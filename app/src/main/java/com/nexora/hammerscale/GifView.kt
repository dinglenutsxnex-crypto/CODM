package com.nexora.hammerscale

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Renders an animated GIF from a raw resource with a luma-keyed transparency:
 *   alpha = R + G + B (clamped 0-255)
 * Pure black pixels → fully transparent.
 * Bright cyan/white pixels → fully opaque.
 * Dark-edge pixels → semi-transparent (natural soft glow edge).
 *
 * Uses the deprecated but fully functional Movie API — no extra dependencies.
 */
@Suppress("DEPRECATION")
class GifView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var movieStart = 0L

    // Luma → alpha: A' = R + G + B.
    // Black (0,0,0) → A=0 (transparent). Bright cyan (0,200,200) → A=400→255 (opaque).
    private val lumaKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,   // R' = R
            0f, 1f, 0f, 0f, 0f,   // G' = G
            0f, 0f, 1f, 0f, 0f,   // B' = B
            1f, 1f, 1f, 0f, 0f    // A' = R+G+B  (clamped to 255 by hardware)
        )))
    }

    private val clipPath = Path()

    init {
        // Software layer required for ColorMatrixColorFilter to affect alpha output
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setGifResource(resId: Int) {
        movie = Movie.decodeStream(context.resources.openRawResource(resId))
        movieStart = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val m = movie ?: return
        val now = SystemClock.uptimeMillis()
        if (movieStart == 0L) movieStart = now

        val duration = m.duration().let { if (it > 0) it else 1000 }
        m.setTime(((now - movieStart) % duration).toInt())

        // Scale to fill the circular view, centred
        val mw = m.width().toFloat()
        val mh = m.height().toFloat()
        val scale = maxOf(width / mw, height / mh)
        val dx = (width  - mw * scale) / 2f
        val dy = (height - mh * scale) / 2f

        canvas.save()
        canvas.clipPath(clipPath)       // circular crop
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f, lumaKeyPaint)
        canvas.restore()

        postInvalidateOnAnimation()     // drive continuous animation
    }
}
