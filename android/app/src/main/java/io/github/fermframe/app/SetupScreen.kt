package io.github.fermframe.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import kotlin.coroutines.resume

/** Joins a clock's own Wi-Fi just for this app (Android 10+): the rest of the phone stays on the home Wi-Fi. */
class ClockWifi(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var cb: ConnectivityManager.NetworkCallback? = null

    /** `pattern`: an advanced glob (e.g. "[Ss]mart [Ww]eather [Cc]lock.*") or an exact name. Android asks the user to confirm. */
    suspend fun join(pattern: String, exact: Boolean, passphrase: String?, timeoutMs: Long = 120_000): Network? {
        release()
        if (Build.VERSION.SDK_INT < 29) return null
        val spec = WifiNetworkSpecifier.Builder().apply {
            if (exact) setSsid(pattern) else setSsidPattern(PatternMatcher(pattern, PatternMatcher.PATTERN_ADVANCED_GLOB))
            if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase)
        }.build()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val c = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                    override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
                }
                cb = c
                cm.requestNetwork(req, c)
                cont.invokeOnCancellation { release() }
            }
        }
    }

    fun release() { cb?.let { try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { } }; cb = null }
}

/** The phone's time zone in the form the FermFrame uses (for the clock page and night dimming). */
fun posixTimeZone(): String {
    val id = TimeZone.getDefault().id
    return when {
        id in listOf("Europe/London", "Europe/Dublin", "Europe/Belfast", "Europe/Guernsey", "Europe/Jersey", "Europe/Isle_of_Man") -> "GMT0BST,M3.5.0/1,M10.5.0"
        id in listOf("Europe/Athens", "Europe/Helsinki", "Europe/Kiev", "Europe/Kyiv", "Europe/Riga", "Europe/Sofia", "Europe/Tallinn", "Europe/Vilnius", "Europe/Bucharest") -> "EET-2EEST,M3.5.0/3,M10.5.0/4"
        id.startsWith("Europe/") -> "CET-1CEST,M3.5.0,M10.5.0/3"
        id in listOf("America/New_York", "America/Detroit", "America/Toronto", "America/Indiana/Indianapolis") -> "EST5EDT,M3.2.0,M11.1.0"
        id in listOf("America/Chicago", "America/Winnipeg") -> "CST6CDT,M3.2.0,M11.1.0"
        id in listOf("America/Denver", "America/Edmonton", "America/Boise") -> "MST7MDT,M3.2.0,M11.1.0"
        id in listOf("America/Los_Angeles", "America/Vancouver") -> "PST8PDT,M3.2.0,M11.1.0"
        id in listOf("Australia/Sydney", "Australia/Melbourne", "Australia/Canberra", "Australia/Hobart") -> "AEST-10AEDT,M10.1.0,M4.1.0/3"
        id == "Australia/Brisbane" -> "AEST-10"
        id == "Pacific/Auckland" -> "NZST-12NZDT,M9.5.0,M4.1.0/3"
        id == "Africa/Johannesburg" -> "SAST-2"
        else -> "GMT0BST,M3.5.0/1,M10.5.0"
    }
}

private class Line(val text: String, val kind: String = "")

/** The home Wi-Fi name, without the user typing it: from a FermFrame already on the Wi-Fi, or from the phone's own connection. */
object HomeWifi {
    /** Copies the name a working FermFrame uses (no permission needed). */
    fun fromFermFrames(ctx: Context, clocks: List<Clock>): String? {
        for (c in clocks.filter { it.online && it.ip.isNotEmpty() }) {
            val r = Net.get(c.base + "/", 8000, Secrets.get(ctx, c.mac))
            val s = if (r.status == 200) ClockSettings.parse(r.body)?.get("ssid") else null
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The Wi-Fi the phone is on. Android only tells apps this with location permission. */
    @Suppress("DEPRECATION")
    suspend fun fromPhone(ctx: Context): String? {
        if (!hasPermission(ctx)) return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val raw: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            withTimeoutOrNull(3000) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                            val name = (caps.transportInfo as? android.net.wifi.WifiInfo)?.ssid
                            if (cont.isActive) cont.resume(name)
                            try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                        }
                    }
                    cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
                    cont.invokeOnCancellation { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
                }
            }
        } else ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid
        val s = raw?.trim('"')
        return if (s.isNullOrBlank() || s == "<unknown ssid>" || s.startsWith("FermFrame-Setup") || s.contains("eather", true)) null else s
    }
}

