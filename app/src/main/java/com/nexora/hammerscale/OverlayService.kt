package com.nexora.hammerscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexora.hammerscale.model.ConnectionViewModel
import com.nexora.hammerscale.model.GameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.nexora.hammerscale.OVERLAY_START"
        const val ACTION_STOP  = "com.nexora.hammerscale.OVERLAY_STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hammerscale_overlay"
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

    private var interceptIsArmed    = false
    private var raidInterceptArmed  = false
    private var brawlerInterceptArmed = false
    private var brawlerBattleActive   = false

    private var roundsToWin: Int = 3
    private var autoSetBattleId: String? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Delayed auto-arm job for event/clan battles — cancelled if battle ends before the 3-sec window fires.
    private var pendingArmJob: Job? = null

    // Separate arm job for brawler — kept isolated so event/clan battle events cannot cancel it.
    private var pendingBrawlerArmJob: Job? = null

    private var vmEventsCursor = 0

    // ── Mode & user-mode toggle state ──────────────────────────────────────
    private var isUserMode = true

    // Which type of battle is currently active (used to color user-mode labels)
    private enum class BattleType { NONE, EVENT, CLAN }
    private var activeBattleType = BattleType.NONE

    // Whether each user-mode toggle is switched on
    private var userEventBattleEnabled = false
    private var userClanBattleEnabled  = false
    private var userRaidEnabled        = false
    private var userBrawlerEnabled     = false

    // Normal label color for user-mode toggles
    private val labelColorNormal = Color.parseColor("#FFE6EDF3")
    // Red when a battle is active for that slot
    private val labelColorActive = Color.parseColor("#FFFF4444")

    // Switch track/thumb color states
    private val switchOnColor  = Color.parseColor("#FF3FB950")
    private val switchOffColor = Color.parseColor("#FF444C56")
    private val thumbOnColor   = Color.WHITE
    private val thumbOffColor  = Color.parseColor("#FF8B949E")

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

            if (!isUserMode) {
                overlayView?.findViewById<TextView>(R.id.tv_event_count)?.text = events.size.toString()
                overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
                    "${events.size} events  ·  last: ${events.last().timeStr}"
            }
            miniView?.findViewById<TextView>(R.id.tv_mini_count)?.apply {
                if (!isUserMode) text = "${events.size}"
            }
        }
    }

    // ── Battle state observer ──────────────────────────────────────────────

    private val battleObserver = Observer<ConnectionViewModel.BattleState?> { state ->
        currentBattleId = state?.battleId
        updateEventsPanel()
    }

    // ── Clan rounds observer ───────────────────────────────────────────────
    private val clanRoundsObserver = Observer<Int?> { rounds ->
        if (rounds == null) return@Observer
        val prev = roundsToWin
        roundsToWin = rounds
        overlayView?.let { v ->
            v.findViewById<TextView>(R.id.tv_rounds_value)?.text = rounds.toString()
            v.findViewById<TextView>(R.id.tv_rounds_label)?.let { label ->
                label.text = "max rounds  "
                label.setTextColor(Color.parseColor("#FF58A6FF"))
            }
        }
        autoSetBattleId = currentBattleId
        // If intercept is already armed (user-mode auto-armed before server responded),
        // update the stored rounds value so the patch uses the real server value.
        if (interceptIsArmed) {
            TrafficVpnService.instance?.armIntercept(roundsToWin)
            Toast.makeText(this, "Clan rounds: $rounds (was $prev) — intercept updated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clan rounds from server: $rounds", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Battle sequence observer ───────────────────────────────────────────
    // Fires when the server's inbound event_battle_start_fight is received.
    // seq = 0-indexed sub-battle number (field[3] in params; absent = 0).
    //
    // For multi-fight skeleton battles (battles.json value is an array):
    //   Use seq as the index into the array to get the rounds-to-win for THIS
    //   specific sub-battle, then update roundsToWin and the overlay display.
    //
    // For single-fight battles (battles.json value is an int):
    //   Skip entirely — roundsToWin is already set correctly from the JSON lookup
    //   in updateEventsPanel(); no server packet analysis needed.
    private val battleSeqObserver = Observer<Int?> { seq ->
        if (seq == null) return@Observer
        val id = currentBattleId ?: return@Observer

        // Only act on multi-fight (skeleton) battles
        if (!BattleConfig.isMultiFight(id)) return@Observer

        val roundsForThisFight = BattleConfig.roundsFor(id, seq) ?: return@Observer
        val totalFights        = BattleConfig.totalFightsFor(id)

        roundsToWin = roundsForThisFight
        if (interceptIsArmed) {
            TrafficVpnService.instance?.armIntercept(roundsToWin)
        }

        // Update overlay: show per-fight round count and progress, e.g. "2 · fight 1/4"
        overlayView?.let { v ->
            v.findViewById<TextView>(R.id.tv_rounds_value)?.text = roundsForThisFight.toString()
            v.findViewById<TextView>(R.id.tv_rounds_label)?.let { label ->
                label.text = "rounds · fight ${seq + 1}/$totalFights  "
                label.setTextColor(Color.parseColor("#FF58A6FF"))
            }
        }
    }

    // ── Raid fight observer ────────────────────────────────────────────────
    private val raidFightObserver = Observer<Boolean> { active ->
        val wasArmed = raidInterceptArmed   // capture before updateRaidPanel resets it
        updateRaidPanel(active)
        updateUserModeRaidLabel(active)
        if (active && userRaidEnabled) {
            armRaid()                       // same pipeline as the button
        } else if (!active && wasArmed && isUserMode) {
            flashLabelGreen(R.id.tv_label_raid)
        }
    }

    // ── Game events observer (used to detect battle type for user-mode labels) ──
    private val gameEventsForTypeObserver = Observer<List<GameEvent>> { list ->
        val last = list.lastOrNull()
        when (last) {
            is GameEvent.BattleStarted -> {
                activeBattleType = if (last.commandName == "event_battle_start_fight")
                    BattleType.EVENT else BattleType.CLAN
                updateUserModeBattleLabels()

                val shouldArm = (activeBattleType == BattleType.EVENT && userEventBattleEnabled) ||
                                (activeBattleType == BattleType.CLAN  && userClanBattleEnabled)
                if (shouldArm) {
                    // Delay 3 s so BattleConfig / clan server response can settle roundsToWin
                    // then call the exact same function the button calls.
                    pendingArmJob?.cancel()
                    pendingArmJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        delay(3_000)
                        armBattleIntercept()    // same pipeline as the button
                    }
                }
            }
            is GameEvent.WinConfirmed -> {
                val typeWon = activeBattleType
                pendingArmJob?.cancel()
                pendingArmJob = null
                activeBattleType = BattleType.NONE
                disarmBattleIntercept()         // same pipeline as the button
                updateUserModeBattleLabels()
                if (isUserMode) {
                    when (typeWon) {
                        BattleType.EVENT -> flashLabelGreen(R.id.tv_label_event_battle)
                        BattleType.CLAN  -> flashLabelGreen(R.id.tv_label_clan_battle)
                        else             -> {}
                    }
                }
            }
            is GameEvent.BrawlerFinished -> {
                val wasWin = last.result == "WIN"
                pendingBrawlerArmJob?.cancel()
                pendingBrawlerArmJob = null
                brawlerBattleActive = false
                disarmBrawlerIntercept()
                updateBrawlerPanel()
                updateUserModeBrawlerLabel()
                if (isUserMode && userBrawlerEnabled && wasWin) flashLabelGreen(R.id.tv_label_brawler)
            }
            is GameEvent.BattleCommand -> {
                if (last.name in setOf("finish_fight", "event_battle_finish_fight", "clan_finish_fight")) {
                    pendingArmJob?.cancel()
                    pendingArmJob = null
                    activeBattleType = BattleType.NONE
                    disarmBattleIntercept()
                    updateUserModeBattleLabels()
                }
                if (last.name == "brawler_start" && last.isOutbound) {
                    android.util.Log.d("HammerBrawler", "brawler_start OUTBOUND: userBrawlerEnabled=$userBrawlerEnabled brawlerBattleActive=$brawlerBattleActive")
                    brawlerBattleActive = true
                    updateBrawlerPanel()
                    updateUserModeBrawlerLabel()
                    if (userBrawlerEnabled) {
                        // Use a SEPARATE job so event/clan battle events cannot cancel this arm.
                        // No delay needed — brawler has no roundsToWin config to wait for.
                        pendingBrawlerArmJob?.cancel()
                        pendingBrawlerArmJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            armBrawlerIntercept()
                        }
                    } else {
                        android.util.Log.w("HammerBrawler", "brawler_start: userBrawlerEnabled=FALSE — intercept NOT armed")
                    }
                }
                if (last.name == "brawler_finish" && last.isOutbound) {
                    pendingBrawlerArmJob?.cancel()
                    pendingBrawlerArmJob = null
                    brawlerBattleActive = false
                    disarmBrawlerIntercept()
                    updateBrawlerPanel()
                    updateUserModeBrawlerLabel()
                }
            }
            else -> {}
        }
    }

    // ── Win confirmation observer ─────────────────────────────────────────
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

    // ── User mode label helpers ────────────────────────────────────────────

    private fun updateUserModeBattleLabels() {
        val v = overlayView ?: return
        val tvEvent = v.findViewById<TextView>(R.id.tv_label_event_battle) ?: return
        val tvClan  = v.findViewById<TextView>(R.id.tv_label_clan_battle)  ?: return
        tvEvent.setTextColor(if (activeBattleType == BattleType.EVENT) labelColorActive else labelColorNormal)
        tvClan.setTextColor(if (activeBattleType == BattleType.CLAN)  labelColorActive else labelColorNormal)
    }

    private fun updateUserModeRaidLabel(raidActive: Boolean) {
        val v = overlayView ?: return
        val tvRaid = v.findViewById<TextView>(R.id.tv_label_raid) ?: return
        tvRaid.setTextColor(if (raidActive) labelColorActive else labelColorNormal)
    }

    private fun updateUserModeBrawlerLabel() {
        val tv = overlayView?.findViewById<TextView>(R.id.tv_label_brawler) ?: return
        tv.setTextColor(if (brawlerBattleActive) labelColorActive else labelColorNormal)
    }

    private fun updateBrawlerPanel() {
        val v         = overlayView ?: return
        val statusTv  = v.findViewById<TextView>(R.id.tv_brawler_status)    ?: return
        val btn       = v.findViewById<TextView>(R.id.btn_brawler_win)       ?: return
        val armStatus = v.findViewById<TextView>(R.id.tv_brawler_arm_status) ?: return

        if (!brawlerBattleActive) {
            statusTv.text = "NO ACTIVE BRAWLER"
            statusTv.setTextColor(Color.parseColor("#FF8B949E"))
            btn.visibility = View.GONE
            armStatus.visibility = View.GONE
            return
        }

        statusTv.text = "BRAWLER FIGHT ACTIVE"
        statusTv.setTextColor(Color.parseColor("#FF3FB950"))
        btn.visibility = View.VISIBLE

        if (brawlerInterceptArmed) {
            btn.text = "⚡ ARMED — play to fight end"
            btn.setTextColor(Color.parseColor("#FF0D1117"))
            btn.setBackgroundColor(Color.parseColor("#FFD29922"))
            armStatus.text = "brawler_finish → patched to WIN"
            armStatus.setTextColor(Color.parseColor("#FFD29922"))
            armStatus.visibility = View.VISIBLE
        } else {
            btn.text = "ARM BRAWLER WIN"
            btn.setTextColor(Color.parseColor("#FF0D1117"))
            btn.setBackgroundColor(Color.parseColor("#FF58A6FF"))
            armStatus.visibility = View.GONE
        }
    }

    // ── Shared intercept pipeline ─────────────────────────────────────────
    // Single source of truth — both dev-mode buttons and user-mode auto-arm
    // call these. No duplicated logic.

    private fun armBattleIntercept() {
        if (interceptIsArmed) return
        val vpn = TrafficVpnService.instance ?: return
        interceptIsArmed = true
        vpn.armIntercept(roundsToWin)
        updateEventsPanel()
    }

    private fun disarmBattleIntercept() {
        if (!interceptIsArmed) return
        interceptIsArmed = false
        TrafficVpnService.instance?.disarmIntercept()
        updateEventsPanel()
    }

    private fun armRaid() {
        if (raidInterceptArmed) return
        val vpn = TrafficVpnService.instance ?: return
        raidInterceptArmed = true
        vpn.armRaidIntercept()
        updateRaidPanel(true)
    }

    private fun disarmRaid() {
        if (!raidInterceptArmed) return
        raidInterceptArmed = false
        TrafficVpnService.instance?.disarmRaidIntercept()
        updateRaidPanel(AppState.viewModel.raidFightActive.value == true)
    }

    private fun armBrawlerIntercept() {
        if (brawlerInterceptArmed) {
            android.util.Log.d("HammerBrawler", "armBrawlerIntercept: already armed, skipping")
            return
        }
        val vpn = TrafficVpnService.instance
        if (vpn == null) {
            android.util.Log.e("HammerBrawler", "armBrawlerIntercept: VPN instance is null — arm FAILED")
            return
        }
        brawlerInterceptArmed = true
        vpn.armBrawlerIntercept()
        android.util.Log.d("HammerBrawler", "armBrawlerIntercept: ARMED (brawlerBattleActive=$brawlerBattleActive)")
        updateBrawlerPanel()
    }

    private fun disarmBrawlerIntercept() {
        if (!brawlerInterceptArmed) {
            android.util.Log.d("HammerBrawler", "disarmBrawlerIntercept: already disarmed")
            return
        }
        android.util.Log.d("HammerBrawler", "disarmBrawlerIntercept: DISARMED")
        brawlerInterceptArmed = false
        TrafficVpnService.instance?.disarmBrawlerIntercept()
        updateBrawlerPanel()
    }

    // ── Green flash helper (user-mode server-confirm feedback) ────────────
    private fun flashLabelGreen(labelResId: Int) {
        val tv = overlayView?.findViewById<TextView>(labelResId) ?: return
        tv.setTextColor(Color.parseColor("#FF3FB950"))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            overlayView?.findViewById<TextView>(labelResId)
                ?.setTextColor(labelColorNormal)
        }, 2_000)
    }

    // ── Switch styling helper ──────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun styleSwitch(sw: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        sw.trackTintList = ColorStateList(states, intArrayOf(switchOnColor, switchOffColor))
        sw.thumbTintList = ColorStateList(states, intArrayOf(thumbOnColor, thumbOffColor))
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
                statusTv.text = "BATTLE ACTIVE"
                statusTv.setTextColor(Color.parseColor("#FF3FB950"))
                idTv.text = "battle_id: $id"
                idTv.visibility = View.VISIBLE
                winBtn.visibility = View.VISIBLE
                rowRounds?.visibility = View.VISIBLE
                lastWinConfirmedId = null

                if (id != autoSetBattleId) {
                    autoSetBattleId = id
                    val autoRounds = BattleConfig.roundsFor(id)
                    val labelTv = v.findViewById<android.widget.TextView>(R.id.tv_rounds_label)
                    if (autoRounds != null) {
                        roundsToWin = autoRounds
                        // If the intercept was already armed (user-mode auto-armed before
                        // BattleConfig responded), update the stored rounds value now.
                        if (interceptIsArmed) {
                            TrafficVpnService.instance?.armIntercept(roundsToWin)
                        }
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
                rowRounds?.visibility = View.GONE
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
                rowRounds?.visibility = View.GONE
            }
        }
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

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create notification channel and start foreground service to prevent being killed
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        adapter = GameEventAdapter(events)
        setupOverlay()
        AppState.viewModel.gameEvents.observeForever(eventObserver)
        AppState.viewModel.gameEvents.observeForever(winObserver)
        AppState.viewModel.gameEvents.observeForever(gameEventsForTypeObserver)
        AppState.viewModel.currentBattle.observeForever(battleObserver)
        AppState.viewModel.clanRounds.observeForever(clanRoundsObserver)
        AppState.viewModel.battleSeq.observeForever(battleSeqObserver)
        AppState.viewModel.raidFightActive.observeForever(raidFightObserver)
        
        BattleConfig.loadAsync(
            resources,
            onLoaded = { count, version ->
                Toast.makeText(this, "BattleConfig OK: $count battles (v$version)", Toast.LENGTH_LONG).show()
                autoSetBattleId = null
                updateEventsPanel()
            },
            onError = { msg ->
                Toast.makeText(this, "BattleConfig FAILED: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HammerScale Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the overlay active in the background"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Stop action intent
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HammerScale Active")
            .setContentText("Tap to open · Swipe to dismiss")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
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

    // ── Mode switching ────────────────────────────────────────────────────

    private fun applyMode(view: View) {
        val tabRow      = view.findViewById<View>(R.id.layout_tab_row)
        val rvEvents    = view.findViewById<View>(R.id.rv_events)
        val panelEvents = view.findViewById<View>(R.id.panel_events)
        val panelUser   = view.findViewById<View>(R.id.panel_user_mode)
        val statusBar   = view.findViewById<View>(R.id.tv_status_bar)
        val eventCount  = view.findViewById<View>(R.id.tv_event_count)
        val menuDevItems = view.findViewById<View>(R.id.panel_menu_dev_items)
        val modeToggleTv = view.findViewById<TextView>(R.id.menu_mode_toggle)

        if (isUserMode) {
            tabRow?.visibility      = View.GONE
            rvEvents?.visibility    = View.GONE
            panelEvents?.visibility = View.GONE
            statusBar?.visibility   = View.GONE
            eventCount?.visibility  = View.GONE
            panelUser?.visibility   = View.VISIBLE
            menuDevItems?.visibility = View.GONE
            modeToggleTv?.text      = "   DEV MODE"
            // Refresh label colors for current states
            updateUserModeBattleLabels()
            updateUserModeRaidLabel(AppState.viewModel.raidFightActive.value == true)
            updateUserModeBrawlerLabel()
        } else {
            tabRow?.visibility      = View.VISIBLE
            // rv_events visible only when logs tab is active — restore logs tab as default
            rvEvents?.visibility    = View.VISIBLE
            panelEvents?.visibility = View.GONE
            statusBar?.visibility   = View.VISIBLE
            eventCount?.visibility  = View.VISIBLE
            panelUser?.visibility   = View.GONE
            menuDevItems?.visibility = View.VISIBLE
            modeToggleTv?.text      = "   USER MODE"
        }
    }

    // ── Main overlay ──────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        view.clipToOutline = true
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
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

        // Clear logs (dev mode only)
        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val sz = events.size
            events.clear()
            vmEventsCursor = 0
            adapter.notifyItemRangeRemoved(0, sz)
            view.findViewById<TextView>(R.id.tv_event_count)?.text = "0"
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
            menuPanel.visibility = View.GONE
        }

        // Download logs (dev mode only)
        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val msgs = AppState.viewModel.getAllMessages()
            LogDownloader.downloadAndShare(this, msgs)
        }

        // Mode toggle
        view.findViewById<TextView>(R.id.menu_mode_toggle).setOnClickListener {
            isUserMode = !isUserMode
            menuPanel.visibility = View.GONE
            applyMode(view)
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
            if (roundsToWin > 1) { roundsToWin--; roundsValueTv?.text = roundsToWin.toString() }
        }

        view.findViewById<TextView>(R.id.btn_rounds_inc)?.setOnClickListener {
            if (roundsToWin < 9) { roundsToWin++; roundsValueTv?.text = roundsToWin.toString() }
        }

        // ── ARM MAX DMG button (raid intercept) ──────────────────────────
        view.findViewById<TextView>(R.id.btn_raid_max_dmg)?.setOnClickListener {
            if (TrafficVpnService.instance == null) {
                view.findViewById<TextView>(R.id.tv_raid_arm_status)?.apply {
                    text = "✗ FAIL: VPN not running"
                    setTextColor(Color.parseColor("#FFFF4444"))
                    visibility = View.VISIBLE
                }
                return@setOnClickListener
            }
            if (raidInterceptArmed) disarmRaid() else armRaid()
        }

        // ── ARM WIN button ────────────────────────────────────────────────
        view.findViewById<TextView>(R.id.btn_win_battle).setOnClickListener {
            if (TrafficVpnService.instance == null) {
                view.findViewById<TextView>(R.id.tv_win_status)?.apply {
                    text = "✗ FAIL: VPN not running"
                    setTextColor(Color.parseColor("#FFFF4444"))
                    visibility = View.VISIBLE
                }
                return@setOnClickListener
            }
            if (interceptIsArmed) disarmBattleIntercept() else armBattleIntercept()
        }

        // ── User mode switches ────────────────────────────────────────────
        val swEvent   = view.findViewById<Switch>(R.id.sw_event_battle)
        val swClan    = view.findViewById<Switch>(R.id.sw_clan_battle)
        val swRaid    = view.findViewById<Switch>(R.id.sw_raid)
        val swBrawler = view.findViewById<Switch>(R.id.sw_brawler)

        styleSwitch(swEvent)
        styleSwitch(swClan)
        styleSwitch(swRaid)
        styleSwitch(swBrawler)

        // Restore session state BEFORE attaching listeners so setting isChecked
        // doesn't fire the callbacks and trigger spurious arm/disarm calls.
        swEvent.isChecked   = userEventBattleEnabled
        swClan.isChecked    = userClanBattleEnabled
        swRaid.isChecked    = userRaidEnabled
        swBrawler.isChecked = userBrawlerEnabled

        swEvent.setOnCheckedChangeListener { _, checked ->
            userEventBattleEnabled = checked
            if (checked) {
                if (activeBattleType == BattleType.EVENT) {
                    pendingArmJob?.cancel()
                    pendingArmJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        delay(3_000)
                        armBattleIntercept()
                    }
                }
            } else {
                pendingArmJob?.cancel()
                pendingArmJob = null
                if (activeBattleType == BattleType.EVENT) disarmBattleIntercept()
            }
        }

        swClan.setOnCheckedChangeListener { _, checked ->
            userClanBattleEnabled = checked
            if (checked) {
                if (activeBattleType == BattleType.CLAN) {
                    pendingArmJob?.cancel()
                    pendingArmJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        delay(3_000)
                        armBattleIntercept()
                    }
                }
            } else {
                pendingArmJob?.cancel()
                pendingArmJob = null
                if (activeBattleType == BattleType.CLAN) disarmBattleIntercept()
            }
        }

        swRaid.setOnCheckedChangeListener { _, checked ->
            userRaidEnabled = checked
            if (checked && AppState.viewModel.raidFightActive.value == true) {
                armRaid()
            } else if (!checked) {
                disarmRaid()
            }
        }

        swBrawler.setOnCheckedChangeListener { _, checked ->
            userBrawlerEnabled = checked
            if (checked && brawlerBattleActive) {
                pendingBrawlerArmJob?.cancel()
                pendingBrawlerArmJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    armBrawlerIntercept()
                }
            } else if (!checked) {
                pendingBrawlerArmJob?.cancel()
                pendingBrawlerArmJob = null
                disarmBrawlerIntercept()
            }
        }

        // ── Row click handlers for user mode toggles ─────────────────────
        view.findViewById<View>(R.id.row_event_battle)?.setOnClickListener {
            swEvent.isChecked = !swEvent.isChecked
        }
        view.findViewById<View>(R.id.row_clan_battle)?.setOnClickListener {
            swClan.isChecked = !swClan.isChecked
        }
        view.findViewById<View>(R.id.row_raid)?.setOnClickListener {
            swRaid.isChecked = !swRaid.isChecked
        }
        view.findViewById<View>(R.id.row_brawler)?.setOnClickListener {
            swBrawler.isChecked = !swBrawler.isChecked
        }

        // ── ARM BRAWLER WIN button (dev mode brawler intercept) ──────────
        view.findViewById<TextView>(R.id.btn_brawler_win)?.setOnClickListener {
            if (TrafficVpnService.instance == null) {
                view.findViewById<TextView>(R.id.tv_brawler_arm_status)?.apply {
                    text = "✗ FAIL: VPN not running"
                    setTextColor(Color.parseColor("#FFFF4444"))
                    visibility = View.VISIBLE
                }
                return@setOnClickListener
            }
            if (brawlerInterceptArmed) disarmBrawlerIntercept() else armBrawlerIntercept()
        }

        // Apply initial mode state
        applyMode(view)

        // Sync current battle state
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

        view.findViewById<GifView>(R.id.gif_mini)?.setGifResource(R.raw.lexan_effect)

        val miniCountTv = view.findViewById<TextView>(R.id.tv_mini_count)
        if (isUserMode) {
            miniCountTv?.visibility = View.GONE
        } else {
            miniCountTv?.visibility = View.VISIBLE
            miniCountTv?.text = if (events.isEmpty()) "--" else "${events.size}"
        }

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
        AppState.viewModel.gameEvents.removeObserver(gameEventsForTypeObserver)
        AppState.viewModel.currentBattle.removeObserver(battleObserver)
        AppState.viewModel.clanRounds.removeObserver(clanRoundsObserver)
        AppState.viewModel.battleSeq.removeObserver(battleSeqObserver)
        AppState.viewModel.raidFightActive.removeObserver(raidFightObserver)
        pendingArmJob?.cancel()
        pendingBrawlerArmJob?.cancel()
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
            is GameEvent.BattleStarted   -> "#FF4444"
            is GameEvent.WinConfirmed    -> "#3FB950"
            is GameEvent.BrawlerFinished -> if ((ev as GameEvent.BrawlerFinished).result == "WIN") "#3FB950" else "#F85149"
            is GameEvent.BattleCommand   -> "#D29922"
            else                         -> "#444C56"
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
