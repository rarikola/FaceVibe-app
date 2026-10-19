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
    val list: List<Expr>
    private val cache = HashMap<Int, Bitmap>()

    init {
        val arr = JSONArray(ctx.assets.open("expressions.json").bufferedReader().use { it.readText() })
        list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val r = o.getJSONArray("reactions")
            Expr(
                o.getInt("id"), o.getString("name"), o.optString("desc", ""),
                if (o.has("key")) o.getString("key") else null,
                if (o.has("char")) o.getString("char") else null,
                (0 until r.length()).map { r.getString(it) }.filter { it.isNotBlank() }.ifEmpty { listOf(o.getString("name")) }
            )
        }
    }

    fun byId(id: Int): Expr? = list.firstOrNull { it.id == id }

    fun bitmap(e: Expr): Bitmap? {
        val k = e.key ?: return null
        return cache.getOrPut(e.id) {
            ctx.assets.open("faces/$k.png").use { BitmapFactory.decodeStream(it) } ?: return null
        }
    }
}
