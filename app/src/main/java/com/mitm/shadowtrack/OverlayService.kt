package com.mitm.shadowtrack

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mitm.shadowtrack.model.ConnectionViewModel
import com.mitm.shadowtrack.model.GameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.mitm.shadowtrack.OVERLAY_START"
        const val ACTION_STOP  = "com.mitm.shadowtrack.OVERLAY_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var miniView: View? = null

    private val events = mutableListOf<GameEvent>()
    private lateinit var adapter: GameEventAdapter

    private var savedX: Int = 0
    private var savedY: Int = 120

    private var currentBattleId: String? = null
    private var lastWinConfirmedId: String? = null

    // True while the ARM-WIN intercept is set in TcpHandler.
    // The next outbound finish_fight from the game will be replaced with a WIN packet.
    private var interceptIsArmed = false

    // True while the ARM MAX DMG raid intercept is set in TcpHandler.
    // The next outbound raid_fight_finish will have its damage ratio patched to 1.0.
    private var raidInterceptArmed = false

    // Number of rounds to report in the WIN patch (field[5] wonRounds = field[7] totalRounds).
    // Range 1–9. Default 3 (standard Shadow Fight 3 battle).
    private var roundsToWin: Int = 3

    // Last battle ID for which we ran the BattleConfig auto-lookup, so we
    // don't overwrite a user-adjusted value on every panel refresh.
    private var autoSetBattleId: String? = null

    // Background scope for IO work (e.g. injectDirect) that must not run on main thread.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Index into the ViewModel's gameEventList — how many items from that list we've
    // already added to our local `events`.  The ViewModel uses a rolling 2000-item cap;
    // we track by index within the CURRENT snapshot rather than by absolute count so
    // we never lose events when the ViewModel drops old ones from the front.
    private var vmEventsCursor = 0

    // ── Event log observer ────────────────────────────────────────────────

    private val eventObserver = Observer<List<GameEvent>> { newList ->
        // The ViewModel maintains a rolling window (up to 2000 items). Our local `events`
        // list grows without bound (cleared by the Clear button).  We must correctly
        // identify which items in `newList` are new since our last update.
        //
        // Strategy: `vmEventsCursor` is how many items from `newList` we've already
        // consumed.  When the ViewModel hasn't rolled over yet, vmEventsCursor == events.size
        // (they grow together).  After a roll-over the ViewModel drops old events off the
        // front, so newList.size < vmEventsCursor — in that case all remaining items are new.
        val added = if (vmEventsCursor < newList.size) newList.drop(vmEventsCursor) else emptyList()
        vmEventsCursor = newList.size

        if (added.isNotEmpty()) {
            val prevSize = events.size
            events.addAll(added)
            adapter.notifyItemRangeInserted(prevSize, added.size)

            val rv = overlayView?.findViewById<RecyclerView>(R.id.rv_events)
            if (rv != null && isAtBottom(rv)) {
                rv.scrollToPosition(events.size - 1)
            }

            overlayView?.findViewById<TextView>(R.id.tv_event_count)?.text = events.size.toString()
            overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
                "${events.size} events  ·  last: ${events.last().timeStr}"
            miniView?.findViewById<TextView>(R.id.tv_mini_count)?.text = "${events.size}"
        }
    }

    // ── Battle state observer ──────────────────────────────────────────────

    private val battleObserver = Observer<ConnectionViewModel.BattleState?> { state ->
        currentBattleId = state?.battleId
        updateEventsPanel()
    }

    // ── Clan rounds observer ───────────────────────────────────────────────
    // Fires when the ViewModel successfully extracts rounds from the server's
    // inbound clan_start_fight response. Updates roundsToWin so ARM WIN (and
    // the clan auto-intercept) both use the server-specified value.
    private val clanRoundsObserver = Observer<Int?> { rounds ->
        if (rounds == null) return@Observer
        roundsToWin = rounds
        overlayView?.let { v ->
            v.findViewById<TextView>(R.id.tv_rounds_value)?.text = rounds.toString()
            v.findViewById<TextView>(R.id.tv_rounds_label)?.let { label ->
                label.text = "max rounds  "
                label.setTextColor(Color.parseColor("#FF58A6FF"))
            }
        }
        // Mark autoSetBattleId so updateEventsPanel() doesn't overwrite with null lookup.
        autoSetBattleId = currentBattleId
        Toast.makeText(this, "Clan rounds auto-detected: $rounds", Toast.LENGTH_SHORT).show()
    }

    // ── Raid fight observer ────────────────────────────────────────────────
    // Fires when ViewModel detects outbound raid_fight_start (active=true) or
    // inbound raid_fight_finish server ACK (active=false).
    private val raidFightObserver = Observer<Boolean> { active ->
        updateRaidPanel(active)
    }

    private fun updateRaidPanel(active: Boolean) {
        val v         = overlayView ?: return
        val statusTv  = v.findViewById<TextView>(R.id.tv_raid_status)    ?: return
        val btn       = v.findViewById<TextView>(R.id.btn_raid_max_dmg)  ?: return
        val armStatus = v.findViewById<TextView>(R.id.tv_raid_arm_status) ?: return

        if (!active) {
            if (raidInterceptArmed) {
                raidInterceptArmed = false
                TrafficVpnService.instance?.disarmRaidIntercept()
            }
            statusTv.text = "NO ACTIVE RAID"
            statusTv.setTextColor(Color.parseColor("#FF8B949E"))
            btn.visibility = View.GONE
            armStatus.visibility = View.GONE
            return
        }

        statusTv.text = "RAID FIGHT ACTIVE"
        statusTv.setTextColor(Color.parseColor("#FF3FB950"))
        btn.visibility = View.VISIBLE

        if (raidInterceptArmed) {
            btn.text = "⚡ ARMED — play to fight end"
            btn.setTextColor(Color.parseColor("#FF0D1117"))
            btn.setBackgroundColor(Color.parseColor("#FFD29922"))
            armStatus.text = "raid_fight_finish → field[2]=1.0 (boss killed)"
            armStatus.setTextColor(Color.parseColor("#FFD29922"))
            armStatus.visibility = View.VISIBLE
        } else {
            btn.text = "ARM MAX DMG"
            btn.setTextColor(Color.parseColor("#FF0D1117"))
            btn.setBackgroundColor(Color.parseColor("#FFDA3633"))
            armStatus.visibility = View.GONE
        }
    }

    // ── Win confirmation observer (via event stream) ──────────────────────

    private val winObserver = Observer<List<GameEvent>> { list ->
        val last = list.lastOrNull()
        if (last is GameEvent.WinConfirmed) {
            lastWinConfirmedId = last.battleId
            updateEventsPanel()
        }
    }

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last >= adapter.itemCount - 2
    }

    // ── Events panel sync ──────────────────────────────────────────────────

    private fun updateEventsPanel() {
        val v = overlayView ?: return
        val statusTv  = v.findViewById<TextView>(R.id.tv_battle_status) ?: return
        val idTv      = v.findViewById<TextView>(R.id.tv_battle_id)     ?: return
        val winBtn    = v.findViewById<TextView>(R.id.btn_win_battle)   ?: return
        val winStatus = v.findViewById<TextView>(R.id.tv_win_status)    ?: return
        val rowRounds = v.findViewById<View>(R.id.row_rounds)

        val id = currentBattleId
        when {
            id != null -> {
                // Active battle — show ARM WIN button and rounds row.
                // This is the ONLY branch that resets winStatus — a new battle starting
                // means any previous result is stale.
                statusTv.text = "BATTLE ACTIVE"
                statusTv.setTextColor(Color.parseColor("#FF3FB950"))
                idTv.text = "battle_id: $id"
                idTv.visibility = View.VISIBLE
                winBtn.visibility = View.VISIBLE
                rowRounds?.visibility = View.VISIBLE
                lastWinConfirmedId = null

                // Auto-detect rounds from the downloaded config table.
                // Only runs once per unique battle ID so a user-adjusted value is preserved.
                if (id != autoSetBattleId) {
                    autoSetBattleId = id
                    val autoRounds = BattleConfig.roundsFor(id)
                    val labelTv = v.findViewById<android.widget.TextView>(R.id.tv_rounds_label)
                    if (autoRounds != null) {
                        roundsToWin = autoRounds
                        overlayView?.findViewById<android.widget.TextView>(R.id.tv_rounds_value)
                            ?.text = roundsToWin.toString()
                        labelTv?.text = "max rounds  "
                        labelTv?.setTextColor(Color.parseColor("#FF58A6FF"))
                        Toast.makeText(this, "Rounds auto-set: $autoRounds for $id", Toast.LENGTH_SHORT).show()
                    } else {
                        labelTv?.text = "ROUNDS  "
                        labelTv?.setTextColor(Color.parseColor("#FF8B949E"))
                        val loaded = BattleConfig.isLoaded
                        Toast.makeText(this, "No rounds for $id (config loaded=$loaded)", Toast.LENGTH_LONG).show()
                    }
                }

                if (interceptIsArmed) {
                    // Intercept is set — button shows armed state, status shows hint.
                    winBtn.text = "⚡ ARMED — play to fight end"
                    winBtn.setTextColor(Color.parseColor("#FF0D1117"))
                    winBtn.setBackgroundColor(Color.parseColor("#FFD29922"))
                    winStatus.text = "finish_fight will be replaced with WIN"
                    winStatus.setTextColor(Color.parseColor("#FFD29922"))
                    winStatus.visibility = View.VISIBLE
                } else {
                    winBtn.text = "ARM WIN"
                    winBtn.setTextColor(Color.parseColor("#FF0D1117"))
                    winBtn.setBackgroundColor(Color.parseColor("#FF3FB950"))
                    winStatus.visibility = View.GONE
                }
            }
            lastWinConfirmedId != null -> {
                // Server confirmed the win.
                interceptIsArmed = false
                statusTv.text = "WIN CONFIRMED"
                statusTv.setTextColor(Color.parseColor("#FF3FB950"))
                idTv.text = "battle_id: $lastWinConfirmedId  /  server ACK"
                idTv.visibility = View.VISIBLE
                winBtn.visibility = View.GONE
                rowRounds?.visibility = View.GONE
                // winStatus intentionally NOT touched — shows the last intercept/inject result
            }
            else -> {
                // No battle, no recent win — disarm any leftover arm state.
                if (interceptIsArmed) {
                    interceptIsArmed = false
                    TrafficVpnService.instance?.disarmIntercept()
                }
                statusTv.text = "NO ACTIVE BATTLE"
                statusTv.setTextColor(Color.parseColor("#FF8B949E"))
                idTv.visibility = View.GONE
                winBtn.visibility = View.GONE
                rowRounds?.visibility = View.GONE
                // winStatus intentionally NOT touched
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        adapter = GameEventAdapter(events)
        setupOverlay()
        AppState.viewModel.gameEvents.observeForever(eventObserver)
        AppState.viewModel.gameEvents.observeForever(winObserver)
        AppState.viewModel.currentBattle.observeForever(battleObserver)
        AppState.viewModel.clanRounds.observeForever(clanRoundsObserver)
        AppState.viewModel.raidFightActive.observeForever(raidFightObserver)
        // Kick off background download of battle → rounds table.
        BattleConfig.fetchAsync(
            onLoaded = { count, version ->
                Toast.makeText(
                    this,
                    "BattleConfig OK: $count battles (v$version)",
                    Toast.LENGTH_LONG
                ).show()
                // Reset so the active battle re-queries with the now-populated table.
                autoSetBattleId = null
                updateEventsPanel()
            },
            onError = { msg ->
                Toast.makeText(
                    this,
                    "BattleConfig FAILED: $msg",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // ── WindowManager params ──────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun makeParams(
        w: Int = dp(360f),
        h: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        x: Int = savedX,
        y: Int = savedY
    ) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        this.x = x; this.y = y
    }

    // ── Main overlay ──────────────────────────────────────────────────────

    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        overlayView = view

        val rv = view.findViewById<RecyclerView>(R.id.rv_events)
        rv.apply {
            layoutManager = LinearLayoutManager(this@OverlayService).also { it.stackFromEnd = true }
            adapter = this@OverlayService.adapter
        }

        val params = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, params)

        // Minimize
        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        // Triple-dot menu
        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            menuPanel.visibility =
                if (menuPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        // Clear logs
        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val sz = events.size
            events.clear()
            vmEventsCursor = 0
            adapter.notifyItemRangeRemoved(0, sz)
            view.findViewById<TextView>(R.id.tv_event_count)?.text = "0"
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
            menuPanel.visibility = View.GONE
        }

        // Download logs — export raw bytes from ALL connections so the server's
        // battle responses (on battleSocketId) are included, not just gameSocketId.
        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val msgs = AppState.viewModel.getAllMessages()
            LogDownloader.downloadAndShare(this, msgs)
        }

        // ── Tabs ──────────────────────────────────────────────────────────
        val tabLogs   = view.findViewById<TextView>(R.id.tab_logs)
        val tabEvents = view.findViewById<TextView>(R.id.tab_events)
        val panelEvents = view.findViewById<View>(R.id.panel_events)

        tabLogs.setOnClickListener {
            rv.visibility = View.VISIBLE
            panelEvents.visibility = View.GONE
            tabLogs.setTextColor(Color.parseColor("#FF58A6FF"))
            tabLogs.setBackgroundColor(Color.parseColor("#FF0D1117"))
            tabEvents.setTextColor(Color.parseColor("#FF8B949E"))
            tabEvents.setBackgroundColor(Color.TRANSPARENT)
        }

        tabEvents.setOnClickListener {
            rv.visibility = View.GONE
            panelEvents.visibility = View.VISIBLE
            tabEvents.setTextColor(Color.parseColor("#FF58A6FF"))
            tabEvents.setBackgroundColor(Color.parseColor("#FF0D1117"))
            tabLogs.setTextColor(Color.parseColor("#FF8B949E"))
            tabLogs.setBackgroundColor(Color.TRANSPARENT)
            updateEventsPanel()
        }

        // ── Rounds counter ────────────────────────────────────────────────
        val roundsValueTv = view.findViewById<TextView>(R.id.tv_rounds_value)
        roundsValueTv?.text = roundsToWin.toString()

        view.findViewById<TextView>(R.id.btn_rounds_dec)?.setOnClickListener {
            if (roundsToWin > 1) {
                roundsToWin--
                roundsValueTv?.text = roundsToWin.toString()
            }
        }

        view.findViewById<TextView>(R.id.btn_rounds_inc)?.setOnClickListener {
            if (roundsToWin < 9) {
                roundsToWin++
                roundsValueTv?.text = roundsToWin.toString()
            }
        }

        // ── ARM MAX DMG button (raid intercept) ──────────────────────────
        view.findViewById<TextView>(R.id.btn_raid_max_dmg)?.setOnClickListener {
            val armStatus = view.findViewById<TextView>(R.id.tv_raid_arm_status)
            val vpnInstance = TrafficVpnService.instance
            if (vpnInstance == null) {
                armStatus?.text = "✗ FAIL: VPN not running"
                armStatus?.setTextColor(Color.parseColor("#FFFF4444"))
                armStatus?.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (raidInterceptArmed) {
                raidInterceptArmed = false
                vpnInstance.disarmRaidIntercept()
            } else {
                raidInterceptArmed = true
                vpnInstance.armRaidIntercept()
            }
            updateRaidPanel(AppState.viewModel.raidFightActive.value == true)
        }

        // ── Win Battle button (ARM WIN mode) ─────────────────────────────
        // ARM WIN is the correct MITM approach: instead of injecting mid-fight
        // (which the game client ignores — it's busy playing and not waiting for a
        // server response), we arm an intercept that fires when the game naturally
        // sends its own event_battle_finish_fight (on fight end / surrender / timeout).
        // HAMMERSCALE replaces that packet with a crafted WIN using the SAME counter,
        // so the server responds on the connection the game is already listening on.
        // The game processes the win response and shows the WIN screen.
        view.findViewById<TextView>(R.id.btn_win_battle).setOnClickListener {
            val winStatus = view.findViewById<TextView>(R.id.tv_win_status)

            val vpnInstance = TrafficVpnService.instance
            if (vpnInstance == null) {
                winStatus.text = "✗ FAIL: VPN not running"
                winStatus.setTextColor(Color.parseColor("#FFFF4444"))
                winStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (interceptIsArmed) {
                // Second press while armed → disarm / cancel
                interceptIsArmed = false
                vpnInstance.disarmIntercept()
                updateEventsPanel()
            } else {
                // First press → arm the intercept with the configured rounds value
                interceptIsArmed = true
                vpnInstance.armIntercept(roundsToWin)
                updateEventsPanel()
            }
        }

        // Sync current battle state immediately
        updateEventsPanel()

        windowManager.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    // ── Mini badge ────────────────────────────────────────────────────────

    private fun showMini() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay_mini, null)
        miniView = view
        view.findViewById<TextView>(R.id.tv_mini_count)?.text =
            if (events.isEmpty()) "SF3" else "${events.size}"

        val params = makeParams(w = dp(80f), h = dp(80f))

        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var dragged = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = ev.rawX; rawY = ev.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - ev.rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (!dragged && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x
                        savedY = params.y
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) { removeMini(); setupOverlay() }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun removeMini() {
        miniView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        miniView = null
    }

    // ── Drag ──────────────────────────────────────────────────────────────

    private fun attachDrag(handle: View, root: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = event.rawX; rawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - event.rawX).toInt()
                    val dy = (event.rawY - rawY).toInt()
                    if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x
                        savedY = params.y
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }

    // ── onDestroy ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        AppState.viewModel.gameEvents.removeObserver(eventObserver)
        AppState.viewModel.gameEvents.removeObserver(winObserver)
        AppState.viewModel.currentBattle.removeObserver(battleObserver)
        AppState.viewModel.clanRounds.removeObserver(clanRoundsObserver)
        AppState.viewModel.raidFightActive.removeObserver(raidFightObserver)
        serviceScope.cancel()
        removeOverlay()
        removeMini()
        super.onDestroy()
    }
}