@Composable
fun SetupScreen(m: AppModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wifi = remember { ClockWifi(ctx) }
    DisposableEffect(Unit) { onDispose { wifi.release() } }

    var brandNew by remember { mutableStateOf(true) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("brewfather") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<Line>() }
    var askPw by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var askAgain by remember { mutableStateOf(false) }

    // Fill in the home Wi-Fi name by itself
    var ssidAuto by remember { mutableStateOf(false) }
    var ssidLooked by remember { mutableStateOf(false) }
    suspend fun findSsid() {
        val s = HomeWifi.fromPhone(ctx) ?: withContext(Dispatchers.IO) { HomeWifi.fromFermFrames(ctx, m.clocks) }
        if (s != null && ssid.isBlank()) { ssid = s; ssidAuto = true }
        ssidLooked = true
    }
    LaunchedEffect(Unit) { findSsid() }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { scope.launch { findSsid() } }

    fun say(t: String, k: String = "") { log.add(Line(t, k)) }
    suspend fun hotspotPassword(again: Boolean): String? { val d = CompletableDeferred<String?>(); askAgain = again; askPw = d; return d.await() }

    /** Joins FermFrame-Setup with the password from the clock's screen (asks for it, up to 3 tries). */
    suspend fun joinSetup(): Network? {
        var pw: String? = null
        for (tries in 0 until 3) {
            if (pw == null || tries > 0) pw = hotspotPassword(tries > 0)?.replace(Regex("\\s"), "")?.lowercase() ?: return null
            say("Joining FermFrame-Setup... tap it when your phone asks.")
            wifi.join("FermFrame-Setup", true, pw)?.let { return it }
            say("That didn't connect.", "bad")
        }
        return null
    }

    suspend fun run() {
        if (Build.VERSION.SDK_INT < 29) { say("Setting up from a phone needs Android 10 or newer. Use the Windows app instead.", "bad"); return }
        val ap = "http://192.168.4.1"
        var net: Network?
        var mac: String? = null
        if (brandNew) {
            say("Looking for your clock's own Wi-Fi... when your phone asks, tap the network called \"Smart Weather Clock...\".", "step")
            net = wifi.join("[Ss]mart [Ww]eather [Cc]lock.*", false, null)
            if (net == null) { say("Couldn't connect to the clock. Unplug it, wait 5 seconds, plug it back in, wait 30 seconds and try again.", "bad"); return }
            val home = withContext(Dispatchers.IO) { Net.get("$ap/", 8000, null, net) }
            val title = Regex("(?i)<title>([^<]*)</title>").find(home.body)?.groupValues?.get(1)?.trim() ?: ""
            if (!Regex("(?i)smart weather clock|스마트").containsMatchIn(title)) { say("This doesn't look like a Geekmagic Smart Weather Clock (it says '$title').", "bad"); return }
            val lite = Firmware.lite(ctx)
            if (lite.size > Firmware.STOCK_MAX) { say("The setup firmware is too big for a brand-new clock.", "bad"); return }
            withContext(Dispatchers.IO) { Net.get("$ap/mac", 5000, null, net) }.body.trim().let { if (Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(it)) { mac = it.uppercase(); say("ID: $mac") } }
            say("Installing FermFrame... (about 30 seconds - keep the clock plugged in!)")
            val prefix = if (title.contains("스마트")) "KR_SDP" else "SDP"
            val r = withContext(Dispatchers.IO) { Net.postFile("$ap/update_ota", "update", "$prefix-fermframe-lite.bin", lite, 150000, null, net) }
            when {
                r.status == 200 && r.body.trim() == "OK" -> say("Installed. The clock is restarting.", "good")
                r.status == 0 -> say("The clock stopped answering after the upload - checking whether it worked...")
                else -> { say("The clock didn't accept the file (it said: ${r.status} ${r.body.take(60)}).", "bad"); return }
            }
            wifi.release()
            delay(12000)
            say("Now the clock shows \"Set me up!\" and a password under its QR code.", "step")
        } else say("Your FermFrame shows \"Set me up!\" and a password under its QR code. (It waits about 3 minutes for the phone.)", "step")

        net = joinSetup() ?: run { say("Couldn't join the FermFrame's Wi-Fi. Check the password on its screen and try again. If it's showing your brew again, it left Setup mode (it waits about 3 minutes): plug it in 3 times again, then tap Try again straight away.", "bad"); return }
        var info = withContext(Dispatchers.IO) { Net.clockInfo(ap, 8000, net) } ?: run { say("The FermFrame isn't answering.", "bad"); return }
        mac = info.optString("mac").ifEmpty { mac }
        val builtIn = Firmware.bundledVersion(ctx)
        if (info.optString("build") != "full" || Firmware.older(info.optString("version"), builtIn)) {
            say("Installing the full FermFrame $builtIn...")
            val r = withContext(Dispatchers.IO) { Net.postFile("$ap/update", "firmware", "fermframe.bin", Firmware.full(ctx), 150000, null, net) }
            if (r.status != 200 && r.status != 0) { say("The full version didn't install (${r.status}). Keep the FermFrame near the phone and try again.", "bad"); return }
            say("The FermFrame is restarting...")
            wifi.release(); delay(12000)
            say("Joining it again - tap FermFrame-Setup when your phone asks.")
            net = wifi.join("FermFrame-Setup", true, null.let { lastPw })
            if (net == null) net = joinSetup()
            if (net == null) { say("The FermFrame didn't come back after the update. Unplug it, plug it back in and try again.", "bad"); return }
            info = withContext(Dispatchers.IO) {
                var j: org.json.JSONObject? = null; val until = System.currentTimeMillis() + 60000
                while (j == null && System.currentTimeMillis() < until) { j = Net.clockInfo(ap, 5000, net); if (j == null) delay(2000) }
                j
            } ?: run { say("The FermFrame didn't come back after the update.", "bad"); return }
        }
        say("Saving your Wi-Fi and account details on the FermFrame...")
        // Blank fields are left out, so a FermFrame that already has them keeps its saved Wi-Fi password and account
        val f = linkedMapOf("ssid" to ssid.trim(), "source" to source, "tz" to posixTimeZone())
        if (pass.isNotEmpty()) f["pass"] = pass
        if (source != "local") { if (user.isNotBlank()) f["user"] = user.trim(); if (secret.isNotBlank()) f["secret"] = secret.trim() }
        if (admin.isNotEmpty()) f["admin"] = admin
        val s = withContext(Dispatchers.IO) { Net.postForm("$ap/save", f, 20000, null, net) }
        if (s.status != 200) { say("The FermFrame didn't save the settings (code ${s.status}).", "bad"); return }
        if (admin.isNotEmpty() && mac != null) Secrets.set(ctx, mac!!, admin)
        say("Saved. The FermFrame joins your Wi-Fi and shows your brew.", "good")
        wifi.release()

        say("Checking the FermFrame on your home Wi-Fi...", "step")
        var found: Net.Found? = null
        val until = System.currentTimeMillis() + 120_000
        while (found == null && System.currentTimeMillis() < until) {
            delay(6000)
            found = withContext(Dispatchers.IO) { Net.scan(ctx) }.firstOrNull { mac == null || it.info.optString("mac") == mac }
        }
        if (found == null) {
            say("The FermFrame hasn't shown up on \"${ssid.trim()}\" yet.", "bad")
            say("If its screen says \"Can't find Wi-Fi\", the Wi-Fi name or password was wrong: scan the code on its screen with your phone to fix it.")
            return
        }
        say("The FermFrame is on your Wi-Fi at ${found.ip}.", "good")
        m.refresh()
        if (source == "local") say("Now set your iSpindel / GravityMon to send to Server ${found.ip}, Port 80, Path /ispindel.", "step")
        say("All done!", "good")
    }

    Column(Modifier.fillMaxSize().background(FF.Bg).systemBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (!running) onClose() }) { Text("←", color = FF.Text, fontSize = 24.sp) }
            Text("Set up a new FermFrame", color = FF.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (log.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Plug the clock into power. Keep it near the phone. Your phone joins the clock's own Wi-Fi just for this app – Android asks you to confirm.", color = FF.Dim, fontSize = 14.sp)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("What does the clock's screen show?", color = FF.Amber, fontWeight = FontWeight.Bold)
                        Pick("The weather – it's brand new", brandNew) { brandNew = true }
                        Pick("\"Set me up!\" and a password – it already has FermFrame", !brandNew) { brandNew = false }
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. Your home Wi-Fi", color = FF.Amber, fontWeight = FontWeight.Bold)
                        OutlinedTextField(ssid, { ssid = it; ssidAuto = false }, label = { Text("Wi-Fi name (2.4 GHz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (ssidAuto) Text("Filled in for you from your Wi-Fi. Change it if the FermFrame should use a different one.", color = FF.Dim, fontSize = 12.sp)
                        else if (ssidLooked && ssid.isBlank() && !HomeWifi.hasPermission(ctx))
                            SecondaryButton("Use my phone's Wi-Fi name", Modifier.fillMaxWidth()) { askLocation.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }
                        OutlinedTextField(pass, { pass = it }, label = { Text("Wi-Fi password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        if (!brandNew) Text("Already set up before? Leave the password and account details blank to keep the ones it has.", color = FF.Dim, fontSize = 12.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2. Where are your readings?", color = FF.Amber, fontWeight = FontWeight.Bold)
                        Pick("Brewfather", source == "brewfather") { source = "brewfather" }
                        Pick("RAPT Pill", source == "rapt") { source = "rapt" }
                        Pick("My own hydrometer (iSpindel / GravityMon) – free", source == "local") { source = "local" }
                        if (source == "local") Text("No account needed. After setup the app shows exactly what to type into the hydrometer.", color = FF.Dim, fontSize = 13.sp)
                        else {
                            val bf = source == "brewfather"
                            Text(if (bf) "Needs Brewfather Premium (30-day free trial). Brewfather: Settings > API > Generate API key (tick Read batches)." else "Needs RAPT Premium (about \$15 a year). RAPT website: My account > API Secrets > Create.", color = FF.Amber, fontSize = 13.sp)
                            OutlinedTextField(user, { user = it }, label = { Text(if (bf) "Brewfather user ID" else "RAPT email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(secret, { secret = it }, label = { Text(if (bf) "Brewfather API key" else "RAPT API secret") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("3. Settings password (optional)", color = FF.Amber, fontWeight = FontWeight.Bold)
                        Text("Stops anyone else on your Wi-Fi changing its settings. The app remembers it, encrypted, on this phone.", color = FF.Dim, fontSize = 13.sp)
                        OutlinedTextField(admin, { admin = it }, label = { Text("Settings password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        } else {
            val state = rememberLazyListState()
            LaunchedEffect(log.size) { state.animateScrollToItem(log.size - 1) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(log) { l -> Text(l.text, color = when (l.kind) { "good" -> FF.Good; "bad" -> FF.Bad; "step" -> FF.Amber; else -> FF.Text }, fontSize = 15.sp) }
            }
        }
        Column(Modifier.padding(16.dp)) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = FF.Amber, trackColor = FF.Field)
            PrimaryButton(if (running) "Working... please wait" else if (log.isEmpty()) "Set up my FermFrame" else "Try again", Modifier.fillMaxWidth(), enabled = !running) {
                if (ssid.isBlank()) { log.clear(); say("Type your home Wi-Fi name first.", "bad"); return@PrimaryButton }
                if (brandNew && pass.isEmpty()) { log.clear(); say("Type your home Wi-Fi password first.", "bad"); return@PrimaryButton }
                if (brandNew && source != "local" && (user.isBlank() || secret.isBlank())) { log.clear(); say("Fill in your account details first.", "bad"); return@PrimaryButton }
                log.clear(); running = true
                scope.launch { try { run() } catch (e: Exception) { say(e.message ?: "Something went wrong.", "bad") } finally { wifi.release(); running = false } }
            }
            if (log.isNotEmpty() && !running) TextButton({ log.clear() }) { Text("Back to the details", color = FF.Dim) }
        }
    }

    askPw?.let { d ->
        var pw by remember(d) { mutableStateOf("") }
        AlertDialog(onDismissRequest = {}, containerColor = FF.Card, title = { Text("FermFrame Wi-Fi password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (askAgain) Text("That didn't work – check the password and try again.", color = FF.Bad)
                    Text("Look at the FermFrame's screen: under the QR code it shows \"Password:\" and 8 letters and numbers. Type them here.")
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password on its screen") })
                }
            },
            confirmButton = { TextButton({ lastPw = pw.replace(Regex("\\s"), "").lowercase(); askPw = null; d.complete(pw) }, enabled = pw.isNotBlank()) { Text("Join", color = FF.Amber) } },
            dismissButton = { TextButton({ askPw = null; d.complete(null) }) { Text("Cancel", color = FF.Dim) } })
    }
}

/** The setup hotspot password typed last (reused when the FermFrame restarts during setup). */
private var lastPw: String? = null

@Composable
private fun Pick(label: String, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = FF.Amber, unselectedColor = FF.Dim))
        Text(label, color = FF.Text, fontSize = 15.sp)
    }
