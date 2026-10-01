package com.facevibe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
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

            var pose: FloatArray? = null
            val mats = result.facialTransformationMatrixes()
            if (mats.isPresent && mats.get().isNotEmpty()) pose = headPose(mats.get()[0])
            onFrame(FaceFrame(true, map, pose))
        } catch (t: Throwable) {
            onError("${t.javaClass.simpleName}: ${t.message}")
        }
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
