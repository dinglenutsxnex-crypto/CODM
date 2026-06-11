package com.mitm.shadowtrack

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
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

    // Stored so we can update flags (focusable toggle for battle data URL input).
    private lateinit var overlayParams: WindowManager.LayoutParams

    private val events = mutableListOf<GameEvent>()
    private lateinit var adapter: GameEventAdapter

    private var savedX: Int = 0
    private var savedY: Int = 120

    private var currentBattleId: String? = null
    private var lastWinConfirmedId: String? = null

    // True while the ARM-WIN intercept is set in TcpHandler.
    private var interceptIsArmed = false

    // Background scope for IO work.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Index into the ViewModel's gameEventList.
    private var vmEventsCursor = 0

    // ── Event log observer ────────────────────────────────────────────────

    private val eventObserver = Observer<List<GameEvent>> { newList ->
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

    // ── Win confirmation observer ──────────────────────────────────────────

    private val winObserver = Observer<List<GameEvent>> { list ->
        val last = list.lastOrNull()
        if (last is GameEvent.WinConfirmed) {
            lastWinConfirmedId = last.battleId
            updateEventsPanel()
        }
    }

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        return lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
    }

    // ── Events panel sync ──────────────────────────────────────────────────

    private fun updateEventsPanel() {
        val v = overlayView ?: return
        val statusTv  = v.findViewById<TextView>(R.id.tv_battle_status) ?: return
        val idTv      = v.findViewById<TextView>(R.id.tv_battle_id)     ?: return
        val winBtn    = v.findViewById<TextView>(R.id.btn_win_battle)   ?: return
        val winStatus = v.findViewById<TextView>(R.id.tv_win_status)    ?: return

        val id = currentBattleId
        when {
            id != null -> {
                statusTv.text = "BATTLE ACTIVE"
                statusTv.setTextColor(Color.parseColor("#FF3FB950"))
                idTv.text = "battle_id: $id"
                idTv.visibility = View.VISIBLE
                winBtn.visibility = View.VISIBLE
                lastWinConfirmedId = null

                if (interceptIsArmed) {
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
                interceptIsArmed = false
                statusTv.text = "WIN CONFIRMED"
                statusTv.setTextColor(Color.parseColor("#FF3FB950"))
                idTv.text = "battle_id: $lastWinConfirmedId  /  server ACK"
                idTv.visibility = View.VISIBLE
                winBtn.visibility = View.GONE
            }
            else -> {
                if (interceptIsArmed) {
                    interceptIsArmed = false
                    TrafficVpnService.instance?.disarmIntercept()
                }
                statusTv.text = "NO ACTIVE BATTLE"
                statusTv.setTextColor(Color.parseColor("#FF8B949E"))
                idTv.visibility = View.GONE
                winBtn.visibility = View.GONE
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

        // Load battle data cache from disk immediately so patchFinishFightToWin
        // has correct maxRounds values available before the first intercept fires.
        BattleDataCache.load(this)

        setupOverlay()
        AppState.viewModel.gameEvents.observeForever(eventObserver)
        AppState.viewModel.gameEvents.observeForever(winObserver)
        AppState.viewModel.currentBattle.observeForever(battleObserver)
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

    // Toggle keyboard-focusable mode on the overlay window.
    // Must be called on the main thread.
    private fun setOverlayFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        if (focusable) {
            overlayParams.flags = overlayParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            overlayParams.flags = overlayParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            overlayParams.flags = overlayParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            overlayParams.flags = overlayParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }
        try { windowManager.updateViewLayout(view, overlayParams) } catch (_: Exception) {}
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

        overlayParams = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, overlayParams)

        // Minimize
        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        // Triple-dot menu
        val menuPanel        = view.findViewById<View>(R.id.panel_menu)
        val panelBattleData  = view.findViewById<View>(R.id.panel_battle_data)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            val open = menuPanel.visibility != View.VISIBLE
            menuPanel.visibility = if (open) View.VISIBLE else View.GONE
            if (!open) {
                // Closing the menu also closes the battle data panel and restores focus state.
                if (panelBattleData.visibility == View.VISIBLE) {
                    dismissBattleDataPanel(view, panelBattleData)
                }
            }
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

        // Download logs
        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val msgs = AppState.viewModel.getAllMessages()
            LogDownloader.downloadAndShare(this, msgs)
        }

        // Battle data panel toggle
        view.findViewById<TextView>(R.id.menu_battle_data).setOnClickListener {
            if (panelBattleData.visibility == View.VISIBLE) {
                dismissBattleDataPanel(view, panelBattleData)
            } else {
                menuPanel.visibility = View.GONE
                showBattleDataPanel(view, panelBattleData)
            }
        }

        // ── Tabs ──────────────────────────────────────────────────────────
        val tabLogs     = view.findViewById<TextView>(R.id.tab_logs)
        val tabEvents   = view.findViewById<TextView>(R.id.tab_events)
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

        // ── ARM WIN button ────────────────────────────────────────────────
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
                interceptIsArmed = false
                vpnInstance.disarmIntercept()
                updateEventsPanel()
            } else {
                interceptIsArmed = true
                vpnInstance.armIntercept()
                updateEventsPanel()
            }
        }

        updateEventsPanel()
        refreshBattleDataStatus(view)

        windowManager.addView(view, overlayParams)
    }

    // ── Battle data panel ─────────────────────────────────────────────────

    private fun showBattleDataPanel(root: View, panel: View) {
        val urlEt = root.findViewById<EditText>(R.id.et_bdc_url)
        if (urlEt.text.isNullOrEmpty()) {
            urlEt.setText(BattleDataCache.DEFAULT_URL)
        }
        refreshBattleDataStatus(root)
        panel.visibility = View.VISIBLE

        // Enable focusable so the EditText and keyboard work.
        setOverlayFocusable(true)

        // Download button
        root.findViewById<TextView>(R.id.btn_bdc_download).setOnClickListener {
            val url = urlEt.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            hideKeyboard(urlEt)
            startBattleDataDownload(root, url)
        }

        // IME done action
        urlEt.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(urlEt)
            val url = urlEt.text.toString().trim()
            if (url.isNotEmpty()) startBattleDataDownload(root, url)
            true
        }
    }

    private fun dismissBattleDataPanel(root: View, panel: View) {
        hideKeyboard(root.findViewById(R.id.et_bdc_url))
        setOverlayFocusable(false)
        panel.visibility = View.GONE
    }

    private fun startBattleDataDownload(root: View, url: String) {
        val progressTv = root.findViewById<TextView>(R.id.tv_bdc_progress)
        val listTv     = root.findViewById<TextView>(R.id.tv_bdc_list)
        val statusTv   = root.findViewById<TextView>(R.id.tv_bdc_status)

        progressTv.visibility = View.VISIBLE
        progressTv.setTextColor(Color.parseColor("#FFD29922"))
        progressTv.text = "Starting…"

        serviceScope.launch {
            BattleDataCache.downloadAndParse(url, this@OverlayService) { msg ->
                launch(Dispatchers.Main) {
                    progressTv.text = msg
                    val isError = msg.startsWith("FAILED")
                    progressTv.setTextColor(
                        Color.parseColor(if (isError) "#FFFF4444" else "#FFD29922")
                    )
                    if (msg.startsWith("Done")) {
                        progressTv.setTextColor(Color.parseColor("#FF3FB950"))
                        refreshBattleDataStatus(root)
                        renderBattleList(listTv)
                        statusTv.text = "${BattleDataCache.count()} battles"
                    }
                }
            }
        }
    }

    private fun refreshBattleDataStatus(root: View) {
        val count    = BattleDataCache.count()
        val statusTv = root.findViewById<TextView>(R.id.tv_bdc_status) ?: return
        val listTv   = root.findViewById<TextView>(R.id.tv_bdc_list) ?: return

        if (count > 0) {
            statusTv.text = "$count battles"
            statusTv.setTextColor(Color.parseColor("#FF3FB950"))
            renderBattleList(listTv)
        } else {
            statusTv.text = "no cache"
            statusTv.setTextColor(Color.parseColor("#FF8B949E"))
            listTv.text = ""
        }
    }

    private fun renderBattleList(tv: TextView) {
        val sb = StringBuilder()
        for (b in BattleDataCache.getList()) {
            sb.append(
                "%8d  %-20s  %s  %dR  %s\n".format(
                    b.id,
                    b.name.take(20),
                    b.type.take(9).padEnd(9),
                    b.rounds,
                    b.visible
                )
            )
        }
        tv.text = if (sb.isEmpty()) "no data" else sb.toString()
    }

    private fun hideKeyboard(view: View?) {
        view ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ── Overlay/mini lifecycle ─────────────────────────────────────────────

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
                    dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - ev.rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (!dragged && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x; savedY = params.y
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) { removeMini(); setupOverlay() }; true }
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
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - event.rawX).toInt()
                    val dy = (event.rawY - rawY).toInt()
                    if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x; savedY = params.y
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
