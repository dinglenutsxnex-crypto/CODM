package com.nexora.hammerscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.LiveMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val liveMessages = mutableListOf<LiveMessage>()
    private lateinit var adapter: PacketAdapter

    private var savedX: Int = 0
    private var savedY: Int = 120

    private var isUserMode = true

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        adapter = PacketAdapter(liveMessages)
        setupOverlay()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Packet Capture Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Overlay UI for packet capture"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Packet Capture Active")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

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

    private fun applyMode(view: View) {
        val rvEvents   = view.findViewById<View>(R.id.rv_events)
        val panelUser  = view.findViewById<View>(R.id.panel_user_mode)
        val panelEvents = view.findViewById<View>(R.id.panel_events)
        val tabRow     = view.findViewById<View>(R.id.layout_tab_row)
        val statusBar  = view.findViewById<View>(R.id.tv_status_bar)
        val eventCount = view.findViewById<View>(R.id.tv_event_count)
        val menuDevItems = view.findViewById<View>(R.id.panel_menu_dev_items)
        val modeToggleTv = view.findViewById<TextView>(R.id.menu_mode_toggle)

        if (isUserMode) {
            tabRow?.visibility       = View.GONE
            rvEvents?.visibility     = View.GONE
            panelEvents?.visibility  = View.GONE
            statusBar?.visibility    = View.GONE
            eventCount?.visibility   = View.GONE
            panelUser?.visibility    = View.VISIBLE
            menuDevItems?.visibility = View.GONE
            modeToggleTv?.text       = "   DEV MODE"

            view.findViewById<TextView>(R.id.tv_label_event_battle)?.let {
                it.text = "Launch CoD Mobile"
                it.setTextColor(Color.parseColor("#FF58A6FF"))
            }
            view.findViewById<View>(R.id.row_event_battle)?.let {
                it.visibility = View.VISIBLE
                it.setOnClickListener { launchCodMobile() }
            }
            view.findViewById<View>(R.id.row_clan_battle)?.visibility = View.GONE
            view.findViewById<View>(R.id.row_brawler)?.visibility = View.GONE
            view.findViewById<View>(R.id.row_raid)?.visibility = View.GONE
        } else {
            tabRow?.visibility       = View.VISIBLE
            rvEvents?.visibility     = View.VISIBLE
            panelEvents?.visibility  = View.GONE
            statusBar?.visibility    = View.VISIBLE
            eventCount?.visibility   = View.VISIBLE
            panelUser?.visibility    = View.GONE
            menuDevItems?.visibility = View.VISIBLE
            modeToggleTv?.text       = "   USER MODE"

            val count = liveMessages.size
            eventCount?.text = count.toString()
            statusBar?.text = "$count packets  ·  last: ${liveMessages.lastOrNull()?.timeStr ?: "--"}"
        }
    }

    private fun launchCodMobile() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(TrafficVpnService.TARGET_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Target app not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            menuPanel.visibility =
                if (menuPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val sz = liveMessages.size
            liveMessages.clear()
            adapter.notifyItemRangeRemoved(0, sz)
            view.findViewById<TextView>(R.id.tv_event_count)?.text = "0"
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
            menuPanel.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val conns = AppState.viewModel.connections.value ?: emptyList()
            LogDownloader.downloadAndShare(this, conns)
        }

        view.findViewById<TextView>(R.id.menu_mode_toggle).setOnClickListener {
            isUserMode = !isUserMode
            menuPanel.visibility = View.GONE
            applyMode(view)
        }

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
        }

        view.findViewById<View>(R.id.row_clan_battle)?.visibility = View.GONE
        view.findViewById<View>(R.id.row_brawler)?.visibility = View.GONE
        view.findViewById<View>(R.id.row_raid)?.visibility = View.GONE

        applyMode(view)
        windowManager.addView(view, params)

        startPollingMessages()
    }

    private var pollingJob: Job? = null

    private fun startPollingMessages() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch(Dispatchers.Main) {
            while (true) {
                delay(500)
                val allMsgs = AppState.viewModel.getAllMessages()
                val currentSize = liveMessages.size
                if (allMsgs.size > currentSize) {
                    val newMsgs = allMsgs.drop(currentSize)
                    liveMessages.addAll(newMsgs)
                    withContext(Dispatchers.Main) {
                        adapter.notifyItemRangeInserted(currentSize, newMsgs.size)
                        val rv = overlayView?.findViewById<RecyclerView>(R.id.rv_events)
                        if (rv != null && isAtBottom(rv)) {
                            rv.scrollToPosition(liveMessages.size - 1)
                        }
                        overlayView?.findViewById<TextView>(R.id.tv_event_count)?.text = liveMessages.size.toString()
                        overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
                            "${liveMessages.size} packets  ·  ${AppState.viewModel.connections.value?.count { it.isLive } ?: 0} active"
                    }
                }
            }
        }
    }

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last >= adapter.itemCount - 2
    }

    private fun removeOverlay() {
        pollingJob?.cancel()
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    private fun showMini() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay_mini, null)
        miniView = view

        view.findViewById<GifView>(R.id.gif_mini)?.setGifResource(R.raw.lexan_effect)

        val miniCountTv = view.findViewById<TextView>(R.id.tv_mini_count)
        if (isUserMode) {
            miniCountTv?.visibility = View.GONE
        } else {
            miniCountTv?.visibility = View.VISIBLE
            miniCountTv?.text = if (liveMessages.isEmpty()) "--" else "${liveMessages.size}"
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

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        removeOverlay()
        removeMini()
        super.onDestroy()
    }
}

class PacketAdapter(private val items: List<LiveMessage>) :
    RecyclerView.Adapter<PacketAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val dirTv: TextView = v.findViewById(R.id.tv_event_label)
        val timeTv: TextView = v.findViewById(R.id.tv_event_time)
        val detailTv: TextView = v.findViewById(R.id.tv_event_detail)
        val colorBar: View = v.findViewById(R.id.view_color_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_event, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val msg = items[pos]
        val dir = if (msg.direction == LiveMessage.Direction.OUTBOUND) ">>" else "<<"
        h.dirTv.text = "$dir ${msg.data.size}B"
        h.dirTv.setTextColor(if (msg.direction == LiveMessage.Direction.OUTBOUND)
            Color.parseColor("#F0883E") else Color.parseColor("#58A6FF"))
        h.timeTv.text = msg.timeStr
        h.detailTv.text = hexPreview(msg.data)
        h.detailTv.visibility = View.VISIBLE
        h.colorBar.setBackgroundColor(if (msg.direction == LiveMessage.Direction.OUTBOUND)
            Color.parseColor("#F0883E") else Color.parseColor("#58A6FF"))
    }

    private fun hexPreview(data: ByteArray): String {
        return data.take(48).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }
}
