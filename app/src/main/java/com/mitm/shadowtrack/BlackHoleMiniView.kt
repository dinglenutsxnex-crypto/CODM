package com.mitm.shadowtrack

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated black-hole button shown when the overlay is minimised.
 *
 * Layers (bottom → top):
 *  1. Rotating outer glow ellipse (blue)
 *  2. Black event-horizon circle
 *  3. Orange photon-ring arc (upper half of a flattened oval, clipped)
 *  4. Accretion disk rear (top half, dim, behind the void)
 *  5. Void circle re-drawn to occlude the rear disk
 *  6. Accretion disk front (bottom half, bright, in front of void)
 *  7. Optional count text (dev-mode only; null = hidden)
 */
class BlackHoleMiniView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Text to show in the centre (null = user mode, nothing drawn). */
    var countText: String? = null
        set(v) { field = v; invalidate() }

    // ── Animation state ────────────────────────────────────────────────────
    private var glowAngle  = 0f   // outer ring rotation  (6 s full turn)
    private var diskAlpha  = 1f   // accretion disk pulse (2 s cycle, 0.5–1.0)

    private val rotAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration          = 6_000
        repeatCount       = ValueAnimator.INFINITE
        repeatMode        = ValueAnimator.RESTART
        interpolator      = LinearInterpolator()
        addUpdateListener { glowAngle = animatedValue as Float; invalidate() }
    }
    private val pulseAnim = ValueAnimator.ofFloat(0.5f, 1f, 0.5f).apply {
        duration          = 2_000
        repeatCount       = ValueAnimator.INFINITE
        repeatMode        = ValueAnimator.RESTART
        interpolator      = LinearInterpolator()
        addUpdateListener { diskAlpha = animatedValue as Float }
    }

    override fun onAttachedToWindow()    { super.onAttachedToWindow();  rotAnim.start(); pulseAnim.start() }
    override fun onDetachedFromWindow()  { super.onDetachedFromWindow(); rotAnim.cancel(); pulseAnim.cancel() }

    // ── Paints ─────────────────────────────────────────────────────────────
    private val voidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#08090F")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.parseColor("#774499EE")
    }
    private val photonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.parseColor("#FFCC8800")
    }
    private val diskFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.parseColor("#FFEEF8FF")
    }
    private val diskRearPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.parseColor("#66AACFFF")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        textAlign   = Paint.Align.CENTER
        typeface    = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    // ── Resize ─────────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = minOf(w, h) / 2f
        glowPaint.strokeWidth      = r * 0.22f
        photonPaint.strokeWidth    = r * 0.09f
        diskFrontPaint.strokeWidth = r * 0.09f
        diskRearPaint.strokeWidth  = r * 0.06f
        textPaint.textSize         = r * 0.46f
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // needed for shadow/BlurMaskFilter
        glowPaint.maskFilter = BlurMaskFilter(r * 0.25f, BlurMaskFilter.Blur.NORMAL)
        diskFrontPaint.maskFilter = BlurMaskFilter(r * 0.08f, BlurMaskFilter.Blur.NORMAL)
    }

    // ── Draw ───────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy)
        val vr = r * 0.62f          // void radius

        // ── 1. Outer rotating glow ring ────────────────────────────────────
        canvas.save()
        canvas.rotate(glowAngle, cx, cy)
        val ga = r * 1.10f; val gb = r * 0.32f
        glowPaint.alpha = (160 + (80 * diskAlpha).toInt()).coerceIn(0, 255)
        canvas.drawOval(cx - ga, cy - gb, cx + ga, cy + gb, glowPaint)
        canvas.restore()

        // ── 2. Black void circle ───────────────────────────────────────────
        canvas.drawCircle(cx, cy, vr, voidPaint)

        // ── 3. Orange photon ring (arc above centre — top half only) ───────
        val pa = vr * 0.94f; val pb = vr * 0.52f
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), cy)        // top half only
        canvas.drawOval(cx - pa, cy - pb * 2f, cx + pa, cy, photonPaint)
        canvas.restore()

        // ── 4. Rear accretion disk (top half, behind void) ─────────────────
        val da = r * 1.20f; val db = r * 0.17f
        diskRearPaint.alpha = (diskAlpha * 180).toInt()
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), cy)        // top half only
        canvas.drawOval(cx - da, cy - db, cx + da, cy + db, diskRearPaint)
        canvas.restore()

        // ── 5. Void again to cover rear disk ───────────────────────────────
        canvas.drawCircle(cx, cy, vr, voidPaint)

        // ── 6. Front accretion disk (bottom half, in front of void) ─────────
        diskFrontPaint.alpha = (diskAlpha * 255).toInt()
        canvas.save()
        canvas.clipRect(0f, cy, width.toFloat(), height.toFloat())   // bottom half only
        canvas.drawOval(cx - da, cy - db, cx + da, cy + db, diskFrontPaint)
        canvas.restore()

        // ── 7. Count text (dev mode only) ─────────────────────────────────
        val txt = countText ?: return
        canvas.drawText(txt, cx, cy + textPaint.textSize * 0.38f, textPaint)
    }
}
