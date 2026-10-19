package com.facevibe.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Recorded face samples (52 baseline-subtracted blendshape values each) and nearest-sample matching. */
class Prototypes(private val file: File) {
    var keys: List<String> = emptyList()
        private set
    val samples = ArrayList<Pair<Int, FloatArray>>()
    private var w = FloatArray(0)

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val k = o.getJSONArray("keys")
                keys = (0 until k.length()).map { k.getString(it) }
                val s = o.getJSONArray("samples")
                for (i in 0 until s.length()) {
                    val e = s.getJSONObject(i)
                    val v = e.getJSONArray("v")
                    samples.add(e.getInt("id") to FloatArray(v.length()) { v.getDouble(it).toFloat() })
                }
            }
        } catch (_: Throwable) {
            samples.clear()
        }
        reweight()
    }

    fun taughtIds(): Set<Int> = samples.map { it.first }.toSet()

    fun vector(m: Map<String, Float>): FloatArray? {
        if (m.isEmpty()) return null
        if (keys.isEmpty()) keys = m.keys.filter { it != "_neutral" }.sorted()
        return FloatArray(keys.size) { m[keys[it]] ?: 0f }
    }

    fun add(id: Int, v: FloatArray, replace: Boolean) {
        if (replace) samples.removeAll { it.first == id }
        samples.add(id to v)
        reweight()
        save()
    }

    fun clear() {
        samples.clear(); reweight(); save()
    }

    private fun reweight() {
        val n = keys.size
        w = FloatArray(n) { 1f }
        if (n == 0 || samples.size < 2) return
        val all = samples.map { it.second } + listOf(FloatArray(n))
        for (i in 0 until n) {
            val mean = all.sumOf { it[i].toDouble() } / all.size
            val sd = sqrt(all.sumOf { (it[i] - mean) * (it[i] - mean) } / all.size)
            w[i] = (1.0 / (sd + 0.05)).toFloat()
        }
    }

    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = (a[i] - b[i]) * w[i]; s += d * d }
        return sqrt(s / a.size)
    }

    /** Nearest taught expression; the neutral (all-zero) face counts as [neutralId]. */
    fun match(v: FloatArray, neutralId: Int): Int {
        var best = neutralId
        var bd = dist(v, FloatArray(v.size))
        for ((id, s) in samples) {
            val d = dist(v, s)
            if (d < bd) { bd = d; best = id }
        }
        return best
    }

    /** Mean sample per expression, used to find look-alike pairs. */
    fun classMeans(): Map<Int, FloatArray> =
        samples.groupBy({ it.first }, { it.second }).mapValues { (_, l) ->
            FloatArray(keys.size) { i -> l.sumOf { it[i].toDouble() }.toFloat() / l.size }
        }

    private fun save() {
        try {
            val o = JSONObject()
            o.put("keys", JSONArray(keys))
            val s = JSONArray()
            for ((id, v) in samples) {
                val e = JSONObject(); e.put("id", id)
                val a = JSONArray(); v.forEach { a.put(it.toDouble()) }
                e.put("v", a); s.put(e)
            }
            o.put("samples", s)
            file.writeText(o.toString())
        } catch (_: Throwable) {}
    }
}
