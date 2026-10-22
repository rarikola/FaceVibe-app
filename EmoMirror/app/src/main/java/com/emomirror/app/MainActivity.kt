package com.emomirror.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * EmoMirror: camera picture on top, a drawn face below that copies your face live.
 * Drag the bar between them to change the split. Capture saves the drawn face to Pictures,
 * Share sends it to another app. Everything runs on the phone; nothing is sent anywhere by itself.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var mirror: MirrorView
    private lateinit var status: TextView
    private lateinit var root: View
    private lateinit var feed: View
    private lateinit var bottom: View
    private lateinit var analysisExecutor: ExecutorService
    private var engine: FaceEngine? = null
    private var debug = false
    private lateinit var probe: ProbeView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Camera permission is required"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        mirror = findViewById(R.id.mirror)
        status = findViewById(R.id.status)
        probe = findViewById(R.id.probe)
        root = findViewById(R.id.root)
        feed = findViewById(R.id.feed)
        bottom = findViewById(R.id.bottom)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        // stop the phone's automatic dark mode from inverting our colours
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.decorView.isForceDarkAllowed = false
            mirror.isForceDarkAllowed = false
            previewView.isForceDarkAllowed = false
        }

        val prefs = getSharedPreferences("em", MODE_PRIVATE)
        setSplit(prefs.getFloat("split", 0.5f))
        findViewById<View>(R.id.divider).setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val loc = IntArray(2); root.getLocationOnScreen(loc)
                val usable = (root.height - v.height).coerceAtLeast(1)
                val f = ((e.rawY - loc[1] - v.height / 2f) / usable).coerceIn(0.2f, 0.75f)
                setSplit(f)
                if (e.actionMasked == MotionEvent.ACTION_MOVE) prefs.edit().putFloat("split", f).apply()
            }
            true
        }
        findViewById<View>(R.id.appName).setOnClickListener {
            debug = !debug
            probe.on = debug
            status.visibility = if (debug) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.captureBtn).setOnClickListener { capture() }
        findViewById<View>(R.id.shareBtn).setOnClickListener { share() }

        analysisExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setSplit(f: Float) {
        (feed.layoutParams as LinearLayout.LayoutParams).weight = f * 100f
        (bottom.layoutParams as LinearLayout.LayoutParams).weight = (1f - f) * 100f
        root.requestLayout()
    }

    private fun capture() {
        val bmp = mirror.snapshot()
        mirror.frozen = true
        mirror.postDelayed({ mirror.frozen = false }, 700)
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EmoMirror_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EmoMirror")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) throw IllegalStateException("no uri")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "Saved to Pictures/EmoMirror", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not save: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        val bmp = mirror.snapshot()
        mirror.frozen = true
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "emomirror.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Share"))
        } catch (t: Throwable) {
            mirror.frozen = false
            Toast.makeText(this, "Could not share: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mirror.isInitialized) mirror.frozen = false
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
        probe.update(if (f.hasFace) f.probe else null, f.srcAspect)
        if (debug) {
            status.text = mirror.debugText()
            status.visibility = View.VISIBLE
        } else if (f.hasFace) {
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
