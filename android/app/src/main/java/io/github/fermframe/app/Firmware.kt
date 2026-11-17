package io.github.fermframe.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The firmware built into this app, and the safety check for any other .bin file (same rules as the Windows app). */
object Firmware {
    const val STOCK_MAX = 507904      // largest file a brand-new clock's own update page takes
    private const val MAX_SKETCH = 1044464   // largest program a FermFrame's flash layout (4M1M) can hold

    fun full(ctx: Context): ByteArray = ctx.assets.open("fermframe.bin").use { it.readBytes() }
    fun lite(ctx: Context): ByteArray = ctx.assets.open("fermframe-lite.bin").use { it.readBytes() }

    private var bundled: String? = null
    fun bundledVersion(ctx: Context): String = bundled ?: inspect(full(ctx)).version.also { bundled = it }

    fun older(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p < q }
        return false
    }

    class Check(val block: String? = null, val isFermFrame: Boolean = false, val version: String = "", val build: String = "")

    private fun find(b: ByteArray, s: String): Boolean {
        val k = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - k.size) { for (j in k.indices) if (b[i + j] != k[j]) continue@outer; return true }
        return false
    }

    fun inspect(b: ByteArray): Check {
        if (b.size < 4096 || b[0] != 0xE9.toByte()) return Check("This isn't a firmware file.")
        val esp8266 = b[11] == 0x40.toByte() && b[10] == 0x10.toByte() && find(b, "esp8266")
        if (!esp8266) return Check("This firmware is for a different chip (not the ESP8266 inside a FermFrame). Installing it would stop the FermFrame working until it's opened up and repaired with a cable.")
        if (b.size > MAX_SKETCH) return Check("This file is too big to fit on a FermFrame (${b.size / 1024} KB).")
        val ff = find(b, "\"firmware\":\"fermframe\"") || find(b, "\"firmware\":\"brewscreen\"")
        val m = Regex("(?:FermFrame|BrewScreen) ([0-9][0-9.]*) \\((full|lite)\\)").find(String(b, Charsets.ISO_8859_1))
        return Check(null, ff, m?.groupValues?.get(1) ?: "", m?.groupValues?.get(2) ?: "")
    }

    /** Sends firmware to a FermFrame and waits for it to come back. Returns a message for the user. */
    suspend fun install(ctx: Context, c: Clock, bytes: ByteArray, say: (String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        say("Sending FermFrame to ${c.name}... (about 30 seconds - keep it plugged in)")
        val r = Net.postFile(c.base + "/update", "firmware", "fermframe.bin", bytes, 180000, Secrets.get(ctx, c.mac))
        if (r.status == 401) return@withContext false to "The FermFrame says the settings password is wrong. Open the Settings tab to type it in."
        if (r.status != 200 && r.status != 0) return@withContext false to "The FermFrame said no (${r.status} ${r.body.take(80)})."
        say("${c.name} is restarting...")
        delay(10000)
        val until = System.currentTimeMillis() + 90000
        var info: org.json.JSONObject? = null
        while (info == null && System.currentTimeMillis() < until) { info = Net.clockInfo(c.base, 5000); if (info == null) delay(3000) }
        if (info == null) false to "${c.name} didn't come back yet - give it a minute, or unplug it and plug it back in."
        else true to "${c.name} is now on FermFrame ${info.optString("version")}." + if (r.status == 0) " (The upload looked unfinished, but it came back fine.)" else ""
    }
}

@Composable
fun FirmwareTab(m: AppModel, c: Clock) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val builtIn = remember { Firmware.bundledVersion(ctx) }
    var busy by remember { mutableStateOf(false) }
    var log by remember(c.mac) { mutableStateOf("") }
    var ok by remember(c.mac) { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }   // message to confirm, file

    val clockOlder = c.online && (Firmware.older(c.version, builtIn) || c.build != "full")
    val clockNewer = c.online && Firmware.older(builtIn, c.version)

    fun run(bytes: ByteArray) {
        busy = true; ok = true; m.updating.add(c.mac)
        scope.launch {
            val (good, msg) = try { Firmware.install(ctx, c, bytes) { log = it } } finally { m.updating.remove(c.mac) }
            busy = false; ok = good; log = msg; m.refresh()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) { ok = false; log = "Couldn't read that file."; return@rememberLauncherForActivityResult }
        val chk = Firmware.inspect(bytes)
        when {
            chk.block != null -> { ok = false; log = "That file can't be installed. " + chk.block }
            !chk.isFermFrame -> confirm = "This file isn't FermFrame firmware. It's for the right chip, so ${c.name} should still start, but it will stop being a FermFrame: this app won't find it or update it any more.\n\nInstall it anyway?" to bytes
            chk.build == "lite" -> confirm = "This is the setup-only version: no graph or readings, only the setup screen, until the full version goes back on.\n\nInstall it anyway?" to bytes
            chk.version.isNotEmpty() && Firmware.older(chk.version, c.version) -> confirm = "This is FermFrame ${chk.version}, older than the ${c.version} already on ${c.name}.\n\nInstall it anyway?" to bytes
            else -> confirm = "Install FermFrame ${chk.version.ifEmpty { "(version unknown)" }} on ${c.name}?" to bytes
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Firmware", color = FF.Amber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(if (c.info == null) "On this FermFrame: unknown (not answering)" else "On this FermFrame: FermFrame ${c.version}" + if (c.build != "full") " (setup-only version)" else "", color = FF.Text, fontSize = 15.sp)
            Text("Built into this app: FermFrame $builtIn", color = FF.Dim, fontSize = 15.sp)
            Text(when { !c.online -> "Can't update - the FermFrame isn't answering."; busy -> "Updating..."; clockOlder -> "A newer version is ready to install."
                clockNewer -> "This FermFrame has a newer version than this app. Update the app to get the same version."; else -> "Up to date." },
                color = when { !c.online -> FF.Bad; clockOlder -> FF.Amber; else -> FF.Good }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = FF.Amber, trackColor = FF.Field)
            if (log.isNotEmpty()) Text(log, color = if (ok) FF.Text else FF.Bad, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            if (!clockNewer) PrimaryButton(if (clockOlder) "Update this FermFrame" else "Reinstall $builtIn", Modifier.fillMaxWidth(), enabled = c.online && !busy) {
                run(Firmware.full(ctx))
            }
            val behind = m.clocks.filter { it.online && it.mac != c.mac && (Firmware.older(it.version, builtIn) || it.build != "full") }
            if (behind.isNotEmpty()) Text("${behind.size} other FermFrame${if (behind.size > 1) "s" else ""} need${if (behind.size == 1) "s" else ""} updating too.", color = FF.Amber, fontSize = 13.sp)
            SecondaryButton("Install a different .bin file...", Modifier.fillMaxWidth(), enabled = c.online && !busy) { pick.launch(arrayOf("*/*")) }
        }
        Text("Keep the FermFrame plugged in while it updates. It takes about a minute, then it restarts by itself. Its settings stay as they are.", color = FF.Dim, fontSize = 13.sp)
    }

    confirm?.let { (msg, bytes) ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = FF.Card, title = { Text("Install firmware?") }, text = { Text(msg) },
            confirmButton = { TextButton({ confirm = null; run(bytes) }) { Text("Install", color = FF.Amber) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel", color = FF.Dim) } })
    }
}
