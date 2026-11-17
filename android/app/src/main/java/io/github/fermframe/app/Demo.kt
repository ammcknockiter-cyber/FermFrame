package io.github.fermframe.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Demo mode: made-up FermFrames for screenshots and trying the app (nothing real is contacted). */
object Demo {
    var on = false
    const val PREFIX = "192.168.0"

    private class Dev(val mac: String, val ip: String, val name: String, val brew: String, val online: Boolean,
                      val og: Double, val sg: Double, val temp: Double, val day: Int, val bat: Int, val readings: Int, val ready: Boolean) {
        val s = DoubleArray(88); val t = DoubleArray(88)
        init {
            val r = Random(7)
            for (i in 0 until 88) {
                val f = 1 / (1 + exp((i - (if (ready) 30 else 42)) / 7.0))
                s[i] = sg + (og - sg) * f + (r.nextDouble() - 0.5) * 0.0006
                t[i] = temp + 0.6 * sin(i / 8.0) + (if (i < 6) (6 - i) * 0.25 else 0.0) + (r.nextDouble() - 0.5) * 0.3
            }
        }
    }

    private val devs = listOf(
        Dev("A4:CF:12:3B:9E:01", "$PREFIX.21", "Garage fridge", "West Coast IPA", true, 1.062, 1.013, 18.6, 9, 82, 412, false),
        Dev("A4:CF:12:3B:9E:02", "$PREFIX.22", "Brew closet", "Oatmeal Stout", true, 1.058, 1.019, 19.4, 12, 64, 590, true),
        Dev("A4:CF:12:3B:9E:03", "$PREFIX.23", "Kitchen", "", false, 0.0, 0.0, 0.0, 0, 0, 0, false),
    )

    fun clocks() = devs.map { Clock(it.mac, it.name, it.ip) }

    fun scan(): List<Net.Found> {
        Thread.sleep(1200)
        return devs.filter { it.online }.mapNotNull { d -> Net.json(handle("http://${d.ip}/fermframe.json", "GET", null)!!.body)?.let { Net.Found(d.ip, it) } }
    }

    fun handle(url: String, method: String, body: ByteArray?): Resp? {
        if (!on || !url.startsWith("http://$PREFIX.")) return null
        val ip = url.removePrefix("http://").substringBefore('/')
        val path = "/" + url.removePrefix("http://").substringAfter('/', "")
        val d = devs.firstOrNull { it.ip == ip }
        Thread.sleep(if (body != null && body.size > 100000) 4000 else 250)
        if (d == null || !d.online) return Resp(0, "", "timed out")
        return when (path) {
            "/fermframe.json" -> Resp(200, JSONObject()
                .put("firmware", "fermframe").put("build", "full").put("version", "2.2.0").put("mac", d.mac).put("mode", "wifi")
                .put("ip", d.ip).put("wifi", "MyHomeWiFi").put("account", true).put("problem", "").put("heap", 37400)
                .put("readings", d.readings).put("brews", if (d.ready) 1 else 2).toString())
            "/graph.json" -> Resp(200, JSONObject()
                .put("name", d.brew).put("sg", d.sg).put("temp", d.temp).put("og", d.og).put("bat", d.bat).put("day", d.day)
                .put("ready", if (d.ready) 1 else 0).put("s", JSONArray(d.s.toList())).put("t", JSONArray(d.t.toList())).toString())
            else -> Resp(404, "Not found")
        }
    }
}
