package com.facevibe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray

class Expr(
    val id: Int,
    val name: String,
    val desc: String,
    val key: String?,
    val ch: String?,
    val reactions: List<String>
)

/** The 37 drawn expressions (assets/expressions.json + assets/faces folder). */
class Expressions(private val ctx: Context) {
    var list: List<Expr> = emptyList()
        private set
    private val customFile = java.io.File(ctx.filesDir, "custom_expr.json")
    private val cache = HashMap<Int, Bitmap>()

    init {
        val arr = JSONArray(ctx.assets.open("expressions.json").bufferedReader().use { it.readText() })
        val base = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val r = o.getJSONArray("reactions")
            Expr(
                o.getInt("id"), o.getString("name"), o.optString("desc", ""),
                if (o.has("key")) o.getString("key") else null,
                if (o.has("char")) o.getString("char") else null,
                (0 until r.length()).map { r.getString(it) }.filter { it.isNotBlank() }.ifEmpty { listOf(o.getString("name")) }
            )
        }
        list = base + loadCustom()
    }

    private fun loadCustom(): List<Expr> = try {
        if (!customFile.exists()) emptyList() else {
            val a = JSONArray(customFile.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Expr(o.getInt("id"), o.getString("name"), "Your own expression", null, o.getString("ch"), listOf(o.getString("reaction")))
            }
        }
    } catch (_: Throwable) { emptyList() }

    /** A user-made expression: any emoji, a name and an optional reaction. */
    fun addCustom(ch: String, name: String, reaction: String): Expr {
        val id = (list.maxOfOrNull { it.id } ?: 0).coerceAtLeast(99) + 1
        val nm = name.ifBlank { "My expression $id" }
        val e = Expr(id, nm, "Your own expression", null, ch, listOf(reaction.ifBlank { nm }))
        list = list + e
        try {
            val a = JSONArray()
            for (x in list.filter { it.id > 99 }) {
                val o = org.json.JSONObject()
                o.put("id", x.id); o.put("name", x.name); o.put("ch", x.ch); o.put("reaction", x.reactions[0])
                a.put(o)
            }
            customFile.writeText(a.toString())
        } catch (_: Throwable) {}
        return e
    }

    fun byId(id: Int): Expr? = list.firstOrNull { it.id == id }

    fun bitmap(e: Expr): Bitmap? {
        val k = e.key ?: return null
        return cache.getOrPut(e.id) {
            ctx.assets.open("faces/$k.png").use { BitmapFactory.decodeStream(it) } ?: return null
        }
    }
}
