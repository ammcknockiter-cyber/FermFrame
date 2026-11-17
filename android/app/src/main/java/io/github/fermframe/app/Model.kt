package io.github.fermframe.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A FermFrame the app knows about. mac/name/ip are kept on the phone; the rest is live. */
data class Clock(
    val mac: String,
    val name: String,
    val ip: String,
    val info: JSONObject? = null,
    val graph: Graph? = null,
    val online: Boolean = false,
    val checkedAt: Long = 0,
) {
    val base get() = "http://$ip"
    val version get() = info?.optString("version") ?: ""
    val build get() = info?.optString("build") ?: ""
    val problem get() = info?.optString("problem")?.let { Html.decode(it) } ?: ""
}

/** The data behind the graph (/graph.json). */
class Graph(
    val name: String,
    val sg: Double?, val temp: Double?, val og: Double?, val battery: Double?, val day: Int?, val ready: Boolean,
    val s: List<Double?>, val t: List<Double?>,
) {
    companion object {
        private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        private fun arr(a: JSONArray?): List<Double?> =
            if (a == null) emptyList() else (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it).takeIf { d -> !d.isNaN() } }

        fun parse(j: JSONObject) = Graph(
            Html.decode(j.optString("name")), j.num("sg"), j.num("temp"), j.num("og"), j.num("bat"),
            j.num("day")?.toInt(), j.optInt("ready") == 1, arr(j.optJSONArray("s")), arr(j.optJSONArray("t")),
        )
    }
}

object Html {
    fun decode(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")
}

/** The list of FermFrames (names, last addresses), kept on this phone. */
object Store {
    private const val FILE = "fermframe"
    private const val KEY = "clocks"

    fun load(ctx: Context): List<Clock> {
        if (Demo.on) return Demo.clocks()
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Clock(it.optString("mac"), it.optString("name"), it.optString("ip")) }
        } catch (e: Exception) { emptyList() }
    }

    fun save(ctx: Context, clocks: List<Clock>) {
        if (Demo.on) return
        val a = JSONArray()
        clocks.forEach { a.put(JSONObject().put("mac", it.mac).put("name", it.name).put("ip", it.ip)) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
