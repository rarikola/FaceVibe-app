package com.emomirror.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * EmoMirror: a drawn face in the middle copies your face live; the camera picture sits in a corner
 * (tap it to move it to the other corner). Everything runs on the phone; nothing is saved or sent.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var mirror: MirrorView
    private lateinit var box: FrameLayout
    private lateinit var status: TextView
    private lateinit var analysisExecutor: ExecutorService
    private var engine: FaceEngine? = null
    private var boxOnRight = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Camera permission is required"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        mirror = findViewById(R.id.mirror)
        box = findViewById(R.id.box)
        status = findViewById(R.id.status)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.clipToOutline = true
        previewView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 19f * resources.displayMetrics.density)
            }
        }
        box.setOnClickListener {
            boxOnRight = !boxOnRight
            (box.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.BOTTOM or (if (boxOnRight) Gravity.END else Gravity.START)
            box.requestLayout()
        }
        analysisExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    status.text = "No front camera found"
                    return@addListener
                }
                val eng = FaceEngine(
                    applicationContext,
                    onFrame = { f -> runOnUiThread { showFrame(f) } },
                    onError = { msg -> runOnUiThread { status.text = "Error: $msg" } }
                )
                engine = eng
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor, eng)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                if (eng.backend == "none") status.text = "Face model failed: ${eng.initError}"
                else status.text = "Looking for your face…"
            } catch (e: Exception) {
                status.text = "Camera error: ${e.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showFrame(f: FaceFrame) {
        mirror.update(if (f.hasFace) f.blend else emptyMap(), f.pose)
        if (f.hasFace) {
            if (status.visibility != View.GONE) status.visibility = View.GONE
        } else {
            status.text = "No face"
            status.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        analysisExecutor.shutdown()
    }
}
