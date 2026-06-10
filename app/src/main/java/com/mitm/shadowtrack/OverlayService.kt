package com.mitm.shadowtrack

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
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

    private val eventObserver = Observer<List<GameEvent>> { newList ->
        val prevSize = events.size
        val added = newList.drop(prevSize)
        if (added.isNotEmpty()) {
            events.addAll(added)
            adapter.notifyItemRangeInserted(prevSize, added.size)
            overlayView?.findViewById<RecyclerView>(R.id.rv_events)
                ?.scrollToPosition(events.size - 1)
            // Update count badges
            overlayView?.findViewById<TextView>(R.id.tv_event_count)?.text = events.size.toString()
            overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
                "${events.size} events  ·  last: ${events.last().timeStr}"
            miniView?.findViewById<TextView>(R.id.tv_mini_count)?.text = "${events.size}"
        }
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

    private fun makeParams(w: Int = WindowManager.LayoutParams.WRAP_CONTENT,
                           h: Int = WindowManager.LayoutParams.WRAP_CONTENT,
                           x: Int = 0, y: Int = 120) =
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

        // RecyclerView
        view.findViewById<RecyclerView>(R.id.rv_events).apply {
            layoutManager = LinearLayoutManager(this@OverlayService).also { it.stackFromEnd = true }
            adapter = this@OverlayService.adapter
        }

        val params = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, params)

        // Minimize
        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        // Triple-dot — toggle the drop-down panel
        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            menuPanel.visibility =
                if (menuPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        // Clear logs
        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val sz = events.size
            events.clear()
            adapter.notifyItemRangeRemoved(0, sz)
            view.findViewById<TextView>(R.id.tv_event_count)?.text = "0"
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
            menuPanel.visibility = View.GONE
        }

        // Download logs
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

        val params = makeParams()

        // Mini needs its own touch handler that also fires "expand" on tap.
        // We can't use attachDrag() + setOnClickListener() because ACTION_DOWN
        // returning true prevents View.onTouchEvent() from registering the DOWN,
        // so click listeners never fire. Handle everything here instead.
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

    /**
     * Attaches a drag gesture to [handle] that moves [root] in the WindowManager.
     * KEY: must return true on ACTION_DOWN so the view claims the touch stream.
     */
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
                    true          // ← MUST be true to receive MOVE events
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - event.rawX).toInt()
                    val dy = (event.rawY - rawY).toInt()
                    if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging  // consume if we actually dragged (prevents accidental clicks)
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
            is GameEvent.HandshakeIn  -> "#58A6FF"   // blue
            is GameEvent.LoginOut     -> "#F0883E"   // orange
            is GameEvent.LoginIn      -> "#3FB950"   // green
            is GameEvent.BattleCommand -> "#D29922"  // yellow
            else                       -> "#444C56"  // dim
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
