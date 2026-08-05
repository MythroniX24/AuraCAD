package com.modelviewer3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * Live top-view ring preview — draws the ring to scale with a center grid,
 * inner/outer circles and the computed US size in the middle.
 */
class RingPreviewView(context: Context) : View(context) {

    var innerDiaMM: Float = 0f
        set(value) { field = value; invalidate() }
    var outerDiaMM: Float = 0f
        set(value) { field = value; invalidate() }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1C2A3E")
        strokeWidth = 1f
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#2E405C")
        strokeWidth = 1f
    }
    private val innerPaint = Paint().apply {
        color = Color.parseColor("#4DD8FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val outerPaint = Paint().apply {
        color = Color.parseColor("#2E6B85")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#1A4DD8FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#F2F6FB")
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val subTextPaint = Paint().apply {
        color = Color.parseColor("#7A8BA3")
        textSize = 11f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f

        // Grid background
        val step = 40f
        var x = step
        while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += step }
        var y = step
        while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += step }
        canvas.drawLine(cx, 0f, cx, h, axisPaint)
        canvas.drawLine(0f, cy, w, cy, axisPaint)

        val hasData = innerDiaMM > 0f && outerDiaMM >= innerDiaMM
        if (!hasData) {
            textPaint.color = Color.parseColor("#5A6B85")
            canvas.drawText("Tap Detect to analyze the ring", cx, cy, textPaint)
            textPaint.color = Color.parseColor("#F2F6FB")
            return
        }

        val maxDia = max(outerDiaMM, 1f)
        val scale = (minOf(w, h) * 0.78f) / maxDia   // px per mm
        val innerR = innerDiaMM / 2f * scale
        val outerR = outerDiaMM / 2f * scale

        canvas.drawCircle(cx, cy, outerR, fillPaint)
        canvas.drawCircle(cx, cy, outerR, outerPaint)
        canvas.drawCircle(cx, cy, innerR, innerPaint)

        canvas.drawText(
            RingMath.usSizeLabel(innerDiaMM), cx, cy + textPaint.textSize * 0.35f, textPaint)
        canvas.drawText(
            "⌀ %.2f mm".format(innerDiaMM), cx, cy + textPaint.textSize * 1.7f, subTextPaint)
    }
}
