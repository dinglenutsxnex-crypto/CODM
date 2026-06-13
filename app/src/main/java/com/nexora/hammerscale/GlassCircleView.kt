package com.nexora.hammerscale

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GlassCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val clipPath = Path()

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 10, 10, 25)
        maskFilter = BlurMaskFilter(55f, BlurMaskFilter.Blur.NORMAL)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 15, 15, 35)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 63, 81, 181)
        strokeWidth = 4f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = (minOf(w, h) / 2f) - strokePaint.strokeWidth
        clipPath.reset()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - strokePaint.strokeWidth

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawCircle(cx, cy, r, blurPaint)
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, r, strokePaint)
    }
}
