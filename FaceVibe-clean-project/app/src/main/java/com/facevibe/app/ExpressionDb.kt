package com.facevibe.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.sqrt

/** One database hit: which stored image (id), its labels, and how far away it is. */
class Match(val id: Int, val labels: List<String>, val dist: Float)

/**
 * The expression database: one 51-number blendshape vector per hand-labelled image, tied to
 * that image's labels. No images are stored, only numbers and labels. Lookup = nearest vector.
 */
class ExpressionDb(context: Context) {

    val names: List<String>
    val mean: FloatArray
    val size: Int
    private val labelSets: List<List<String>>
    private val vecs: Array<FloatArray>
    private val mirrors: Array<FloatArray?>
    private val scale: FloatArray   // per-image neighbourhood size (hub correction)

    init {
        val text = context.assets.open("expression_db.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val nameArr = root.getJSONArray("blendshapes")
        val n = nameArr.length()
        names = List(n) { nameArr.getString(it) }

        val items = root.getJSONArray("items")
        size = items.length()
        val ls = ArrayList<List<String>>()
        val vs = ArrayList<FloatArray>()
        val ms = ArrayList<FloatArray?>()
        val sum = FloatArray(n)
        for (i in 0 until size) {
            val o = items.getJSONObject(i)
            val la = o.getJSONArray("labels")
            ls.add(List(la.length()) { la.getString(it) })
            val v = toFloats(o.getJSONArray("vec"), n)
            vs.add(v)
            for (j in 0 until n) sum[j] += v[j]
            ms.add(if (o.isNull("vec_mirror")) null else toFloats(o.getJSONArray("vec_mirror"), n))
        }
        labelSets = ls
        vecs = vs.toTypedArray()
        mirrors = ms.toTypedArray()
        mean = FloatArray(n) { if (size > 0) sum[it] / size else 0f }

        // Hub correction: how far is each image from its 5th nearest other image? Images in crowded
        // regions get a small scale so they stop winning for everything; every image still counts once.
        val sc = FloatArray(size)
        for (i in 0 until size) {
            val ds = FloatArray(size)
            for (j in 0 until size) {
                ds[j] = if (i == j) 9f else {
                    val m = mirrors[j]
                    sqrt(if (m != null) minOf(sq(vecs[i], vecs[j]), sq(vecs[i], m)) else sq(vecs[i], vecs[j]))
                }
            }
            ds.sort()
            sc[i] = sqrt(ds[minOf(4, size - 2).coerceAtLeast(0)].coerceAtLeast(0.05f))
        }
        scale = sc
    }

    private fun toFloats(a: org.json.JSONArray, n: Int) = FloatArray(n) { a.getDouble(it).toFloat() }

    /** Live blendshape map -> vector in the database's order. */
    fun toVector(blend: Map<String, Float>) = FloatArray(names.size) { blend[names[it]] ?: 0f }

    private fun sq(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return s
    }

    fun labelsOf(id: Int): List<String> = labelSets[id]

    /** Distance from the live vector to every stored image (mirrored copy tried too), hub-corrected. */
    fun adjusted(v: FloatArray): FloatArray = FloatArray(size) { i ->
        val m = mirrors[i]
        val a = sq(v, vecs[i])
        sqrt(if (m != null) minOf(a, sq(v, m)) else a) / scale[i]
    }

    /** The k nearest stored images (each also tried mirrored), closest first. */
    fun nearest(v: FloatArray, k: Int): List<Match> {
        val d = FloatArray(size) { i ->
            val m = mirrors[i]
            val a = sq(v, vecs[i])
            if (m != null) minOf(a, sq(v, m)) else a
        }
        return d.indices.sortedBy { d[it] }.take(k)
            .map { Match(it, labelSets[it], sqrt(d[it])) }
    }
}
