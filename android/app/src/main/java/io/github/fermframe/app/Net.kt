package io.github.fermframe.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

class Resp(val status: Int, val body: String, val error: String? = null)

/** Talking to FermFrames over the home network (plain HTTP, like the Windows app). */
object Net {

    /** The settings password is plain HTTP sign-in with user name "fermframe" (firmware 2.3+). */
    const val ADMIN_USER = "fermframe"

    fun get(url: String, timeoutMs: Int = 6000, password: String? = null, network: android.net.Network? = null): Resp =
        request(url, "GET", null, null, timeoutMs, password, network)

    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 20000, password: String? = null, network: android.net.Network? = null): Resp {
        val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return request(url, "POST", "application/x-www-form-urlencoded", body.toByteArray(), timeoutMs, password, network)
    }

    /** Multipart upload of one file (firmware updates). */
    fun postFile(url: String, field: String, fileName: String, bytes: ByteArray, timeoutMs: Int = 180000, password: String? = null, network: android.net.Network? = null): Resp {
        val boundary = "----FermFrame" + System.currentTimeMillis()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = head.toByteArray() + bytes + tail.toByteArray()
        return request(url, "POST", "multipart/form-data; boundary=$boundary", body, timeoutMs, password, network)
    }

    /** `network`: send through that Wi-Fi (a clock's own hotspot during setup) instead of the phone's normal one. */
    private fun request(url: String, method: String, type: String?, body: ByteArray?, timeoutMs: Int, password: String?, network: android.net.Network? = null): Resp {
        Demo.handle(url, method, body)?.let { return it }
        return try {
            val c = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
            c.connectTimeout = minOf(timeoutMs, 5000); c.readTimeout = timeoutMs
            c.requestMethod = method; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "FermFrame-Android/" + BuildConfigVersion.name)
            if (!password.isNullOrEmpty()) {
                val token = android.util.Base64.encodeToString("$ADMIN_USER:$password".toByteArray(), android.util.Base64.NO_WRAP)
                c.setRequestProperty("Authorization", "Basic $token")
            }
            if (body != null) {
                c.doOutput = true
                if (type != null) c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            Resp(code, text)
        } catch (e: Exception) {
            Resp(0, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun json(s: String): JSONObject? = try { JSONObject(s.trimStart('﻿')) } catch (e: Exception) { null }

    /** A FermFrame's status (/fermframe.json, or /brewscreen.json from before the rename), or null. */
    fun clockInfo(base: String, timeoutMs: Int = 5000, network: android.net.Network? = null): JSONObject? {
        for (path in listOf("/fermframe.json", "/brewscreen.json")) {
            val r = get(base + path, timeoutMs, null, network)
            if (r.status == 0) return null
            if (r.status != 200) continue
            val j = json(r.body) ?: continue
            val fw = j.optString("firmware")
            if (fw == "fermframe" || fw == "brewscreen") return j
        }
        return null
    }

    /** First three parts of this phone's Wi-Fi address, e.g. "192.168.1". */
    fun wifiPrefix(ctx: Context): String? {
        if (Demo.on) return Demo.PREFIX
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val nets = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        for (n in nets) {
            val lp = cm.getLinkProperties(n) ?: continue
            val a = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            if (a != null) return a.hostAddress!!.substringBeforeLast('.')
        }
        return null
    }

    class Found(val ip: String, val info: JSONObject)

    /** Looks at every address on the Wi-Fi for FermFrames. */
    suspend fun scan(ctx: Context): List<Found> = withContext(Dispatchers.IO) {
        if (Demo.on) return@withContext Demo.scan()
        val prefix = wifiPrefix(ctx) ?: return@withContext emptyList()
        val gate = Semaphore(64)
        val open = coroutineScope {
            (1..254).map { n ->
                async {
                    gate.withPermit {
                        val ip = "$prefix.$n"
                        try { Socket().use { it.connect(InetSocketAddress(ip, 80), 600) }; ip } catch (e: Exception) { null }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        coroutineScope {
            open.map { ip -> async { clockInfo("http://$ip", 4000)?.let { Found(ip, it) } } }.awaitAll().filterNotNull()
        }
    }
}

/** The app version, for the User-Agent (set from MainActivity). */
object BuildConfigVersion { var name = "1.0.0" }
