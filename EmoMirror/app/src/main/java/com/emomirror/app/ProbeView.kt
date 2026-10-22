package com.emomirror.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Debug overlay: shows every point the app read inside the mouth, and how it read it. */
class ProbeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var pts: FloatArray? = null
    private var aspect = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var on = false
        set(value) { field = value; invalidate() }

    fun update(points: FloatArray?, srcAspect: Float) {
        pts = points; aspect = srcAspect
        if (on) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = pts ?: return
        if (!on || aspect <= 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        // same framing as the preview (fill centre)
        val srcW = aspect; val srcH = 1f
        val scale = maxOf(w / srcW, h / srcH)
        val dw = srcW * scale; val dh = srcH * scale
        val ox = (w - dw) / 2f; val oy = (h - dh) / 2f
        var i = 0
        while (i + 2 < a.size) {
            val x = ox + a[i] * dw
            val y = oy + a[i + 1] * dh
            p.color = when (a[i + 2].toInt()) {
                1 -> Color.parseColor("#00E5FF")   // read as teeth
                2 -> Color.parseColor("#FF2D7A")   // read as tongue
                3 -> Color.parseColor("#FFD400")   // clipped to white, ignored
                else -> Color.parseColor("#80FFFFFF") // read as dark
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * resources.displayMetrics.density
            c.drawCircle(x, y, 2.5f * resources.displayMetrics.density, p)
            i += 3
        }
    }
}
