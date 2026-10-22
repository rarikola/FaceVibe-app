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
            map["dbgFlesh"] = m?.fleshRowShare ?: 0f
            map["dbgBand"] = m?.teethBand ?: 0f
            map["dbgGr"] = m?.grMid ?: 0f
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
        val fleshRowShare: Float, val teethBand: Float, val count: Int,
        val probe: FloatArray, val aspect: Float, val openRatio: Float, val grMid: Float = 0f
    )

    /**
     * Reads the mouth opening straight from the camera picture, because the face tracker cannot
     * see teeth or a tongue at all.
     *
     * Only the area inside the inner-lip outline is read, so the lips are never counted. Every point
     * is judged by how much green it has next to its red, and by how colourful it is. Measured off
     * real camera frames:
     *   - teeth  green/red 0.80..0.88, barely colourful (about 0.22)
     *   - tongue green/red 0.60..0.66, colourful (about 0.37)
     *   - lips   green/red 0.50..0.60, very colourful (and kept outside the outline anyway)
     *   - the gap is simply dark
     * The line between teeth and tongue therefore sits at 0.73, and does not move with the light,
     * because the camera's own white balance already takes the colour of the room out.
     *
     * The colours are then read as shapes, row by row, because where a thing sits matters as much
     * as its colour: teeth are an unbroken block of rows against a lip, while a tongue fills the
     * whole opening and an open mouth without one has a dark hole down its middle.
     */
    /** Where the line between teeth and tongue sits. The short set-up moves it to fit this person. */
    @Volatile var grCut = 0.70f

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

        // Sample a square grid over the opening, keeping where each point sat, so the shapes
        // can be read and not just the colours.
        val steps = 20
        val cls = IntArray(steps * steps) { -1 }        // -1 outside, 0 dark, 1 teeth, 2 tongue, 3 unknown
        val gx = FloatArray(steps * steps); val gy = FloatArray(steps * steps)
        var maxV = 0
        val raw = IntArray(steps * steps)
        var n = 0
        for (j in 0 until steps) for (i in 0 until steps) {
            val x = x0 + (x1 - x0) * (i + 0.5f) / steps
            val y = y0 + (y1 - y0) * (j + 0.5f) / steps
            if (!inside(x, y)) continue
            val k = j * steps + i
            val c = pixel(x, y)
            raw[k] = c; gx[k] = x; gy[k] = y; cls[k] = 3
            val v = maxOf((c shr 16) and 255, maxOf((c shr 8) and 255, c and 255))
            if (v > maxV) maxV = v
            n++
        }
        if (n < 20) return MouthLook(0f, 0f, 0f, 0f, 0f, n, FloatArray(0), aspect, openRatio)

        val darkV = maxOf(maxV * 0.45f, 45f)
        val grs = FloatArray(cls.size); var grN = 0
        for (k in cls.indices) {
            if (cls[k] < 0) continue
            val c = raw[k]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val v = maxOf(r, maxOf(g, b))
            val gr = if (r < 10) 1f else g / r.toFloat()
            val sat = if (v == 0) 0f else (v - minOf(r, minOf(g, b))) / v.toFloat()
            // One line, no gap: everything that is not dark is either teeth or tongue. A gap here
            // means that in some lighting every point lands in it and the whole reading goes blank.
            cls[k] = when {
                minOf(r, minOf(g, b)) > 240 -> 3  // clipped to white: no colour left, says nothing
                v < darkV -> 0                    // the gap and the throat
                gr - 0.25f * sat >= grCut -> 1    // teeth
                else -> 2                         // tongue
            }
            if (cls[k] != 3) grs[grN++] = gr - 0.25f * sat
        }

        // Row by row. Teeth form one unbroken block of rows; a tongue makes nearly every row
        // flesh; an open mouth with no tongue has a dark hole down the middle, so only its
        // edge rows are flesh. (Measured on real frames: teeth band 16 rows for a smile and
        // none otherwise; rows that are mostly flesh 100% for a tongue against 40% for an "O".)
        var firstRow = -1; var lastRow = -1
        var fleshRows = 0; var validRows = 0
        val teethFrac = FloatArray(steps)
        for (j in 0 until steps) {
            var judged = 0; var teeth = 0; var flesh = 0
            for (i in 0 until steps) {
                val c = cls[j * steps + i]
                if (c < 0 || c == 3) continue
                judged++
                if (c == 1) teeth++ else if (c == 2) flesh++
            }
            if (judged < 4) { teethFrac[j] = -1f; continue }
            if (firstRow < 0) firstRow = j
            lastRow = j
            validRows++
            teethFrac[j] = teeth / judged.toFloat()
            if (flesh / judged.toFloat() >= 0.60f) fleshRows++
        }
        if (validRows < 6) return MouthLook(0f, 0f, 0f, 0f, 0f, n, FloatArray(0), aspect, openRatio)

        val midRow = (firstRow + lastRow) / 2
        var upper = 0f; var lower = 0f; var bestBand = 0
        var runStart = -1; var runSum = 0f; var runLen = 0
        fun closeRun(endRow: Int) {
            if (runLen >= 2) {
                if (runLen > bestBand) bestBand = runLen
                val cover = runSum / runLen
                val score = (runLen / 4f).coerceAtMost(1f) * ((cover - 0.45f) / 0.25f).coerceIn(0f, 1f)
                if (runStart <= midRow) upper = maxOf(upper, score)
                if (endRow >= midRow) lower = maxOf(lower, score)
            }
            runStart = -1; runSum = 0f; runLen = 0
        }
        for (j in 0 until steps) {
            val f = teethFrac[j]
            if (f < 0f) continue
            if (f >= 0.50f) {
                if (runStart < 0) runStart = j
                runSum += f; runLen++
            } else closeRun(j - 1)
        }
        closeRun(lastRow)

        // the middle value, used by the short set-up that learns this person's own mouth
        var grMid = 0f
        if (grN > 0) { java.util.Arrays.sort(grs, 0, grN); grMid = grs[grN / 2] }

        val fleshRowShare = fleshRows / validRows.toFloat()
        val tongue = ((fleshRowShare - 0.55f) / 0.25f).coerceIn(0f, 1f)

        val probe = FloatArray(n * 3)
        var p = 0
        for (k in cls.indices) {
            if (cls[k] < 0) continue
            probe[p] = gx[k]; probe[p + 1] = gy[k]; probe[p + 2] = cls[k].toFloat(); p += 3
        }
        return MouthLook(upper, lower, tongue, fleshRowShare, bestBand / 20f, n, probe, aspect, openRatio, grMid)
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
