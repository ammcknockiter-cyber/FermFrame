package io.github.fermframe.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the screens show, and the work behind it. */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var clocks by mutableStateOf(Store.load(app))
        private set
    var selected by mutableStateOf<String?>(null)        // mac of the FermFrame being viewed
    var showAlerts by mutableStateOf(false)
    var showSetup by mutableStateOf(false)
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    private var lastScan = 0L
    private var again = false; private var againScan = false   // a refresh asked for while one was running
    /** FermFrames being updated right now: they restart, so don't call them "not answering" meanwhile. */
    val updating = mutableSetOf<String>()

    val current get() = clocks.firstOrNull { it.mac == selected }

    fun clock(mac: String) = clocks.firstOrNull { it.mac == mac }

    private fun nextName(): String {
        var n = 1
        while (clocks.any { it.name == "FermFrame $n" }) n++
        return "FermFrame $n"
    }

    /** Checks the known FermFrames (and looks for new or moved ones when needed). */
    fun refresh(scan: Boolean = false) {
        if (busy) { again = true; againScan = againScan || scan; return }
        busy = true
        status = if (scan) "Looking for FermFrames on your Wi-Fi..." else "Checking your FermFrames..."
        viewModelScope.launch {
            val known = clocks.filter { it.ip.isNotEmpty() }
            val found = withContext(Dispatchers.IO) {
                val byMac = HashMap<String, Net.Found>()
                known.map { c -> async { Net.clockInfo(c.base)?.let { Net.Found(c.ip, it) } } }.awaitAll()
                    .filterNotNull().forEach { byMac[it.info.optString("mac")] = it }
                val missing = known.any { !byMac.containsKey(it.mac) } || known.isEmpty()
                if (scan || (missing && System.currentTimeMillis() - lastScan > 3 * 60_000)) {
                    lastScan = System.currentTimeMillis()
                    Net.scan(ctx).forEach { byMac[it.info.optString("mac")] = it }
                }
                byMac
            }
            val graphs = withContext(Dispatchers.IO) {
                found.values.map { f -> async { f.info.optString("mac") to Net.get("http://${f.ip}/graph.json", 10000).let { r -> if (r.status == 200) Net.json(r.body)?.let { Graph.parse(it) } else null } } }
                    .awaitAll().toMap()
            }
            val now = System.currentTimeMillis()
            val list = clocks.toMutableList()
            for ((mac, f) in found) {
                if (mac.isEmpty()) continue
                val i = list.indexOfFirst { it.mac == mac }
                val old = if (i >= 0) list[i] else Clock(mac, nextNameFor(list), f.ip)
                var upd = old.copy(ip = f.ip, info = f.info, online = true, checkedAt = now, graph = graphs[mac] ?: old.graph)
                // Firmware 2.5+ keeps its own name, so the phone and the Windows app show the same one
                val stored = f.info.optString("name").trim()
                if (stored.isNotEmpty()) upd = upd.copy(name = stored)
                else if (keepsName(upd) && nameSent.add(mac)) { val u = upd; viewModelScope.launch { sendName(u, u.name) } }
                if (i >= 0) list[i] = upd else list.add(upd)
            }
            clocks = list.map { if (found.containsKey(it.mac) || it.mac in updating) it else it.copy(online = false) }
            Store.save(ctx, clocks)
            Widget.save(ctx, clocks)
            val on = clocks.count { it.online }
            status = (if (clocks.isEmpty()) "No FermFrames found on this Wi-Fi." else "$on of ${clocks.size} answering.") +
                "  Checked " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
            busy = false
            if (again) { val s = againScan; again = false; againScan = false; refresh(s) }
        }
    }

    private fun nextNameFor(list: List<Clock>): String {
        var n = 1
        while (list.any { it.name == "FermFrame $n" }) n++
        return "FermFrame $n"
    }

    fun rename(mac: String, name: String) {
        if (name.isBlank()) return
        clocks = clocks.map { if (it.mac == mac) it.copy(name = name.trim()) else it }
        Store.save(ctx, clocks)
        Widget.save(ctx, clocks)
        val c = clock(mac) ?: return
        if (keepsName(c)) viewModelScope.launch {
            val ok = sendName(c, c.name)
            status = if (ok) "Renamed. Your other phones and computers show the new name too."
                else "Renamed on this phone, but the FermFrame didn't take it. If it has a settings password, type it in on the Settings tab, then rename it again."
        } else if (c.online) status = "Renamed on this phone. Update the FermFrame's firmware so your other devices show the same name."
    }

    /** FermFrame 2.5 and newer store their own name. */
    private fun keepsName(c: Clock) = c.online && c.version.isNotEmpty() && !Firmware.older(c.version, "2.5.0")
    private val nameSent = mutableSetOf<String>()   // FermFrames given this phone's name once (they had none yet)

    private suspend fun sendName(c: Clock, name: String): Boolean = withContext(Dispatchers.IO) {
        Net.postForm(c.base + "/save", mapOf("name" to name), 15000, Secrets.get(ctx, c.mac)).status == 200
    }

    // ---- is there a newer FermFrame app?
    var update by mutableStateOf<Updates.Release?>(null)
        private set
    fun checkForUpdate(force: Boolean = false) = viewModelScope.launch {
        update = withContext(Dispatchers.IO) { Updates.check(ctx, force) }
        if (force && update == null) status = "This is the newest FermFrame app (${BuildConfigVersion.name})."
    }
    fun dismissUpdate() { update?.let { Updates.skip(ctx, it.version) }; update = null }

    fun remove(mac: String) {
        clocks = clocks.filter { it.mac != mac }
        if (selected == mac) selected = null
        Store.save(ctx, clocks)
    }
}
