package com.facevibe.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
import kotlin.math.abs

/**
 * Milestone 3 (UI pass): mirrored full-screen front camera, on-device face analysis,
 * emoji card (upper right) with its two labels (upper left), LIVE status pill (bottom left).
 * Tap the picture = show/hide diagnostics, long-press = recalibrate.
 * Nothing is recorded or uploaded.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var diag: DiagView
    private lateinit var emoji: TextView
    private lateinit var emojiLabels: TextView
    private lateinit var analysisExecutor: ExecutorService
    private var engine: FaceEngine? = null
    private val emotions = EmotionEngine()
    private val handler = Handler(Looper.getMainLooper())

    // 30-emoji template matcher
    private var tpl: EmojiTemplates? = null
    private var curEmoji = 0
    private var pendEmoji = -1
    private var pendEmojiCount = 0

    private var frames = 0
    private var fps = 0
    private var lastFpsTime = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else setStatus("Camera permission is required", RED)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        diag = findViewById(R.id.diag)
        emoji = findViewById(R.id.emoji)
        emojiLabels = findViewById(R.id.emojiLabels)
        try {
            tpl = EmojiTemplates(applicationContext)
        } catch (t: Throwable) {
            emojiLabels.visibility = View.VISIBLE
            emojiLabels.text = "templates error: ${t.javaClass.simpleName}"
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GREY)
        }

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.setOnClickListener {
            diag.visibility = if (diag.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        previewView.setOnLongClickListener {
            emotions.recalibrate()
            true
        }
        analysisExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        (statusDot.background as? GradientDrawable)?.setColor(color)
    }

    // ---- camera + analysis ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    setStatus("No front camera found", RED)
                    return@addListener
                }

                val eng = FaceEngine(
                    applicationContext,
                    onFrame = { f -> runOnUiThread { showFrame(f) } },
                    onError = { msg -> runOnUiThread { setStatus("Error: $msg", RED) } }
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

                if (eng.backend == "none") setStatus("Face model failed: ${eng.initError}", RED)
                else setStatus("Looking for your face…", GREY)
            } catch (e: Exception) {
                setStatus("Camera error: ${e.javaClass.simpleName}: ${e.message}", RED)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showFrame(f: FaceFrame) {
        frames++
        val now = SystemClock.uptimeMillis()
        if (lastFpsTime == 0L) lastFpsTime = now
        if (now - lastFpsTime >= 1000) {
            fps = frames
            frames = 0
            lastFpsTime = now
        }
        val backend = engine?.backend ?: "?"

        if (!f.hasFace) {
            emotions.onNoFace()
            setStatus("No face", GREY)
            diag.update(emptyList())
            return
        }

        val st = emotions.update(f.blend)
        if (st.calibrating) {
            emoji.text = "🙂"
            emojiLabels.visibility = View.GONE
            setStatus("Hold a neutral face… ${(st.calibrationProgress * 100).toInt()}%", AMBER)
        } else {
            updateEmoji()
            setStatus("LIVE", GREEN)
        }

        val rows = ArrayList<DiagView.Row>()
        fun g(k: String) = f.blend[k] ?: 0f
        fun add(label: String, v: Float) = rows.add(DiagView.Row(label, v, "%.2f".format(v)))

        rows.add(DiagView.Row(backend, fps / 30f, "$fps fps"))
        if (!st.calibrating) {
            for ((e, sc) in st.scores) rows.add(DiagView.Row("» " + e.label.take(9), sc, "%.2f".format(sc)))
        }
        for ((label, key) in SIGNALS) add(label, g(key))
        add("gazeLeft", (g("eyeLookOutLeft") + g("eyeLookInRight")) / 2f)
        add("gazeRight", (g("eyeLookInLeft") + g("eyeLookOutRight")) / 2f)
        add("gazeUp", (g("eyeLookUpLeft") + g("eyeLookUpRight")) / 2f)
        add("gazeDown", (g("eyeLookDownLeft") + g("eyeLookDownRight")) / 2f)

        val p = f.pose
        if (p != null) {
            rows.add(DiagView.Row("yaw", abs(p[0]) / 45f, "%.0f°".format(p[0])))
            rows.add(DiagView.Row("pitch", abs(p[1]) / 45f, "%.0f°".format(p[1])))
            rows.add(DiagView.Row("roll", abs(p[2]) / 45f, "%.0f°".format(p[2])))
        }
        diag.update(rows)
    }

    /** 30-emoji matcher on the calibrated, smoothed face. */
    private fun updateEmoji() {
        val t = tpl ?: return
        val x = t.features(emotions.smoothed())
        val target: Int
        val sims = t.similarities(x)
        if (t.isNeutral(x)) {
            target = 0
        } else {
            var b = 1
            for (i in 1 until sims.size) if (sims[i] > sims[b]) b = i
            target = b
        }
        if (target != curEmoji) {
            val curSim = if (curEmoji == 0) 0.2f else sims[curEmoji]
            val decisive = target == 0 || sims[target] > curSim + 0.05f
            if (decisive) {
                if (target == pendEmoji) pendEmojiCount++ else { pendEmoji = target; pendEmojiCount = 1 }
                if (pendEmojiCount >= 6) {
                    curEmoji = target
                    pendEmoji = -1
                    pendEmojiCount = 0
                }
            } else {
                pendEmoji = -1
                pendEmojiCount = 0
            }
        } else {
            pendEmoji = -1
            pendEmojiCount = 0
        }
        val m = t.templates[curEmoji]
        emoji.text = m.emoji
        emojiLabels.visibility = View.VISIBLE
        emojiLabels.text = m.label1 + "\n" + m.label2
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        engine?.close()
        analysisExecutor.shutdown()
    }

    companion object {
        private val GREEN = Color.parseColor("#2ECC71")
        private val AMBER = Color.parseColor("#F5A623")
        private val RED = Color.parseColor("#E5484D")
        private val GREY = Color.parseColor("#9A9AA5")

        /** label to MediaPipe blendshape name */
        private val SIGNALS = listOf(
            "smileL" to "mouthSmileLeft",
            "smileR" to "mouthSmileRight",
            "cheekL" to "cheekSquintLeft",
            "cheekR" to "cheekSquintRight",
            "frownL" to "mouthFrownLeft",
            "frownR" to "mouthFrownRight",
            "browInUp" to "browInnerUp",
            "browOutL" to "browOuterUpLeft",
            "browOutR" to "browOuterUpRight",
            "browDnL" to "browDownLeft",
            "browDnR" to "browDownRight",
            "eyeWideL" to "eyeWideLeft",
            "eyeWideR" to "eyeWideRight",
            "squintL" to "eyeSquintLeft",
            "squintR" to "eyeSquintRight",
            "blinkL" to "eyeBlinkLeft",
            "blinkR" to "eyeBlinkRight",
            "jawOpen" to "jawOpen",
            "pressL" to "mouthPressLeft",
            "pressR" to "mouthPressRight",
            "stretchL" to "mouthStretchLeft",
            "stretchR" to "mouthStretchRight",
            "pucker" to "mouthPucker",
            "noseL" to "noseSneerLeft",
            "noseR" to "noseSneerRight",
            "upLipL" to "mouthUpperUpLeft",
            "upLipR" to "mouthUpperUpRight"
        )
    }
}
