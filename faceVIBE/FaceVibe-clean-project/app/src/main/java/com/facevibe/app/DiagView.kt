package com.facevibe.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Translucent diagnostic panel: one row per signal (label, bar, number). */
class DiagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Row(val label: String, val bar: Float, val text: String)

    private var rows: List<Row> = emptyList()
    private val d = resources.displayMetrics.density
    private val rowH = 14f * d
    private val pad = 6f * d
    private val labelW = 70f * d
    private val valW = 40f * d

    private val bg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val barBg = Paint().apply { color = Color.argb(70, 255, 255, 255) }
    private val barFg = Paint().apply { color = Color.argb(200, 90, 220, 140) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }

    fun update(newRows: List<Row>) {
        val sizeChanged = newRows.size != rows.size
        rows = newRows
        if (sizeChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = if (rows.isEmpty()) 0 else (rows.size * rowH + 2 * pad).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthSpec),
            resolveSize(h, heightSpec)
        )
    }

    override fun onDraw(c: Canvas) {
        if (rows.isEmpty()) return
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        var y = pad
        val barX = pad + labelW
        val barW = width - barX - valW - pad
        for (r in rows) {
            c.drawText(r.label, pad, y + rowH * 0.78f, txt)
            c.drawRect(barX, y + 3f * d, barX + barW, y + rowH - 3f * d, barBg)
            c.drawRect(barX, y + 3f * d, barX + barW * r.bar.coerceIn(0f, 1f), y + rowH - 3f * d, barFg)
            c.drawText(r.text, width - valW - pad + 4f * d, y + rowH * 0.78f, txt)
            y += rowH
        }
    }
}
