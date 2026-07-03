package com.nexora.hammerscale

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

// Renders an animated GIF from a raw resource with luma-keyed transparency:
// alpha = R + G + B (clamped 0-255), so black pixels vanish and bright pixels
// stay opaque, giving a soft glow edge. Uses the deprecated but functional
// Movie API to avoid pulling in an extra GIF library.
@Suppress("DEPRECATION")
class GifView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var movieStart = 0L

    private val lumaKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f, 0f
        )))
    }

    private val clipPath = Path()

    init {
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

        val mw = m.width().toFloat()
        val mh = m.height().toFloat()
        val scale = maxOf(width / mw, height / mh)
        val dx = (width  - mw * scale) / 2f
        val dy = (height - mh * scale) / 2f

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f, lumaKeyPaint)
        canvas.restore()

        postInvalidateOnAnimation()
    }
}
