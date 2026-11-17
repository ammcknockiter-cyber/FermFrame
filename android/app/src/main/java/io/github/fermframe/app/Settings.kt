package io.github.fermframe.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A FermFrame's settings, read from its own settings page (same fields as the page and the Windows app). */
class ClockSettings(val v: MutableMap<String, String>, val on: MutableSet<String>, val passSaved: Boolean, val secretSaved: Boolean, val adminSet: Boolean) {
    val full get() = v.containsKey("theme")
    operator fun get(k: String, def: String = "") = v[k] ?: def

    companion object {
        private fun attrs(t: String): Map<String, String> =
            Regex("([\\w-]+)(?:=(?:\"([^\"]*)\"|([^\\s\"'>]+)))?").findAll(t)
                .associate { m -> m.groupValues[1].lowercase() to Html.decode(m.groups[2]?.value ?: m.groups[3]?.value ?: "") }

        fun parse(html: String?): ClockSettings? {
            if (html == null || !html.contains("action=/save", ignoreCase = true)) return null
            val v = HashMap<String, String>(); val on = HashSet<String>()
            var pass = false; var secret = false; var admin = false
            for (m in Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html)) {
                val a = attrs(m.groupValues[1]); val name = a["name"] ?: continue
                val type = a["type"] ?: "text"; val value = a["value"] ?: ""; val ph = a["placeholder"] ?: ""
                when {
                    type == "checkbox" -> if (a.containsKey("checked")) on.add(name)
                    type == "radio" -> if (a.containsKey("checked")) v[name] = value
                    type == "hidden" -> {}
                    name == "pass" -> pass = ph.startsWith("(saved")
                    name == "secret" -> secret = ph.startsWith("(saved")
                    name == "admin" -> admin = ph.startsWith("(set")
                    else -> v[name] = value
                }
            }
            for (m in Regex("<select\\b([^>]*)>(.*?)</select>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)) {
                val name = attrs(m.groupValues[1])["name"] ?: continue
                var first: String? = null; var sel: String? = null
                for (o in Regex("<option value=(?:\"([^\"]*)\"|([^\\s>]*))([^>]*)>").findAll(m.groupValues[2])) {
                    val value = Html.decode(o.groups[1]?.value ?: o.groups[2]?.value ?: "")
                    if (first == null) first = value
                    if (o.groupValues[3].contains("selected")) sel = value
                }
                v[name] = sel ?: first ?: ""
            }
            return ClockSettings(v, on, pass, secret, admin)
        }

        val THEMES = mapOf(
            "beer" to listOf("#000000", "#ffffff", "#ffcc00", "#5bbeff", "#2a2a2a", "#8c8c8c"),
            "dark" to listOf("#101418", "#e6e6e6", "#7cd992", "#f2a65a", "#2b3138", "#8a949e"),
            "light" to listOf("#ffffff", "#111111", "#c77700", "#1f6fd1", "#dddddd", "#777777"),
            "retro" to listOf("#000000", "#33ff66", "#33ff66", "#b8ff66", "#0f3318", "#1f9944"),
            "contrast" to listOf("#000000", "#ffffff", "#ffff00", "#00ffff", "#555555", "#cccccc"),
        )
        val COLOUR_KEYS = listOf("cBg", "cText", "cSg", "cTemp", "cGrid", "cDim")
        val COLOUR_NAMES = listOf("Background", "Text", "Gravity", "Temperature", "Grid", "Small text")
    }
}

private fun hex(s: String): Color = try { Color(android.graphics.Color.parseColor(s)) } catch (e: Exception) { Color.Black }

