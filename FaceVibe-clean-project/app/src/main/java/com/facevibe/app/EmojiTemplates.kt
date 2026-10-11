package com.facevibe.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** One emoji with its two labels and its face template (feature -> target, weight). */
class EmojiTemplate(
    val emoji: String,
    val label1: String,
    val label2: String,
    val targets: FloatArray,   // per feature
    val weights: FloatArray    // per feature (square-rooted, ready to use)
)

/**
 * 30 emoji faces described by hand: each is a pattern over ~22 face features.
 * The live (calibrated) face is compared to every pattern by cosine similarity; weak movement
 * counts as neutral. Templates live in assets/emoji_templates.json so they are easy to tune.
 */
class EmojiTemplates(context: Context) {

    val templates: List<EmojiTemplate>
    private val neutralWeights: FloatArray

    init {
        val root = org.json.JSONArray(context.assets.open("emoji_templates.json").bufferedReader().use { it.readText() })
        val list = ArrayList<EmojiTemplate>()
        for (i in 0 until root.length()) {
            val o: JSONObject = root.getJSONObject(i)
            val t = o.getJSONObject("t")
            val tg = FloatArray(FEATURES.size)
            val w = FloatArray(FEATURES.size) { sqrt(DEFAULT_WEIGHT) }
            for ((idx, f) in FEATURES.withIndex()) {
                if (t.has(f)) {
                    val pair = t.getJSONArray(f)
                    tg[idx] = pair.getDouble(0).toFloat()
                    w[idx] = sqrt(pair.getDouble(1)).toFloat()
                }
            }
            list.add(EmojiTemplate(o.getString("emoji"), o.getString("label1"), o.getString("label2"), tg, w))
        }
        templates = list
        neutralWeights = FloatArray(FEATURES.size) { sqrt(DEFAULT_WEIGHT) }
    }

    /** Turn the (calibrated, smoothed) blendshape map into the feature vector. */
    fun features(b: Map<String, Float>): FloatArray {
        fun g(k: String) = b[k] ?: 0f
        fun a(x: String, y: String) = (g(x) + g(y)) / 2f
        return floatArrayOf(
            a("mouthSmileLeft", "mouthSmileRight"),                                   // smile
            abs(g("mouthSmileLeft") - g("mouthSmileRight")),                          // smileAsym
            a("mouthFrownLeft", "mouthFrownRight"),                                   // frown
            g("browInnerUp"),                                                         // browInUp
            a("browOuterUpLeft", "browOuterUpRight"),                                 // browOutUp
            a("browDownLeft", "browDownRight"),                                       // browDown
            maxOf(abs(g("browOuterUpLeft") - g("browOuterUpRight")),
                abs(g("browDownLeft") - g("browDownRight"))),                         // browAsym
            a("eyeWideLeft", "eyeWideRight"),                                         // wide
            a("eyeSquintLeft", "eyeSquintRight"),                                     // squint
            a("eyeBlinkLeft", "eyeBlinkRight"),                                       // blink
            abs(g("eyeBlinkLeft") - g("eyeBlinkRight")),                              // blinkAsym
            g("jawOpen"),                                                             // jaw
            a("mouthPressLeft", "mouthPressRight"),                                   // press
            a("mouthStretchLeft", "mouthStretchRight"),                               // stretch
            g("mouthPucker"),                                                         // pucker
            g("mouthFunnel"),                                                         // funnel
            a("noseSneerLeft", "noseSneerRight"),                                     // sneer
            a("mouthUpperUpLeft", "mouthUpperUpRight"),                               // upLip
            a("mouthLowerDownLeft", "mouthLowerDownRight"),                           // lowDown
            a("cheekSquintLeft", "cheekSquintRight"),                                 // cheekSq
            a("eyeLookUpLeft", "eyeLookUpRight"),                                     // lookUp
            g("tongueOut")                                                            // tongue
        )
    }

    /** True when the face is hardly moving at all. */
    fun isNeutral(x: FloatArray): Boolean {
        var s = 0f
        for (i in x.indices) {
            val v = x[i] * neutralWeights[i]
            s += v * v
        }
        return sqrt(s) < NEUTRAL_ENERGY
    }

    /** Cosine similarity of the live features to every template (index 0 = neutral, always 0). */
    fun similarities(x: FloatArray): FloatArray = FloatArray(templates.size) { i ->
        if (i == 0) 0f else {
            val t = templates[i]
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (k in x.indices) {
                val a = x[k] * t.weights[k]
                val b = t.targets[k] * t.weights[k]
                dot += a * b
                na += a * a
                nb += b * b
            }
            dot / (sqrt(na) * sqrt(nb) + 1e-6f)
        }
    }

    companion object {
        val FEATURES = listOf(
            "smile", "smileAsym", "frown", "browInUp", "browOutUp", "browDown", "browAsym", "wide",
            "squint", "blink", "blinkAsym", "jaw", "press", "stretch", "pucker", "funnel", "sneer",
            "upLip", "lowDown", "cheekSq", "lookUp", "tongue"
        )
        const val DEFAULT_WEIGHT = 0.4f
        const val NEUTRAL_ENERGY = 0.1f
    }
}