// ── RecyclerView adapter ───────────────────────────────────────────────────

class GameEventAdapter(private val items: List<GameEvent>) :
    RecyclerView.Adapter<GameEventAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val colorBar = v.findViewById<View>(R.id.view_color_bar)
        val label    = v.findViewById<TextView>(R.id.tv_event_label)
        val time     = v.findViewById<TextView>(R.id.tv_event_time)
        val detail   = v.findViewById<TextView>(R.id.tv_event_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_event, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ev = items[pos]

        val colorHex = when (ev) {
            is GameEvent.HandshakeOut,
            is GameEvent.HandshakeIn   -> "#58A6FF"
            is GameEvent.LoginOut      -> "#F0883E"
            is GameEvent.LoginIn       -> "#3FB950"
            is GameEvent.BattleStarted -> "#FF4444"
            is GameEvent.WinConfirmed  -> "#3FB950"
            is GameEvent.BattleCommand -> "#D29922"
            else                       -> "#444C56"
        }
        val color = Color.parseColor(colorHex)

        h.colorBar.setBackgroundColor(color)
        h.label.text = ev.label
        h.label.setTextColor(color)
        h.time.text  = ev.timeStr

        val det = ev.detail
        if (det.isNotEmpty()) {
            h.detail.text = det
            h.detail.visibility = View.VISIBLE
        } else {
            h.detail.visibility = View.GONE
        }
    }
}
