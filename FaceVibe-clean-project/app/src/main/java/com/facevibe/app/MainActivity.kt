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
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.app.AlertDialog
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
    private var featEma: FloatArray? = null
    private var curGroup = 0
    private var pendGroup = -1
    private var pendCount = 0
    private var lastSwitch = 0L
    private var shownEmoji = ""
    private var shownReaction = ""

    /** Least-shown emoji of the group (random tiebreak); its reactions rotate each time it is shown. */
    private fun pickMember(g: EmojiTemplate) {
        val prefs = getSharedPreferences("f2e", MODE_PRIVATE)
        val counts = g.members.map { prefs.getInt("n_" + it.emoji, 0) }
        val min = counts.min()
        val i = counts.indices.filter { counts[it] == min }.random()
        val mem = g.members[i]
        shownEmoji = mem.emoji
        shownReaction = mem.reactions[counts[i] % mem.reactions.size]
        prefs.edit().putInt("n_" + mem.emoji, counts[i] + 1).apply()
    }

    // ---- Teach-me ----
    private lateinit var exprs: Expressions
    private lateinit var protos: Prototypes
    private lateinit var emojiImg: ImageView
    private lateinit var teachPanel: View
    private lateinit var tImg: ImageView
    private lateinit var tChar: TextView
    private lateinit var tName: TextView
    private lateinit var tDesc: TextView
    private lateinit var tCount: TextView
    private lateinit var tStatus: TextView
    private lateinit var tProgress: ProgressBar
    private lateinit var bPause: Button
    private var teachOn = false
    private var tIdx = 0
    private var tPhase = 0            // 0 paused, 1 get ready, 2 recording
    private var tStart = 0L
    private var tAdd = false
    private var tAcc: FloatArray? = null
    private var tN = 0
    private var curId = -1
    private val votes = IntArray(VOTE_N) { -1 }
    private var voteI = 0
    private var neutralId = 35

    private fun showExpr(e: Expr, img: ImageView, ch: TextView) {
        val bmp = exprs.bitmap(e)
        if (bmp != null) { img.setImageBitmap(bmp); img.visibility = View.VISIBLE; ch.text = "" }
        else { img.visibility = View.GONE; ch.text = e.ch ?: "🙂" }
    }

    private fun switchTo(id: Int) {
        val e = exprs.byId(id) ?: return
        val prefs = getSharedPreferences("f2e", MODE_PRIVATE)
        val n = prefs.getInt("n_" + id, 0)
        prefs.edit().putInt("n_" + id, n + 1).apply()
        curId = id
        showExpr(e, emojiImg, emoji)
        emojiLabels.visibility = View.VISIBLE
        emojiLabels.text = e.reactions[n % e.reactions.size]
    }

    /** Nearest taught sample, majority vote over the last few frames: quick but steady. */
    private fun updateTaught() {
        val v = protos.vector(emotions.smoothed()) ?: return
        votes[voteI] = protos.match(v, neutralId)
        voteI = (voteI + 1) % VOTE_N
        val top = votes.filter { it >= 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return
        val now = SystemClock.uptimeMillis()
        if (top.key != curId && top.value >= VOTE_NEED && (curId < 0 || now - lastSwitch >= TAUGHT_DWELL_MS)) {
            switchTo(top.key)
            lastSwitch = now
        }
    }

    private fun openTeach() {
        teachOn = true
        teachPanel.visibility = View.VISIBLE
        val done = protos.taughtIds()
        tIdx = exprs.list.indexOfFirst { it.id !in done }.let { if (it < 0) 0 else it }
        tAdd = false
        startReady()
        showTeachExpr()
    }

    private fun startReady() {
        tPhase = 1; tStart = SystemClock.uptimeMillis(); tAcc = null; tN = 0
        bPause.text = "Pause"
        tProgress.progress = 0
    }

    private fun showTeachExpr() {
        val e = exprs.list[tIdx]
        showExpr(e, tImg, tChar)
        tName.text = e.name
        tDesc.text = e.desc
        tCount.text = "${tIdx + 1} / ${exprs.list.size}   ·   taught: ${protos.taughtIds().size}" + if (tAdd) "   ·   extra sample" else ""
    }

    private fun gotoExpr(i: Int) {
        if (i < 0) return
        if (i >= exprs.list.size) { finishTeach(); return }
        tIdx = i; tAdd = false
        showTeachExpr()
        startReady()
    }

    private fun finishTeach() {
        teachOn = false
        tPhase = 0
        teachPanel.visibility = View.GONE
        voteI = 0; votes.fill(-1); curId = -1
        val means = protos.classMeans()
        if (means.size < 2) return
        val ids = means.keys.toList()
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (a in ids.indices) for (b in a + 1 until ids.size)
            pairs.add(Triple(protos.dist(means[ids[a]]!!, means[ids[b]]!!), ids[a], ids[b]))
        pairs.sortBy { it.first }
        val msg = pairs.take(6).joinToString("\n") {
            "${exprs.byId(it.second)?.name}  ≈  ${exprs.byId(it.third)?.name}"
        }
        AlertDialog.Builder(this)
            .setTitle("Easily mixed up")
            .setMessage(msg + "\n\nTip: make these faces a bit more different from each other, then Teach again.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun teachFrame(f: FaceFrame, calibrating: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (tPhase == 0) { tStatus.text = "Paused"; return }
        if (!f.hasFace || calibrating) {
            tStart = now
            tAcc = null; tN = 0
            tProgress.progress = 0
            tStatus.text = if (calibrating) "Hold a neutral face…" else "I can't see your face"
            return
        }
        val e = exprs.list[tIdx]
        val el = now - tStart
        if (tPhase == 1) {
            val left = READY_MS - el
            tStatus.text = "Get ready… ${(left / 1000) + 1}"
            tProgress.progress = 0
            if (left <= 0) { tPhase = 2; tStart = now; tAcc = null; tN = 0 }
            return
        }
        tStatus.text = "Hold: ${e.name}"
        tProgress.progress = (el * 100 / REC_MS).toInt().coerceAtMost(100)
        if (el >= SETTLE_MS) {
            val v = protos.vector(emotions.smoothed())
            if (v != null) {
                val a = tAcc ?: FloatArray(v.size).also { tAcc = it }
                for (i in v.indices) a[i] += v[i]
                tN++
            }
        }
        if (el >= REC_MS) {
            val a = tAcc
            if (a != null && tN >= 10) {
                val n = tN
                protos.add(e.id, FloatArray(a.size) { a[it] / n }, !tAdd)
                gotoExpr(tIdx + 1)
            } else startReady()
        }
    }

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
        emojiImg = findViewById(R.id.emojiImg)
        teachPanel = findViewById(R.id.teach)
        tImg = findViewById(R.id.tImg); tChar = findViewById(R.id.tChar)
        tName = findViewById(R.id.tName); tDesc = findViewById(R.id.tDesc)
        tCount = findViewById(R.id.tCount); tStatus = findViewById(R.id.tStatus)
        tProgress = findViewById(R.id.tProgress); bPause = findViewById(R.id.bPause)
        exprs = Expressions(applicationContext)
        protos = Prototypes(java.io.File(filesDir, "prototypes.json"))
        neutralId = exprs.list.firstOrNull { it.name == "Poker face" }?.id ?: 35
        findViewById<View>(R.id.teachBtn).setOnClickListener { openTeach() }
        findViewById<View>(R.id.bBack).setOnClickListener { gotoExpr((tIdx - 1).coerceAtLeast(0)) }
        findViewById<View>(R.id.bSkip).setOnClickListener { gotoExpr(tIdx + 1) }
        findViewById<View>(R.id.bAdd).setOnClickListener { tAdd = true; showTeachExpr(); startReady() }
        bPause.setOnClickListener {
            if (tPhase == 0) { startReady() } else { tPhase = 0; bPause.text = "Start" }
        }
        findViewById<View>(R.id.bFinish).setOnClickListener { finishTeach() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (teachOn) finishTeach() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
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
            featEma = null
            if (teachOn) teachFrame(f, false)
            setStatus("No face", GREY)
            diag.update(emptyList())
            return
        }

        val st = emotions.update(f.blend)
        if (st.calibrating) {
            emoji.text = "🙂"
            emojiImg.visibility = View.GONE
            emojiLabels.visibility = View.GONE
            setStatus("Hold a neutral face… ${(st.calibrationProgress * 100).toInt()}%", AMBER)
        } else {
            if (teachOn) { /* paused matching while teaching */ }
            else if (protos.samples.isNotEmpty()) updateTaught()
            else updateEmoji()
            setStatus("LIVE", GREEN)
        }
        if (teachOn) teachFrame(f, st.calibrating)

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

    /** Emoji-group matcher on the calibrated, smoothed face. Switches slowly so it does not flicker. */
    private fun updateEmoji() {
        val t = tpl ?: return
        emojiImg.visibility = View.GONE
        val raw = t.features(emotions.smoothed())
        val prev = featEma
        val x: FloatArray
        if (prev == null) {
            x = raw
        } else {
            for (i in raw.indices) prev[i] += FEATURE_ALPHA * (raw[i] - prev[i])
            x = prev
        }
        featEma = x

        val sims = t.similarities(x)
        var target = 0
        // Two thresholds (hysteresis): easy to stay in a group, harder to fall back to neutral and back.
        val energy = t.energy(x)
        val moving = if (curGroup == 0) energy > NEUTRAL_EXIT else energy > NEUTRAL_ENTER
        if (moving) {
            target = 1
            for (i in 1 until sims.size) if (sims[i] > sims[target]) target = i
        }
        val now = SystemClock.uptimeMillis()
        if (target != curGroup) {
            val curSim = if (curGroup == 0) 0.2f else sims[curGroup]
            val decisive = target == 0 || sims[target] > curSim + SWITCH_MARGIN
            if (decisive) {
                if (target == pendGroup) pendCount++ else { pendGroup = target; pendCount = 1 }
                if (pendCount >= HOLD_FRAMES && now - lastSwitch >= MIN_DWELL_MS) {
                    curGroup = target
                    pickMember(t.templates[target])
                    lastSwitch = now
                    pendGroup = -1
                    pendCount = 0
                }
            } else {
                pendGroup = -1
                pendCount = 0
            }
        } else {
            pendGroup = -1
            pendCount = 0
        }
        val m = t.templates[curGroup]
        if (shownEmoji.isEmpty()) pickMember(m)
        emoji.text = shownEmoji
        emojiLabels.visibility = View.VISIBLE
        emojiLabels.text = shownReaction
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        engine?.close()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val VOTE_N = 8
        private const val VOTE_NEED = 5
        private const val TAUGHT_DWELL_MS = 500L
        private const val READY_MS = 2000L
        private const val SETTLE_MS = 500L
        private const val REC_MS = 3000L
        private const val FEATURE_ALPHA = 0.25f   // extra smoothing on the face pattern
        private const val HOLD_FRAMES = 8        // ~0.6 s of consistent evidence before switching
        private const val MIN_DWELL_MS = 700L     // never switch twice within this time
        private const val NEUTRAL_ENTER = 0.08f   // below this the face counts as neutral again
        private const val NEUTRAL_EXIT = 0.15f    // above this a neutral face starts counting as an expression
        private const val SWITCH_MARGIN = 0.06f   // new group must beat the current one by this much

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
