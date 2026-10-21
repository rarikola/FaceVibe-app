package com.facevibe.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A drawn face that copies the live face: every number from the face tracker moves one part of it.
 * "Left"/"Right" are the person's own sides, drawn like a mirror (person's left = screen left).
 */
class MirrorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val v = HashMap<String, Float>()
    private var yaw = 0f
    private var pitch = 0f
    private var roll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#4A2A00")
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B1A00") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bg = Paint()

    fun update(blend: Map<String, Float>, pose: FloatArray?) {
        val keys = if (blend.isEmpty()) v.keys.toList() else blend.keys.toList()
        for (k in keys) {
            val target = blend[k] ?: 0f
            val old = v[k] ?: 0f
            val a = if (k.startsWith("eyeBlink")) 0.7f else 0.5f
            v[k] = old + a * (target - old)
        }
        val ty = pose?.getOrNull(0) ?: 0f
        val tp = pose?.getOrNull(1) ?: 0f
        val tr = pose?.getOrNull(2) ?: 0f
        yaw += 0.4f * (ty - yaw); pitch += 0.4f * (tp - pitch); roll += 0.4f * (tr - roll)
        invalidate()
    }

    private fun g(k: String) = v[k] ?: 0f
    private fun pair(a: String, b: String) = (g(a) + g(b)) / 2f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#1F2A44"), Color.parseColor("#0E1220"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val r = min(w, h) * 0.40f
        val cx = w / 2f + (yaw / 45f).coerceIn(-1f, 1f) * r * 0.20f
        val cy = h * 0.46f + (pitch / 45f).coerceIn(-1f, 1f) * r * 0.15f
        c.save()
        c.rotate(roll, w / 2f, h * 0.46f)

        // head
        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
            intArrayOf(Color.parseColor("#FFE066"), Color.parseColor("#FFC93C"), Color.parseColor("#F2A516")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        fill.alpha = 255   // a see-through paint left over from the blush must not leak into the head
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        line.strokeWidth = r * 0.035f
        line.color = Color.parseColor("#C98A0B")
        c.drawCircle(cx, cy, r, line)
        line.color = Color.parseColor("#4A2A00")

        // eyes: person's left eye on screen left
        val ex = r * 0.38f
        val ey = cy - r * 0.15f
        eye(c, cx - ex, ey, r, g("eyeBlinkLeft"), g("eyeWideLeft"), g("eyeSquintLeft"),
            g("eyeLookInLeft") - g("eyeLookOutLeft"), g("eyeLookUpLeft") - g("eyeLookDownLeft"))
        eye(c, cx + ex, ey, r, g("eyeBlinkRight"), g("eyeWideRight"), g("eyeSquintRight"),
            g("eyeLookOutRight") - g("eyeLookInRight"), g("eyeLookUpRight") - g("eyeLookDownRight"))

        // brows
        val by = cy - r * 0.50f
        brow(c, cx - ex, by, r, -1, g("browOuterUpLeft"), g("browInnerUp"), g("browDownLeft"))
        brow(c, cx + ex, by, r, 1, g("browOuterUpRight"), g("browInnerUp"), g("browDownRight"))

        // cheeks when smiling
        val smile = pair("mouthSmileLeft", "mouthSmileRight")
        if (smile > 0.25f) {
            fill.color = Color.argb((smile * 70).toInt().coerceAtMost(90), 255, 90, 90)
            c.drawCircle(cx - r * 0.58f, cy + r * 0.12f, r * 0.14f, fill)
            c.drawCircle(cx + r * 0.58f, cy + r * 0.12f, r * 0.14f, fill)
        }

        mouth(c, cx, cy + r * 0.45f, r)
        c.restore()
    }

    private fun eye(c: Canvas, x: Float, y: Float, r: Float, blink: Float, wide: Float, squint: Float, gx: Float, gy: Float) {
        val open = ((1f - blink) * (1f + wide * 0.30f - squint * 0.35f)).coerceIn(0f, 1.35f)
        val rx = r * 0.15f
        val ry = r * 0.19f * open
        if (open < 0.12f) {
            line.strokeWidth = r * 0.045f
            val p = Path()
            p.moveTo(x - rx, y); p.quadTo(x, y + r * 0.06f, x + rx, y)
            c.drawPath(p, line)
            return
        }
        val oval = RectF(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(oval, white)
        line.strokeWidth = r * 0.03f
        c.drawOval(oval, line)
        c.save()
        val clip = Path(); clip.addOval(oval, Path.Direction.CW); c.clipPath(clip)
        val px = x + gx.coerceIn(-1f, 1f) * rx * 0.45f
        val py = y - gy.coerceIn(-1f, 1f) * ry * 0.35f
        c.drawCircle(px, py, r * 0.085f, ink)
        c.drawCircle(px - r * 0.025f, py - r * 0.03f, r * 0.025f, white)
        c.restore()
    }

    private fun brow(c: Canvas, x: Float, y: Float, r: Float, side: Int, outerUp: Float, innerUp: Float, down: Float) {
        val a = r * 0.22f
        val outerY = y - outerUp * a + down * a * 0.35f
        val innerY = y - innerUp * a * 0.9f + down * a * 0.9f
        val half = r * 0.17f
        val outerX = x + side * half
        val innerX = x - side * half
        line.strokeWidth = r * 0.065f
        val p = Path()
        p.moveTo(outerX, outerY)
        p.quadTo(x, min(outerY, innerY) - r * 0.03f, innerX, innerY)
        c.drawPath(p, line)
    }

    private fun mouth(c: Canvas, cx: Float, y: Float, r: Float) {
        val jaw = g("jawOpen")
        val sL = g("mouthSmileLeft"); val sR = g("mouthSmileRight")
        val fL = g("mouthFrownLeft"); val fR = g("mouthFrownRight")
        val pucker = g("mouthPucker"); val funnel = g("mouthFunnel")
        val stretch = pair("mouthStretchLeft", "mouthStretchRight")
        val shift = (g("mouthRight") - g("mouthLeft")) * r * 0.12f
        val halfW = r * 0.34f * (1f + stretch * 0.25f + (sL + sR) * 0.12f - pucker * 0.55f - funnel * 0.35f)
        val x0 = cx + shift - halfW
        val x1 = cx + shift + halfW
        val y0 = y - sL * r * 0.20f + fL * r * 0.13f
        val y1 = y - sR * r * 0.20f + fR * r * 0.13f
        val openH = jaw * r * 0.62f + funnel * r * 0.10f
        val midTop = y + ((sL + sR) / 2f) * r * 0.10f - ((fL + fR) / 2f) * r * 0.12f - pucker * r * 0.03f
        val ctrlTop = 2f * midTop - (y0 + y1) / 2f
        val xm = cx + shift

        if (openH > r * 0.04f) {
            val path = Path()
            path.moveTo(x0, y0)
            path.quadTo(xm, ctrlTop, x1, y1)
            val ctrlBot = 2f * (midTop + openH) - (y0 + y1) / 2f
            path.quadTo(xm, ctrlBot + openH * 0.25f, x0, y0)
            path.close()
            fill.color = Color.parseColor("#7A1E1E")
            c.drawPath(path, fill)
            // teeth strip under the upper lip
            if (openH > r * 0.10f) {
                c.save(); c.clipPath(path)
                fill.color = Color.WHITE
                c.drawRect(x0, min(y0, y1) - r * 0.2f, x1, midTop + min(openH * 0.30f, r * 0.12f), fill)
                c.restore()
            }
            line.strokeWidth = r * 0.045f
            c.drawPath(path, line)
        } else {
            line.strokeWidth = r * 0.05f
            val p = Path()
            p.moveTo(x0, y0)
            p.quadTo(xm, ctrlTop + openH, x1, y1)
            c.drawPath(p, line)
        }
    }
}