@Composable
fun SettingsTab(m: AppModel, c: Clock) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(c.mac) { mutableStateOf<ClockSettings?>(null) }
    var message by remember(c.mac) { mutableStateOf("Reading the settings from the FermFrame...") }
    var needPassword by remember(c.mac) { mutableStateOf(false) }
    var reloadKey by remember(c.mac) { mutableIntStateOf(0) }

    LaunchedEffect(c.mac, reloadKey) {
        if (!c.online) { message = "This FermFrame isn't answering, so its settings can't be changed right now."; return@LaunchedEffect }
        message = "Reading the settings from the FermFrame..."
        val r = withContext(Dispatchers.IO) { Net.get(c.base + "/", 20000, Secrets.get(ctx, c.mac)) }
        when {
            r.status == 401 -> { needPassword = true; message = "This FermFrame's settings have a password." }
            r.status == 200 -> ClockSettings.parse(r.body)?.let { loaded = it; message = "" } ?: run { message = "Couldn't read the settings." }
            else -> message = "Couldn't read the settings (${r.error ?: "code " + r.status})."
        }
    }

    if (needPassword) {
        PasswordPrompt(c, onDone = { pw ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { Net.get(c.base + "/", 20000, pw) }
                if (r.status == 200) { Secrets.set(ctx, c.mac, pw); needPassword = false; reloadKey++ }
                else message = if (r.status == 401) "That password isn't right." else "Couldn't reach the FermFrame."
            }
        }, message = message)
        return
    }
    val s = loaded
    if (s == null) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message, color = FF.Dim) }; return }
    SettingsForm(m, c, s, onSaved = { wifiChanged -> if (!wifiChanged) reloadKey++ })
}

@Composable
private fun PasswordPrompt(c: Clock, onDone: (String) -> Unit, message: String) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(message, color = FF.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Type the settings password you chose for ${c.name}. The app remembers it, encrypted, on this phone.\n\nForgot it? Plug the FermFrame in 3 times in a row to open Setup mode.", color = FF.Dim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Settings password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Open settings", Modifier.fillMaxWidth(), enabled = pw.isNotEmpty()) { onDone(pw) }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = FF.Amber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Hint(text: String, color: Color = FF.Dim) = Text(text, color = color, fontSize = 12.sp)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, placeholder: String = "") =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = FF.Dim) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth())

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = FF.Amber, checkmarkColor = Color.Black, uncheckedColor = FF.Dim))
        Text(label, color = FF.Text, fontSize = 15.sp)
    }

