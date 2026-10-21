package com.emomirror.app

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
import kotlin.math.min

/**
 * A drawn face that copies the live face. Every number from the face tracker moves one part of it.
 * "Left"/"Right" are the person's own sides, drawn like a mirror (person's left = screen left).
 * Small tracker values are boosted so soft expressions (sad mouth, wink, frown) read clearly.
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
            val a = if (k.startsWith("eyeBlink")) 0.75f else 0.55f
            v[k] = old + a * (target - old)
        }
        // the tracker can report a head angle as 178 instead of -2 (same pose, turned half a circle);
        // fold every angle into -90..90 so the face never flips upside down
        val ty = fold(pose?.getOrNull(0) ?: 0f)
        val tp = fold(pose?.getOrNull(1) ?: 0f)
        val tr = fold(pose?.getOrNull(2) ?: 0f)
        yaw += 0.4f * (ty - yaw); pitch += 0.4f * (tp - pitch); roll += 0.4f * (tr - roll)
        if (!frozen) invalidate()
    }

    var frozen = false

    private fun fold(a: Float): Float {
        var x = a
        while (x > 90f) x -= 180f
        while (x < -90f) x += 180f
        return x
    }

    /** The current picture of the face, with the app name in the corner. */
    fun snapshot(): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); textSize = width * 0.04f }
        c.drawText("EmoMirror", width * 0.04f, height - width * 0.04f, p)
        return bmp
    }

    private fun g(k: String) = v[k] ?: 0f
    /** value times gain, kept in 0..1 */
    private fun b(k: String, gain: Float) = (g(k) * gain).coerceIn(0f, 1f)
    private fun b2(l: String, r: String, gain: Float) = ((g(l) + g(r)) / 2f * gain).coerceIn(0f, 1f)
    private fun lerp(a: Int, c: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(c) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(c) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(c) - Color.blue(a)) * u).toInt()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#1F2A44"), Color.parseColor("#0E1220"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val r = min(w * 0.38f, h * 0.38f)
        val baseCy = h * 0.5f
        val cx = w / 2f + (yaw / 45f).coerceIn(-1f, 1f) * r * 0.20f
        val cy = baseCy + (pitch / 45f).coerceIn(-1f, 1f) * r * 0.15f
        c.save()
        c.rotate(roll, w / 2f, baseCy)

        // anger turns the face red, like the angry emoji
        val smile = b2("mouthSmileLeft", "mouthSmileRight", 1.5f)
        val avgDown = (g("browDownLeft") + g("browDownRight")) / 2f
        val avgSneer = (g("noseSneerLeft") + g("noseSneerRight")) / 2f
        val avgPress = (g("mouthPressLeft") + g("mouthPressRight")) / 2f
        val anger = avgDown + avgSneer * 0.6f + avgPress * 0.4f - smile * 0.5f
        val t = ((anger - 0.40f) / 0.35f).coerceIn(0f, 1f)

        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
            intArrayOf(lerp(Color.parseColor("#FFE066"), Color.parseColor("#FF8A5B"), t),
                lerp(Color.parseColor("#FFC93C"), Color.parseColor("#F0502D"), t),
                lerp(Color.parseColor("#F2A516"), Color.parseColor("#C0281A"), t)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        line.strokeWidth = r * 0.035f
        line.color = lerp(Color.parseColor("#C98A0B"), Color.parseColor("#8E1A10"), t)
        c.drawCircle(cx, cy, r, line)
        line.color = Color.parseColor("#4A2A00")

        val ex = r * 0.38f
        val ey = cy - r * 0.15f
        eye(c, cx - ex, ey, r, g("eyeBlinkLeft"), b("eyeWideLeft", 1.3f), b("eyeSquintLeft", 1.5f), b("browDownLeft", 1.7f),
            g("eyeLookInLeft") - g("eyeLookOutLeft"), g("eyeLookUpLeft") - g("eyeLookDownLeft"))
        eye(c, cx + ex, ey, r, g("eyeBlinkRight"), b("eyeWideRight", 1.3f), b("eyeSquintRight", 1.5f), b("browDownRight", 1.7f),
            g("eyeLookOutRight") - g("eyeLookInRight"), g("eyeLookUpRight") - g("eyeLookDownRight"))

        val by = cy - r * 0.50f
        val inner = b("browInnerUp", 1.6f)
        brow(c, cx - ex, by, r, -1, b("browOuterUpLeft", 1.5f), inner, b("browDownLeft", 1.7f))
        brow(c, cx + ex, by, r, 1, b("browOuterUpRight", 1.5f), inner, b("browDownRight", 1.7f))

        if (smile > 0.35f) {
            fill.color = Color.argb((smile * 70).toInt().coerceAtMost(90), 255, 90, 90)
            c.drawCircle(cx - r * 0.58f, cy + r * 0.12f, r * 0.14f, fill)
            c.drawCircle(cx + r * 0.58f, cy + r * 0.12f, r * 0.14f, fill)
        }

        mouth(c, cx, cy + r * 0.45f, r)
        c.restore()
    }

    private fun eye(c: Canvas, x: Float, y: Float, r: Float, blinkRaw: Float, wide: Float, squint: Float, browDown: Float, gx: Float, gy: Float) {
        // a wink only reaches ~0.5-0.8 on the tracker, so stretch it: 0.2 = open, 0.7 = shut
        val lid = ((blinkRaw - 0.2f) / 0.5f).coerceIn(0f, 1f)
        val open = ((1f - lid) * (1f + wide * 0.30f - squint * 0.40f) * (1f - browDown * 0.30f)).coerceIn(0f, 1.35f)
        val rx = r * 0.15f
        val ry = r * 0.19f * open
        if (open < 0.14f) {
            line.strokeWidth = r * 0.05f
            val p = Path()
            p.moveTo(x - rx, y); p.quadTo(x, y + r * 0.07f, x + rx, y)
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
        val a = r * 0.24f
        // sad: inner end up, outer end droops. angry: inner end pulled down hard, outer end a little up
        val outerY = y - outerUp * a + down * a * 0.25f + innerUp * a * 0.30f
        val innerY = y - innerUp * a + down * a * 1.25f
        val half = r * 0.17f
        val outerX = x + side * half
        val innerX = x - side * half
        line.strokeWidth = r * 0.07f
        val p = Path()
        p.moveTo(outerX, outerY)
        p.quadTo(x, min(outerY, innerY) - r * 0.02f, innerX, innerY)
        c.drawPath(p, line)
    }

    private fun mouth(c: Canvas, cx: Float, y: Float, r: Float) {
        val jaw = b("jawOpen", 1.2f)
        val sL = b("mouthSmileLeft", 1.5f); val sR = b("mouthSmileRight", 1.5f)
        val fL = b("mouthFrownLeft", 2.6f); val fR = b("mouthFrownRight", 2.6f)
        val pucker = b("mouthPucker", 1.5f); val funnel = b("mouthFunnel", 1.5f)
        val stretch = b2("mouthStretchLeft", "mouthStretchRight", 1.4f)
        val upper = b2("mouthUpperUpLeft", "mouthUpperUpRight", 1.8f)
        val press = b2("mouthPressLeft", "mouthPressRight", 2f)
        val shift = (g("mouthRight") - g("mouthLeft")) * r * 0.14f
        val halfW = r * 0.34f * (1f + stretch * 0.25f + (sL + sR) * 0.12f - pucker * 0.55f - funnel * 0.35f)
        val x0 = cx + shift - halfW
        val x1 = cx + shift + halfW
        val y0 = y - sL * r * 0.22f + fL * r * 0.24f
        val y1 = y - sR * r * 0.22f + fR * r * 0.24f
        val openH = jaw * r * 0.62f + funnel * r * 0.10f + upper * r * 0.10f * (1f - press)
        val midTop = y + ((sL + sR) / 2f) * r * 0.10f - ((fL + fR) / 2f) * r * 0.14f - pucker * r * 0.03f
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
            if (openH > r * 0.07f) {
                c.save(); c.clipPath(path)
                fill.color = Color.WHITE
                c.drawRect(x0, min(y0, y1) - r * 0.2f, x1, midTop + min(openH * 0.35f, r * 0.14f), fill)
                c.restore()
            }
            line.strokeWidth = r * 0.045f
            c.drawPath(path, line)
        } else {
            line.strokeWidth = r * (0.05f + press * 0.02f)
            val p = Path()
            p.moveTo(x0, y0)
            p.quadTo(xm, ctrlTop + openH, x1, y1)
            c.drawPath(p, line)
        }
    }
}
