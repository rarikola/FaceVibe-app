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
 * emoji card (upper right), joke card (upper left), LIVE status pill (bottom left).
 * Tap the picture = show/hide diagnostics, long-press = recalibrate.
 * Nothing is recorded or uploaded.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var diag: DiagView
    private lateinit var emoji: TextView
    private lateinit var jokeCard: View
    private lateinit var jokeSetup: TextView
    private lateinit var jokePunch: TextView
    private lateinit var jokeHint: TextView
    private lateinit var analysisExecutor: ExecutorService
    private var engine: FaceEngine? = null
    private val emotions = EmotionEngine()
    private val handler = Handler(Looper.getMainLooper())

    private var frames = 0
    private var fps = 0
    private var lastFpsTime = 0L

    private var jokeIndex = 0
    private var revealed = false

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
        jokeCard = findViewById(R.id.jokeCard)
        jokeSetup = findViewById(R.id.jokeSetup)
        jokePunch = findViewById(R.id.jokePunch)
        jokeHint = findViewById(R.id.jokeHint)

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
        jokeCard.setOnClickListener { revealPunchline() }
        applySetup()

        analysisExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        (statusDot.background as? GradientDrawable)?.setColor(color)
    }

    // ---- joke card: setup -> tap -> punchline -> (after a few seconds) next setup ----

    private fun applySetup() {
        revealed = false
        jokeSetup.text = JOKES[jokeIndex].first
        jokePunch.visibility = View.GONE
        jokeHint.visibility = View.VISIBLE
    }

    private fun revealPunchline() {
        if (revealed) return
        revealed = true
        jokePunch.text = JOKES[jokeIndex].second
        jokePunch.visibility = View.VISIBLE
        jokeHint.visibility = View.GONE
        handler.postDelayed({
            jokeIndex = (jokeIndex + 1) % JOKES.size
            jokeCard.animate().alpha(0f).setDuration(250).withEndAction {
                applySetup()
                jokeCard.animate().alpha(1f).setDuration(250).start()
            }.start()
        }, 8000)
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
            setStatus("Hold a neutral face… ${(st.calibrationProgress * 100).toInt()}%", AMBER)
        } else {
            emoji.text = st.current.emoji
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

        private val JOKES = listOf(
            "Why can't you trust an atom?" to "Because they make up everything!",
            "What do you call a fake noodle?" to "An impasta!",
            "Why did the scarecrow win an award?" to "He was outstanding in his field!",
            "What do you call cheese that isn't yours?" to "Nacho cheese!",
            "Why don't skeletons fight each other?" to "They don't have the guts!",
            "What's orange and sounds like a parrot?" to "A carrot!",
            "Why did the math book look sad?" to "It had too many problems!",
            "How does a penguin build its house?" to "Igloos it together!",
            "What did the ocean say to the beach?" to "Nothing, it just waved!",
            "Why did the bicycle fall over?" to "It was two tired!"
        )

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
