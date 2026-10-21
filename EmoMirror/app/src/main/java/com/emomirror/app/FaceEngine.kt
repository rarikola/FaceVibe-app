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
            map["tongueOutImg"] = if (lmk.isNotEmpty()) tongueScore(lmk[0], input, map["jawOpen"] ?: 0f) else 0f

            var pose: FloatArray? = null
            val mats = result.facialTransformationMatrixes()
            if (mats.isPresent && mats.get().isNotEmpty()) pose = headPose(mats.get()[0])
            onFrame(FaceFrame(true, map, pose))
        } catch (t: Throwable) {
            onError("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 0..1: how much of the area just below the lower lip is redder than the chin skin.
     * Only counts when the mouth is open. A pink tongue sticking out shows up as extra red there.
     */
    private fun tongueScore(l: List<NormalizedLandmark>, input: MPImage, jaw: Float): Float {
        if (jaw < 0.12f || l.size < 300) return 0f
        val bmp = BitmapExtractor.extract(input)
        val w = bmp.width; val h = bmp.height
        val xl = l[61].x(); val xr = l[291].x()
        val y17 = l[17].y(); val y152 = l[152].y()
        if (xr - xl <= 0.01f || y152 - y17 <= 0.01f) return 0f
        fun red(c: Int): Float = (((c shr 16) and 255) - ((c shr 8) and 255)) / (((c shr 16) and 255) + 1f)
        fun bright(c: Int) = (c shr 16) and 255
        val xa = xl + 0.25f * (xr - xl); val xb = xl + 0.75f * (xr - xl)
        val dy = y152 - y17
        fun grid(f0: Float, f1: Float): List<Int> {
            val out = ArrayList<Int>()
            for (i in 0 until 8) for (j in 0 until 6) {
                val x = ((xa + (xb - xa) * i / 7f) * w).toInt().coerceIn(0, w - 1)
                val y = ((y17 + dy * (f0 + (f1 - f0) * j / 5f)) * h).toInt().coerceIn(0, h - 1)
                out.add(bmp.getPixel(x, y))
            }
            return out
        }
        val ref = grid(0.72f, 0.95f)
        val refMean = ref.map { red(it) }.average().toFloat()
        val t = grid(0.05f, 0.60f)
        val hits = t.count { red(it) > refMean + 0.10f && bright(it) > 90 }
        val frac = hits / t.size.toFloat()
        return ((frac - 0.2f) / 0.4f).coerceIn(0f, 1f)
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
