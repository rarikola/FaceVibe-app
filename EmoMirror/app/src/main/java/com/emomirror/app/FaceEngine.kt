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
class FaceFrame(val hasFace: Boolean, val blend: Map<String, Float>, val pose: FloatArray?)

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
            // the tracker's own tongueOut value is weak, so also look for tongue-coloured pixels below the lip
            val lmk = result.faceLandmarks()
            val tg = if (lmk.isNotEmpty()) tongueScore(lmk[0], input, map["jawOpen"] ?: 0f) else floatArrayOf(0f, 0f, 0f)
            map["tongueOutImg"] = tg[0]
            map["dbgInside"] = tg[0]
            map["dbgBelow"] = tg[1]
            map["dbgBright"] = tg[2]

            var pose: FloatArray? = null
            val mats = result.facialTransformationMatrixes()
            if (mats.isPresent && mats.get().isNotEmpty()) pose = headPose(mats.get()[0])
            onFrame(FaceFrame(true, map, pose))
        } catch (t: Throwable) {
            onError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Looks for a tongue inside the mouth opening: open mouths are dark inside, a tongue is bright pink,
     * teeth are white. Returns [score 0..1, pink share below the lip, mean brightness inside] (for the debug line).
     */
    private fun tongueScore(l: List<NormalizedLandmark>, input: MPImage, jaw: Float): FloatArray {
        val none = floatArrayOf(0f, 0f, 0f)
        if (jaw < 0.15f || l.size < 320) return none
        val bmp = BitmapExtractor.extract(input)
        val w = bmp.width; val h = bmp.height
        val xl = l[78].x(); val xr = l[308].x()
        val y13 = l[13].y(); val y14 = l[14].y(); val y17 = l[17].y(); val y152 = l[152].y()
        if (xr - xl <= 0.01f || y14 - y13 <= 0.003f) return none
        fun px(x: Float, y: Float) = bmp.getPixel((x * w).toInt().coerceIn(0, w - 1), (y * h).toInt().coerceIn(0, h - 1))
        fun pink(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r > 140 && (r - g) / (r + 1f) in 0.20f..0.65f && b > g * 1.03f
        }
        val xa = xl + 0.30f * (xr - xl); val xb = xl + 0.70f * (xr - xl)
        val gap = y14 - y13
        var hits = 0; var n = 0; var bright = 0f
        for (i in 0 until 8) for (j in 0 until 6) {
            val x = xa + (xb - xa) * i / 7f
            val y = y13 + gap * 0.10f + gap * 1.05f * j / 5f
            val c = px(x, y)
            if (pink(c)) hits++
            bright += ((c shr 16) and 255)
            n++
        }
        var below = 0; var m = 0
        if (y152 - y17 > 0.01f) {
            for (i in 0 until 8) for (j in 0 until 4) {
                val x = xa + (xb - xa) * i / 7f
                val y = y17 + (y152 - y17) * (0.05f + 0.35f * j / 3f)
                if (pink(px(x, y))) below++
                m++
            }
        }
        val frac = hits / n.toFloat()
        return floatArrayOf(((frac - 0.25f) / 0.35f).coerceIn(0f, 1f), below / maxOf(m, 1).toFloat(), bright / n)
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
