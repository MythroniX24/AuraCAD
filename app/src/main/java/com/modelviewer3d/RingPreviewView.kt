package com.modelviewer3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Top-view ring preview — a donut drawn to scale from the live inner/outer
 * diameters (mm). Pure Canvas, no GL cost, updates with an invalidate().
 */
class RingPreviewView(context: Context) : View(context) {

    var innerDiaMM: Float = 16.5f
        set(v) { field = v; invalidate() }
    var outerDiaMM: Float = 20.0f
        set(v) { field = v; invalidate() }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#16223A")
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#00D4FF")
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#101018")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D4FF")
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8080A0")
        textAlign = Paint.Align.CENTER
        textSize = 13f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val pad = 6f
        val maxR = min(width, height) / 2f - pad
        val od = max(outerDiaMM, 0.1f)
        val scale = maxR / od
        val outerR = od * scale
        val innerR = max(innerDiaMM, 0.1f).coerceAtMost(od) * scale

        val bounds = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, outerR, strokePaint)
        canvas.drawCircle(cx, cy, innerR, holePaint)

        if (innerDiaMM > 0f) {
            canvas.drawText("⌀ %.1f mm".format(innerDiaMM), cx, cy + 2f, textPaint)
            canvas.drawText(RingMath.usSizeLabel(innerDiaMM), cx, cy + 26f, subTextPaint)
        }
    }
}
