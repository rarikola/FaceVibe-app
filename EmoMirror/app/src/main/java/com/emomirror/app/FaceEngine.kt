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
            map["dbgFlesh"] = m?.fleshShare ?: 0f
            map["dbgTeeth"] = m?.teethShare ?: 0f
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
        val fleshShare: Float, val teethShare: Float, val count: Int,
        val probe: FloatArray, val aspect: Float, val openRatio: Float
    )

    /**
     * Reads the mouth opening straight from the camera picture, because the face tracker cannot
     * see teeth or a tongue at all.
     *
     * Only the area inside the inner-lip outline is read, so the lips are never counted. Every point
     * is judged by how much green it has next to its red:
     *   - teeth  sit near 0.9  (white, cream and yellow all do)
     *   - tongue sits near 0.6 (so do lips, which is why they are kept outside the outline)
     *   - the gap is simply dark
     * The gap between those two is wide and does not move with the light, since the camera's own
     * white balance already takes the colour of the room out.
     */
    private fun mouthLook(l: List<NormalizedLandmark>, input: MPImage): MouthLook? {
        if (l.size < 468) return null
        val bmp = BitmapExtractor.extract(input)
        val w = bmp.width; val h = bmp.height
        val aspect = w / h.toFloat()
        val empty = MouthLook(0f, 0f, 0f, 0f, 0f, 0, FloatArray(0), aspect, 0f)

        // A shut mouth is a thin line: reading it would only ever sample lip.
        val mouthW = l[308].x() - l[78].x()
        val openRatio = if (mouthW > 0.001f) (l[14].y() - l[13].y()) / mouthW else 0f
        if (openRatio < 0.13f) return MouthLook(0f, 0f, 0f, 0f, 0f, 0, FloatArray(0), aspect, openRatio)

        // MediaPipe's inner-lip ring, clockwise
        val ring = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
        val xs = FloatArray(ring.size); val ys = FloatArray(ring.size)
        for (i in ring.indices) { xs[i] = l[ring[i]].x(); ys[i] = l[ring[i]].y() }
        var cx = 0f; var cy = 0f
        for (i in ring.indices) { cx += xs[i]; cy += ys[i] }
        cx /= ring.size; cy /= ring.size
        // pull the outline in so the lip edge itself is never sampled; the opening is wide and
        // short, so the top and bottom need pulling in harder than the corners
        for (i in ring.indices) { xs[i] = cx + (xs[i] - cx) * 0.84f; ys[i] = cy + (ys[i] - cy) * 0.72f }
        var x0 = 1f; var x1 = 0f; var y0 = 1f; var y1 = 0f
        for (i in ring.indices) {
            if (xs[i] < x0) x0 = xs[i]
            if (xs[i] > x1) x1 = xs[i]
            if (ys[i] < y0) y0 = ys[i]
            if (ys[i] > y1) y1 = ys[i]
        }
        if (x1 - x0 < 0.008f || y1 - y0 < 0.003f) return MouthLook(0f, 0f, 0f, 0f, 0f, 0, FloatArray(0), aspect, openRatio)

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

        // first pass: collect the points inside the opening and find the brightest of them
        val steps = 20
        val px = FloatArray(steps * steps); val py = FloatArray(steps * steps); val col = IntArray(steps * steps)
        var n = 0
        var maxV = 0
        for (i in 0 until steps) for (j in 0 until steps) {
            val x = x0 + (x1 - x0) * (i + 0.5f) / steps
            val y = y0 + (y1 - y0) * (j + 0.5f) / steps
            if (!inside(x, y)) continue
            val c = pixel(x, y)
            val v = maxOf((c shr 16) and 255, maxOf((c shr 8) and 255, c and 255))
            if (v > maxV) maxV = v
            px[n] = x; py[n] = y; col[n] = c; n++
        }
        if (n < 10) return MouthLook(0f, 0f, 0f, 0f, 0f, n, FloatArray(0), aspect, openRatio)

        // second pass: sort each point into teeth, tongue or gap
        val probe = FloatArray(n * 3)
        var teeth = 0; var flesh = 0; var judged = 0
        var upHit = 0; var upN = 0; var lowHit = 0; var lowN = 0
        val darkV = maxV * 0.42f
        val midY = (y0 + y1) / 2f
        for (k in 0 until n) {
            val c = col[k]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val v = maxOf(r, maxOf(g, b))
            val gr = if (r < 10) 1f else g / r.toFloat()
            val kind = when {
                minOf(r, g, b) > 236 -> 3      // clipped to white: carries no colour, so it says nothing
                v < darkV -> 0                 // the gap and the throat
                gr > 0.80f -> 1                // teeth, however yellow
                gr < 0.78f -> 2                // tongue
                else -> 0
            }
            if (kind != 3) {
                judged++
                if (kind == 1) teeth++
                if (kind == 2) flesh++
                if (py[k] < midY) { upN++; if (kind == 1) upHit++ } else { lowN++; if (kind == 1) lowHit++ }
            }
            probe[k * 3] = px[k]; probe[k * 3 + 1] = py[k]; probe[k * 3 + 2] = kind.toFloat()
        }
        if (judged < 8) return MouthLook(0f, 0f, 0f, 0f, 0f, judged, probe, aspect, openRatio)

        fun sc(hit: Int, tot: Int) = if (tot < 4) 0f else ((hit / tot.toFloat() - 0.12f) / 0.28f).coerceIn(0f, 1f)
        val fleshShare = flesh / judged.toFloat()
        val teethShare = teeth / judged.toFloat()
        // a tongue fills the opening; a sliver of inner lip does not
        val tongue = ((fleshShare - 0.50f) / 0.25f).coerceIn(0f, 1f)
        return MouthLook(sc(upHit, upN), sc(lowHit, lowN), tongue, fleshShare, teethShare, judged,
            probe, aspect, openRatio)
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
