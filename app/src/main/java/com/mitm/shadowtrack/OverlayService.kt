package com.mitm.shadowtrack

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mitm.shadowtrack.model.GameEvent

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

    private val eventObserver = Observer<List<GameEvent>> { newList ->
        val prevSize = events.size
        val added = newList.drop(prevSize)
        if (added.isNotEmpty()) {
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

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        val total = adapter.itemCount
        return last >= total - 2
    }

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
    }

    // ── WindowManager params ──────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun makeParams(w: Int = dp(360f),
                           h: Int = WindowManager.LayoutParams.WRAP_CONTENT,
                           x: Int = savedX, y: Int = savedY) =
        WindowManager.LayoutParams(
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

        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            menuPanel.visibility =
                if (menuPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val sz = events.size
            events.clear()
            adapter.notifyItemRangeRemoved(0, sz)
            view.findViewById<TextView>(R.id.tv_event_count)?.text = "0"
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
            menuPanel.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val id = AppState.viewModel.gameSocketId.value
            val msgs = if (id != null) AppState.viewModel.getMessages(id) else emptyList()
            LogDownloader.downloadAndShare(this, msgs)
        }

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

    // ── Drag ─────────────────────────────────────────────────────────────

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
                MotionEvent.ACTION_UP -> {
                    dragging
                }
                else -> false
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        AppState.viewModel.gameEvents.removeObserver(eventObserver)
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
            is GameEvent.HandshakeIn  -> "#58A6FF"
            is GameEvent.LoginOut     -> "#F0883E"
            is GameEvent.LoginIn      -> "#3FB950"
            is GameEvent.BattleStarted -> "#FF4444"
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
