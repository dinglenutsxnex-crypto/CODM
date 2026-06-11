package com.mitm.shadowtrack

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GlassCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 10, 10, 20)
        maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 15, 15, 30)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 63, 81, 181)
        strokeWidth = 4f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - strokePaint.strokeWidth
        canvas.drawCircle(cx, cy, r, blurPaint)
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
    }
}