@Composable
private fun Radios(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) =
    Column {
        options.forEach { (v, label) ->
            Row(Modifier.fillMaxWidth().clickable { onChange(v) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == v, { onChange(v) }, colors = RadioButtonDefaults.colors(selectedColor = FF.Amber, unselectedColor = FF.Dim))
                Text(label, color = FF.Text, fontSize = 15.sp)
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = if (options.any { it.first == value } || value.isEmpty()) options else options + (value to value)
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(all.firstOrNull { it.first == value }?.second ?: "", {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }, containerColor = FF.Field) {
            all.forEach { (v, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { onChange(v); open = false }) }
        }
    }
}

private val TIMES = (0 until 24 * 60 step 15).map { "%02d:%02d".format(it / 60, it % 60) }.map { it to it }
private val PALETTE = listOf("#000000", "#101418", "#2a2a2a", "#555555", "#8c8c8c", "#cccccc", "#ffffff", "#ffcc00", "#ff9800",
    "#ff5722", "#e53935", "#ec407a", "#ab47bc", "#7e57c2", "#3f51b5", "#1f6fd1", "#5bbeff", "#00bcd4", "#00ffff", "#26a69a",
    "#33ff66", "#7cd992", "#b8ff66", "#c77700", "#795548", "#0f3318")

@Composable
private fun SettingsForm(m: AppModel, c: Clock, s: ClockSettings, onSaved: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = remember(s) { mutableStateMapOf<String, String>().apply { putAll(s.v) } }
    val on = remember(s) { mutableStateListOf<String>().apply { addAll(s.on) } }
    var pass by remember(s) { mutableStateOf("") }
    var secret by remember(s) { mutableStateOf("") }
    var admin by remember(s) { mutableStateOf("") }
    var adminOff by remember(s) { mutableStateOf(false) }
    var newBrew by remember(s) { mutableStateOf(false) }
    var status by remember(s) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmWifi by remember { mutableStateOf(false) }
    var pickColour by remember { mutableStateOf(-1) }
    fun tick(k: String, b: Boolean) { if (b) { if (k !in on) on.add(k) } else on.remove(k) }
    val src = v["source"] ?: "brewfather"

    fun doSave() {
        val f = LinkedHashMap<String, String>()
        f["ssid"] = (v["ssid"] ?: "").trim(); if (pass.isNotEmpty()) f["pass"] = pass
        f["source"] = src; f["user"] = (v["user"] ?: "").trim(); if (secret.isNotBlank()) f["secret"] = secret.trim()
        f["device"] = (v["device"] ?: "").trim(); f["unit"] = v["unit"] ?: "C"; f["tz"] = v["tz"] ?: ""; f["refresh"] = v["refresh"] ?: "5"
        if (s.full) {
            f["look"] = "1"
            for (k in listOf("theme", "font", "bright", "nFrom", "nTo", "nLevel", "graph", "plot", "range", "pageSecs") + ClockSettings.COLOUR_KEYS) f[k] = v[k] ?: ""
            for (k in listOf("night", "pgGraph", "pgBig", "pgClock", "pgBrews", "keg")) if (k in on) f[k] = "1"
        }
        if (newBrew) f["newBrew"] = "1"
        if (adminOff) f["adminOff"] = "1" else if (admin.isNotEmpty()) f["admin"] = admin
        val wifiChanged = f["ssid"] != s["ssid"] || pass.isNotEmpty()
        saving = true; status = "Saving..."
        scope.launch {
            val r = withContext(Dispatchers.IO) { Net.postForm(c.base + "/save", f, 20000, Secrets.get(ctx, c.mac)) }
            saving = false
            if (r.status == 200) {
                if (adminOff) Secrets.set(ctx, c.mac, null) else if (admin.isNotEmpty()) Secrets.set(ctx, c.mac, admin)
                status = if (wifiChanged) "Saved. The FermFrame is restarting and joining the Wi-Fi." else "Saved. Have a look at your FermFrame."
                onSaved(wifiChanged); m.refresh()
            } else status = if (r.status == 401) "The FermFrame says the settings password is wrong." else "The FermFrame didn't save it (${r.error ?: "code " + r.status})."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Changes go to the FermFrame when you tap Save. It only restarts if you change the Wi-Fi.")
            Section("1. Home Wi-Fi") {
                Field("Wi-Fi name", v["ssid"] ?: "", { v["ssid"] = it })
                Field("Wi-Fi password", pass, { pass = it }, password = true, placeholder = if (s.passSaved) "Leave blank to keep the saved one" else "")
            }
            Section("2. Where are your readings?") {
                Radios(buildList { add("brewfather" to "Brewfather"); add("rapt" to "RAPT Pill"); if (s.full) add("local" to "My own hydrometer (iSpindel / GravityMon)") }, src) { v["source"] = it }
                when (src) {
                    "local" -> {
                        Hint("Free – no account or subscription. In the iSpindel's settings choose the HTTP service (GravityMon: HTTP POST) and send to:", FF.Text)
                        Text("Server: ${c.ip}    Port: 80    Path: /ispindel", color = FF.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Hint("GravityMon wants it as one address: http://${c.ip}/ispindel")
                        val h = c.info?.optString("hydrometer")?.let { Html.decode(it) } ?: ""
                        val ago = c.info?.optLong("hydroAgo", -1) ?: -1
                        Hint("Last reading from: " + if (h.isEmpty()) "nothing yet" else h + if (ago >= 0) " (${ago / 60} min ago)" else "")
                        Check("Start a new brew (clears this FermFrame's graph)", newBrew) { newBrew = it }
                    }
                    else -> {
                        val bf = src == "brewfather"
                        Hint(if (bf) "Needs a Brewfather Premium account (30-day free trial)." else "Needs RAPT Premium (about \$15 a year).", FF.Amber)
                        Field(if (bf) "Brewfather user ID" else "RAPT email", v["user"] ?: "", { v["user"] = it })
                        Field(if (bf) "Brewfather API key" else "RAPT API secret", secret, { secret = it }, password = true, placeholder = if (s.secretSaved) "Leave blank to keep the saved key" else "")
                        Hint(if (bf) "In Brewfather: Settings > API > Generate API key (tick Read batches)." else "On the RAPT website: My account > API Secrets > Create.")
                    }
                }
            }
            if (s.full) {
                Section("Preview") {
                    var page by remember { mutableIntStateOf(0) }
                    fun col(k: String) = try { android.graphics.Color.parseColor(v[k] ?: "#000000") } catch (e: Exception) { android.graphics.Color.BLACK }
                    val look = ScreenLook(col("cBg"), col("cText"), col("cSg"), col("cTemp"), col("cGrid"), col("cDim"), v["font"] ?: "digital", v["graph"] ?: "line",
                        v["plot"] ?: "both", (v["bright"] ?: "100").toIntOrNull() ?: 100, (v["range"] ?: "168").toIntOrNull() ?: 168, "keg" in on, (v["unit"] ?: "C") == "F")
                    ScreenPreview(look, c.graph, page, Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Graph", "Big", "Clock", "Other").forEachIndexed { i, label ->
                            FilterChip(i == page, { page = i }, { Text(label, fontSize = 13.sp, maxLines = 1) }, Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FF.Amber, selectedLabelColor = Color.Black, labelColor = FF.Text))
                        }
                    }
                    Hint("Changes show here straight away; tap Save to send them to the FermFrame.")
                }
                Section("3. Look & feel") {
                    Choice("Theme", listOf("beer" to "Beer (amber on black)", "dark" to "Dark", "light" to "Light", "retro" to "Retro green", "contrast" to "High contrast", "custom" to "My own colours"), v["theme"] ?: "beer") { t ->
                        v["theme"] = t; ClockSettings.THEMES[t]?.forEachIndexed { i, col -> v[ClockSettings.COLOUR_KEYS[i]] = col }
                    }
                    ClockSettings.COLOUR_KEYS.chunked(3).forEachIndexed { row, keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEachIndexed { i, k ->
                                val idx = row * 3 + i
                                Column(Modifier.weight(1f).clickable { pickColour = idx }) {
                                    Text(ClockSettings.COLOUR_NAMES[idx], color = FF.Dim, fontSize = 11.sp)
                                    Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(hex(v[k] ?: "#000000")).border(1.dp, FF.Line, RoundedCornerShape(8.dp)))
                                }
                            }
                        }
                    }
                    Choice("Font", listOf("digital" to "Digital (7-segment)", "modern" to "Modern", "classic" to "Classic", "mono" to "Typewriter"), v["font"] ?: "digital") { v["font"] = it }
                    val bright = (v["bright"] ?: "100").toFloatOrNull() ?: 100f
                    Text("Brightness  ${bright.toInt()}%", color = FF.Dim, fontSize = 13.sp)
                    Slider(bright, { v["bright"] = ((it / 5).toInt() * 5).coerceIn(5, 100).toString() }, valueRange = 5f..100f,
                        colors = SliderDefaults.colors(thumbColor = FF.Amber, activeTrackColor = FF.Amber, inactiveTrackColor = FF.Field))
                    Check("Dim at night", "night" in on) { tick("night", it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Choice("From", TIMES, v["nFrom"] ?: "22:00") { v["nFrom"] = it } }
                        Box(Modifier.weight(1f)) { Choice("To", TIMES, v["nTo"] ?: "07:00") { v["nTo"] = it } }
                    }
                    Choice("Night level", listOf("0" to "Off", "5" to "Very dim", "15" to "Dim", "30" to "Medium"), v["nLevel"] ?: "5") { v["nLevel"] = it }
                    Hint("Night dimming uses the time zone under 6. More.")
                }
                Section("4. Graph") {
                    Choice("Graph style", listOf("line" to "Line", "area" to "Filled area", "dots" to "Dots", "bars" to "Daily bars"), v["graph"] ?: "line") { v["graph"] = it }
                    Choice("Show", listOf("both" to "Gravity and temperature", "sg" to "Gravity only", "temp" to "Temperature only"), v["plot"] ?: "both") { v["plot"] = it }
                    Choice("Time range", listOf("24" to "Last 24 hours", "72" to "Last 3 days", "168" to "Last 7 days", "336" to "Last 14 days", "0" to "Whole batch"), v["range"] ?: "168") { v["range"] = it }
                }
                Section("5. Pages") {
                    Hint("Tick more than one and the FermFrame takes turns showing them.")
                    Check("Graph", "pgGraph" in on) { tick("pgGraph", it) }
                    Check("Big number", "pgBig" in on) { tick("pgBig", it) }
                    Check("Clock (time and date)", "pgClock" in on) { tick("pgClock", it) }
                    Check("My other fermenting brews", "pgBrews" in on) { tick("pgBrews", it) }
                    Choice("Seconds per page", listOf("5", "10", "15", "20", "30", "45", "60", "120", "300", "600").map { it to (if (it.toInt() >= 60) "${it.toInt() / 60} minute${if (it.toInt() >= 120) "s" else ""}" else "$it seconds") }, v["pageSecs"] ?: "10") { v["pageSecs"] = it }
                    Check("Show \"Ready to keg?\" when gravity hasn't moved for 3 days", "keg" in on) { tick("keg", it) }
                }
            }
            Section("6. More") {
                Field("Batch or pill name (blank = the first one)", v["device"] ?: "", { v["device"] = it })
                Radios(listOf("C" to "${FF.Deg}C", "F" to "${FF.Deg}F"), v["unit"] ?: "C") { v["unit"] = it }
                Choice("Time zone", listOf("GMT0BST,M3.5.0/1,M10.5.0" to "UK and Ireland", "CET-1CEST,M3.5.0,M10.5.0/3" to "Central Europe",
                    "EET-2EEST,M3.5.0/3,M10.5.0/4" to "Eastern Europe", "EST5EDT,M3.2.0,M11.1.0" to "US Eastern", "CST6CDT,M3.2.0,M11.1.0" to "US Central",
                    "MST7MDT,M3.2.0,M11.1.0" to "US Mountain", "PST8PDT,M3.2.0,M11.1.0" to "US Pacific", "AEST-10AEDT,M10.1.0,M4.1.0/3" to "Australia (Sydney, Melbourne)",
                    "AEST-10" to "Australia (Brisbane)", "NZST-12NZDT,M9.5.0,M4.1.0/3" to "New Zealand", "SAST-2" to "South Africa", "UTC0" to "UTC"), v["tz"] ?: "") { v["tz"] = it }
                Choice("Check for new readings every", listOf("1", "2", "5", "10", "15", "30", "60", "120").map { it to (if (it.toInt() >= 60) "${it.toInt() / 60} hour${if (it.toInt() >= 120) "s" else ""}" else "$it minute${if (it == "1") "" else "s"}") }, v["refresh"] ?: "5") { v["refresh"] = it }
            }
            Section("7. Settings password (optional)") {
                Hint("Stops anyone else on your Wi-Fi from changing these settings or installing other firmware. The app remembers it, encrypted, on this phone. Forgot it? Plug the FermFrame in 3 times in a row to open Setup mode.")
                Field("Settings password", admin, { admin = it }, password = true, placeholder = if (s.adminSet) "Set – leave blank to keep" else "No password")
                if (s.adminSet) Check("Remove the password", adminOff) { adminOff = it }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (status.isNotEmpty()) Text(status, color = if (status.startsWith("Saved")) FF.Good else if (saving || status.startsWith("Saving")) FF.Dim else FF.Bad, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            PrimaryButton("Save to FermFrame", Modifier.fillMaxWidth(), enabled = !saving) {
                if ((v["ssid"] ?: "").isBlank()) { status = "The Wi-Fi name can't be empty."; return@PrimaryButton }
                if ((v["ssid"] ?: "").trim() != s["ssid"] || pass.isNotEmpty()) confirmWifi = true else doSave()
            }
        }
    }

    if (confirmWifi) AlertDialog(onDismissRequest = { confirmWifi = false }, containerColor = FF.Card,
        title = { Text("Change the Wi-Fi?") },
        text = { Text("The FermFrame will restart and join \"${(v["ssid"] ?: "").trim()}\". If the name or password is wrong it can't come back on your Wi-Fi – you'd fix it by scanning the code on its screen with your phone.") },
        confirmButton = { TextButton({ confirmWifi = false; doSave() }) { Text("Save anyway", color = FF.Amber) } },
        dismissButton = { TextButton({ confirmWifi = false }) { Text("Cancel", color = FF.Dim) } })

    if (pickColour >= 0) {
        val key = ClockSettings.COLOUR_KEYS[pickColour]
        AlertDialog(onDismissRequest = { pickColour = -1 }, containerColor = FF.Card,
            title = { Text(ClockSettings.COLOUR_NAMES[pickColour]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { col -> Box(Modifier.size(36.dp).clip(CircleShape).background(hex(col)).border(2.dp, if (v[key] == col) FF.Amber else FF.Line, CircleShape)
                                .clickable { v[key] = col; v["theme"] = "custom"; pickColour = -1 }) }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ pickColour = -1 }) { Text("Close", color = FF.Dim) } })
    }
}
