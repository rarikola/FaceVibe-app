package com.facevibe.app

import android.os.SystemClock
import kotlin.math.abs

/** Milestone 3: seven broad emotion families. */
enum class Emotion(val label: String, val emoji: String) {
    NEUTRAL("Neutral", "😐"),
    HAPPY("Happy", "😄"),
    PLAYFUL("Playful", "😉"),
    SURPRISED("Surprised", "😮"),
    SAD("Sad", "😢"),
    ANGRY("Angry", "😠"),
    DISGUSTED("Disgusted", "🤢")
}

class EmotionState(
    val current: Emotion,
    val scores: Map<Emotion, Float>,
    val calibrating: Boolean,
    val calibrationProgress: Float
)

/**
 * Deterministic rules on top of MediaPipe blendshapes:
 * 1) ~2 s neutral calibration -> personal baseline
 * 2) baseline subtraction + exponential smoothing
 * 3) per-family scores (0..1)
 * 4) persistence + hysteresis so the emoji does not flicker.
 * Call from a single thread (UI thread).
 */
class EmotionEngine {

    private var calibrating = true
    private var calStart = 0L
    private var calFrames = 0
    private val calSum = HashMap<String, Float>()
    private var baseline: Map<String, Float> = emptyMap()
    private val smooth = HashMap<String, Float>()

    private var current = Emotion.NEUTRAL
    private var pending: Emotion? = null
    private var pendingCount = 0

    fun recalibrate() {
        calibrating = true
        resetCalibrationAccumulators()
        smooth.clear()
        current = Emotion.NEUTRAL
        pending = null
        pendingCount = 0
    }

    /** If the face disappears mid-calibration, start the 2 s window over. */
    fun onNoFace() {
        if (calibrating) resetCalibrationAccumulators()
    }

    private fun resetCalibrationAccumulators() {
        calFrames = 0
        calStart = 0L
        calSum.clear()
    }

    fun update(blend: Map<String, Float>): EmotionState {
        val now = SystemClock.uptimeMillis()

        if (calibrating) {
            if (calFrames == 0) calStart = now
            for ((k, v) in blend) calSum[k] = (calSum[k] ?: 0f) + v
            calFrames++
            val elapsed = now - calStart
            if (elapsed >= CAL_MS && calFrames >= 15) {
                val frames = calFrames.toFloat()
                baseline = calSum.mapValues { it.value / frames }
                calibrating = false
                smooth.clear()
            }
            val progress = (elapsed.toFloat() / CAL_MS).coerceIn(0f, 1f)
            return EmotionState(Emotion.NEUTRAL, emptyMap(), calibrating, progress)
        }

        // baseline-subtracted, renormalised, smoothed
        for ((k, v) in blend) {
            val b = baseline[k] ?: 0f
            val d = ((v - b) / (1f - b).coerceAtLeast(0.3f)).coerceIn(0f, 1f)
            val prev = smooth[k] ?: d
            smooth[k] = prev + ALPHA * (d - prev)
        }

        fun s(k: String) = smooth[k] ?: 0f
        fun avg(a: String, b: String) = (s(a) + s(b)) / 2f
        fun n(x: Float, full: Float) = (x / full).coerceIn(0f, 1f)

        val smile = avg("mouthSmileLeft", "mouthSmileRight")
        val frown = avg("mouthFrownLeft", "mouthFrownRight")
        val browUp = (s("browInnerUp") + avg("browOuterUpLeft", "browOuterUpRight")) / 2f
        val browDown = avg("browDownLeft", "browDownRight")
        val wide = avg("eyeWideLeft", "eyeWideRight")
        val squint = avg("eyeSquintLeft", "eyeSquintRight")
        val press = avg("mouthPressLeft", "mouthPressRight")
        val sneer = avg("noseSneerLeft", "noseSneerRight")
        val upLip = avg("mouthUpperUpLeft", "mouthUpperUpRight")
        val jaw = s("jawOpen")
        val blinkL = s("eyeBlinkLeft")
        val blinkR = s("eyeBlinkRight")

        val happy = n(smile, 0.5f)
        val wink = if (maxOf(blinkL, blinkR) > 0.5f) n(abs(blinkL - blinkR) - 0.25f, 0.4f) else 0f
        val playful = (wink * 1.3f).coerceIn(0f, 1f)
        val surprised = (0.35f * n(jaw, 0.4f) + 0.35f * n(browUp, 0.5f) + 0.30f * n(wide, 0.4f)) * (1f - 0.5f * happy)
        val sad = (0.6f * n(frown, 0.4f) + 0.4f * n(s("browInnerUp"), 0.5f)) * (1f - happy)
        val angry = (0.6f * n(browDown, 0.5f) + 0.2f * n(squint, 0.5f) + 0.2f * n(press, 0.5f)) * (1f - happy)
        val disgusted = (0.6f * n(sneer, 0.4f) + 0.4f * n(upLip, 0.4f)) * (1f - 0.5f * happy)

        val scores = linkedMapOf(
            Emotion.HAPPY to happy,
            Emotion.PLAYFUL to playful,
            Emotion.SURPRISED to surprised,
            Emotion.SAD to sad,
            Emotion.ANGRY to angry,
            Emotion.DISGUSTED to disgusted
        )

        var best = Emotion.NEUTRAL
        var bestScore = 0f
        for ((e, sc) in scores) if (sc > bestScore) { best = e; bestScore = sc }
        val target = if (bestScore >= ENTER) best else Emotion.NEUTRAL

        if (target == current) {
            pending = null
            pendingCount = 0
        } else {
            val curScore = if (current == Emotion.NEUTRAL) 0f else (scores[current] ?: 0f)
            val decisive = target != Emotion.NEUTRAL && bestScore > curScore + 0.15f
            val currentFaded = current == Emotion.NEUTRAL || curScore < EXIT
            if (decisive || currentFaded) {
                if (pending == target) pendingCount++ else { pending = target; pendingCount = 1 }
                if (pendingCount >= PERSIST) {
                    current = target
                    pending = null
                    pendingCount = 0
                }
            } else {
                pending = null
                pendingCount = 0
            }
        }

        val shown = LinkedHashMap<Emotion, Float>()
        shown[Emotion.NEUTRAL] = (1f - bestScore).coerceIn(0f, 1f)
        shown.putAll(scores)
        return EmotionState(current, shown, false, 1f)
    }

    companion object {
        private const val CAL_MS = 2000L
        private const val ALPHA = 0.35f   // smoothing
        private const val ENTER = 0.45f   // score needed to leave neutral / switch
        private const val EXIT = 0.30f    // current family holds until it drops below this
        private const val PERSIST = 6     // frames (~250 ms at 24 fps)
    }
}
