package com.mitm.shadowtrack

import android.app.Service
import android.content.Intent
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

    // observeForever must be removed in onDestroy to avoid leaks
    private val eventObserver = Observer<List<GameEvent>> { newList ->
        val prevSize = events.size
        val added = newList.drop(prevSize)
        if (added.isNotEmpty()) {
            events.addAll(added)
            adapter.notifyItemRangeInserted(prevSize, added.size)
            overlayView?.findViewById<RecyclerView>(R.id.rv_events)
                ?.scrollToPosition(events.size - 1)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        adapter = GameEventAdapter(events)

        setupOverlay()

        // Use the singleton ViewModel — safe since we observeForever and remove in onDestroy
        AppState.viewModel.gameEvents.observeForever(eventObserver)
    }

    private fun buildParams(gravity: Int = Gravity.TOP or Gravity.END, x: Int = 0, y: Int = 140)
        = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity; this.x = x; this.y = y }

    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        overlayView = view

        val rv = view.findViewById<RecyclerView>(R.id.rv_events)
        rv.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        rv.adapter = adapter

        val params = buildParams()
        makeDraggable(view.findViewById(R.id.overlay_header), view, params, isOverlay = true)

        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            windowManager.removeView(view)
            overlayView = null
            showMini()
        }

        view.findViewById<TextView>(R.id.btn_clear).setOnClickListener {
            val prevSize = events.size
            events.clear()
            adapter.notifyItemRangeRemoved(0, prevSize)
        }

        windowManager.addView(view, params)
    }

    private fun showMini() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay_mini, null)
        miniView = view
        val params = buildParams()
        makeDraggable(view, view, params, isOverlay = false)
        view.setOnClickListener {
            windowManager.removeView(view)
            miniView = null
            setupOverlay()
        }
        windowManager.addView(view, params)
    }

    private fun makeDraggable(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        isOverlay: Boolean
    ) {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> moved // consume click if we dragged
            }
        }
    }

    override fun onDestroy() {
        AppState.viewModel.gameEvents.removeObserver(eventObserver)
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        miniView?.let  { try { windowManager.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}

// ── RecyclerView adapter ───────────────────────────────────────────────────

class GameEventAdapter(private val items: List<GameEvent>) :
    RecyclerView.Adapter<GameEventAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label  = v.findViewById<TextView>(R.id.tv_event_label)
        val time   = v.findViewById<TextView>(R.id.tv_event_time)
        val detail = v.findViewById<TextView>(R.id.tv_event_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_event, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ev = items[pos]
        h.label.text = ev.label
        h.time.text  = ev.timeStr.takeLast(12) // HH:mm:ss.SSS
        val det = ev.detail
        if (det.isNotEmpty()) {
            h.detail.text = det
            h.detail.visibility = View.VISIBLE
        } else {
            h.detail.visibility = View.GONE
        }
        val colorHex = when (ev) {
            is GameEvent.HandshakeOut,
            is GameEvent.HandshakeIn  -> "#58A6FF"
            is GameEvent.LoginOut     -> "#F0883E"
            is GameEvent.LoginIn      -> "#3FB950"
            is GameEvent.BattleCommand -> "#D29922"
            else                       -> "#8B949E"
        }
        h.label.setTextColor(android.graphics.Color.parseColor(colorHex))
    }
}
