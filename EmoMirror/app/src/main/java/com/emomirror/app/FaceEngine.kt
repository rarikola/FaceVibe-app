package com.emomirror.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.asin
import kotlin.math.atan2

/** One analysed frame. pose = [yaw, pitch, roll] in degrees, or null. */
/**
 * One analysed frame. pose = [yaw, pitch, roll] in degrees, or null.
 * probe = the points sampled inside the mouth, as x, y, kind triples (kind: 0 dark, 1 teeth, 2 red),
 * with x and y as fractions of the analysed picture. srcAspect = that picture's width / height.
 */
class FaceFrame(
    val hasFace: Boolean,
    val blend: Map<String, Float>,
    val pose: FloatArray?,
    val probe: FloatArray? = null,
    val srcAspect: Float = 0f
)

/**
 * On-device face analysis: CameraX frames -> MediaPipe Face Landmarker (LIVE_STREAM).
 * Tries GPU first, falls back to CPU. Nothing is stored or sent anywhere.
 */
class FaceEngine(
    context: Context,
    private val onFrame: (FaceFrame) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var landmarker: FaceLandmarker? = null

    var backend: String = "none"
        private set
    var initError: String = ""
        private set

    init {
        landmarker = create(context, Delegate.GPU)
        if (landmarker != null) {
            backend = "GPU"
        } else {
            landmarker = create(context, Delegate.CPU)
            if (landmarker != null) backend = "CPU"
        }
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker? {
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .setDelegate(delegate)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener(this::handleResult)
                .setErrorListener { e -> onError(e.message ?: "MediaPipe error") }
                .build()
            FaceLandmarker.createFromOptions(context, options)
        } catch (t: Throwable) {
            initError = "${delegate.name}: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    override fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null) {
            image.close()
            return
        }
        try {
            val raw: Bitmap = image.toBitmap()
            val m = Matrix()
            m.postRotate(image.imageInfo.rotationDegrees.toFloat())
            m.postScale(-1f, 1f) // mirror so left/right match what the user sees on screen
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            lm.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            onError("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun handleResult(result: FaceLandmarkerResult, input: MPImage) {
        try {
            val bs = result.faceBlendshapes()
            if (!bs.isPresent || bs.get().isEmpty()) {
                onFrame(FaceFrame(false, emptyMap(), null))
                return
            }
            val map = HashMap<String, Float>()
            for (c in bs.get()[0]) map[c.categoryName()] = c.score()
            // the tracker cannot see teeth or a tongue at all, so read them from the picture itself
            val lmk = result.faceLandmarks()
            val m = if (lmk.isNotEmpty()) mouthLook(lmk[0], input) else null
            map["teethUp"] = m?.upper ?: 0f
            map["teethLow"] = m?.lower ?: 0f
            map["tongueOutImg"] = m?.tongue ?: 0f
            map["dbgRed"] = m?.redShare ?: 0f
            map["dbgWhite"] = m?.whiteShare ?: 0f
            map["dbgN"] = (m?.count ?: 0).toFloat()
            map["dbgOpen"] = m?.openRatio ?: 0f

            var pose: FloatArray? = null
            val mats = result.facialTransformationMatrixes()
            if (mats.isPresent && mats.get().isNotEmpty()) pose = headPose(mats.get()[0])
            onFrame(FaceFrame(true, map, pose, m?.probe, m?.aspect ?: 0f))
        } catch (t: Throwable) {
            onError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    class MouthLook(
        val upper: Float, val lower: Float, val tongue: Float,
        val redShare: Float, val whiteShare: Float, val count: Int,
        val probe: FloatArray, val aspect: Float, val openRatio: Float
    )

    /**
     * Reads the mouth opening straight from the camera picture.
     *
     * Everything is judged against the person's own cheek skin in the same light, so it works for
     * any skin tone, any teeth colour and any lighting:
     *   - teeth  = much less colourful than skin, and not dark  (cream and yellow count as white)
     *   - tongue = more colourful and redder than skin
     *   - gap    = dark
     * Only points inside the inner-lip outline are read, so the lips themselves are never counted.
     */
    private fun mouthLook(l: List<NormalizedLandmark>, input: MPImage): MouthLook? {
        if (l.size < 468) return null
        val bmp = BitmapExtractor.extract(input)
        val w = bmp.width; val h = bmp.height
        val aspect = w / h.toFloat()
        // MediaPipe's inner-lip ring, clockwise
        val ring = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
        val xs = FloatArray(ring.size); val ys = FloatArray(ring.size)
        for (i in ring.indices) { xs[i] = l[ring[i]].x(); ys[i] = l[ring[i]].y() }
        // how far the lips are actually apart, next to how wide the mouth is.
        // A shut mouth is a thin line: reading it would only ever sample lip.
        val mouthW = l[308].x() - l[78].x()
        val lipGap = l[14].y() - l[13].y()
        val openRatio = if (mouthW > 0.001f) lipGap / mouthW else 0f
        if (openRatio < 0.14f) return MouthLook(0f, 0f, 0f, 0f, 0f, 0, FloatArray(0), aspect, openRatio)

        var cx = 0f; var cy = 0f
        for (i in ring.indices) { cx += xs[i]; cy += ys[i] }
        cx /= ring.size; cy /= ring.size
        // pull the outline in a little so the lip edge itself is never sampled
        // the opening is far shorter than it is wide, so the top and bottom edges need pulling in harder
        for (i in ring.indices) { xs[i] = cx + (xs[i] - cx) * 0.82f; ys[i] = cy + (ys[i] - cy) * 0.60f }
        var x0 = 1f; var x1 = 0f; var y0 = 1f; var y1 = 0f
        for (i in ring.indices) {
            if (xs[i] < x0) x0 = xs[i]; if (xs[i] > x1) x1 = xs[i]
            if (ys[i] < y0) y0 = ys[i]; if (ys[i] > y1) y1 = ys[i]
        }
        if (x1 - x0 < 0.01f || y1 - y0 < 0.004f) return null

        fun inside(px: Float, py: Float): Boolean {
            var c = false
            var j = ring.size - 1
            for (i in ring.indices) {
                if ((ys[i] > py) != (ys[j] > py) &&
                    px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i] + 1e-6f) + xs[i]) c = !c
                j = i
            }
            return c
        }
        fun pixel(px: Float, py: Float) =
            bmp.getPixel((px * w).toInt().coerceIn(0, w - 1), (py * h).toInt().coerceIn(0, h - 1))
        // saturation and brightness, 0..1
        fun sat(c: Int): Float {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        }
        fun value(c: Int): Float = maxOf((c shr 16) and 255, maxOf((c shr 8) and 255, c and 255)) / 255f

        // the person's own skin, in this light: both cheeks
        var skinSat = 0f; var skinVal = 0f; var sn = 0
        for (id in intArrayOf(50, 101, 117, 280, 330, 346)) {
            val c = pixel(l[id].x(), l[id].y())
            skinSat += sat(c); skinVal += value(c); sn++
        }
        skinSat /= sn; skinVal /= sn
        val whiteSat = maxOf(skinSat * 0.60f, 0.08f)   // teeth: clearly less colourful than skin
        val redSat = maxOf(skinSat * 1.15f, 0.30f)     // tongue: more colourful than skin
        val darkVal = skinVal * 0.42f

        val probe = ArrayList<Float>()
        var upHit = 0; var upN = 0
        var lowHit = 0; var lowN = 0
        var red = 0; var n = 0
        val steps = 14
        for (i in 0 until steps) for (j in 0 until steps) {
            val px = x0 + (x1 - x0) * (i + 0.5f) / steps
            val py = y0 + (y1 - y0) * (j + 0.5f) / steps
            if (!inside(px, py)) continue
            val c = pixel(px, py)
            val s = sat(c); val v = value(c)
            val kind = when {
                v < darkVal -> 0
                s < whiteSat && v > skinVal * 0.55f -> 1
                s > redSat -> 2
                else -> 0
            }
            n++
            if (kind == 2) red++
            if (py < cy) { upN++; if (kind == 1) upHit++ } else { lowN++; if (kind == 1) lowHit++ }
            probe.add(px); probe.add(py); probe.add(kind.toFloat())
        }
        if (n < 14) return MouthLook(0f, 0f, 0f, 0f, 0f, n, probe.toFloatArray(), aspect, openRatio)
        fun sc(hit: Int, tot: Int) = if (tot < 4) 0f else ((hit / tot.toFloat() - 0.12f) / 0.30f).coerceIn(0f, 1f)
        val redShare = red / n.toFloat()
        val whiteShare = (upHit + lowHit) / n.toFloat()
        // a tongue fills much of the opening with red; a thin red rim is just lip left over
        val tongue = ((redShare - 0.35f) / 0.30f).coerceIn(0f, 1f)
        return MouthLook(sc(upHit, upN), sc(lowHit, lowN), tongue, redShare, whiteShare, n,
            probe.toFloatArray(), aspect, openRatio)
    }

    /** 4x4 column-major transform -> yaw/pitch/roll in degrees (signs verified on-device in M2). */
    private fun headPose(m: FloatArray): FloatArray {
        fun r(row: Int, col: Int) = m[col * 4 + row].toDouble()
        val yaw = Math.toDegrees(atan2(r(0, 2), r(2, 2)))
        val pitch = Math.toDegrees(asin((-r(1, 2)).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(atan2(r(1, 0), r(1, 1)))
        return floatArrayOf(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
    }

    fun close() {
        try { landmarker?.close() } catch (_: Throwable) {}
        landmarker = null
    }
}
