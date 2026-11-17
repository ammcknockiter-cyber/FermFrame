package io.github.fermframe.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(m: AppModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(with(AlertPrefs) { ctx.alertsEnabled }) }
    var allowed by remember { mutableStateOf(Alerts.canNotify(ctx)) }
    var useF by remember { mutableStateOf(AlertPrefs.useF(ctx)) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }   // redraw after changes stored in prefs

    fun setEnabled(v: Boolean) { enabled = v; with(AlertPrefs) { ctx.alertsEnabled = v }; Alerts.schedule(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted; if (granted) setEnabled(true) else status = "Android won't show FermFrame alerts until you allow notifications for FermFrame in the phone's settings."
    }

    Column(Modifier.fillMaxSize().background(FF.Bg).systemBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("←", color = FF.Text, fontSize = 24.sp) }
            Text("Alerts", color = FF.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Alerts on this phone", color = FF.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Checks your FermFrames every 15 minutes while this phone is on your home Wi-Fi. Away from home, you'll hear once you're back.", color = FF.Dim, fontSize = 13.sp)
                    }
                    Switch(enabled, { v ->
                        if (v && !Alerts.canNotify(ctx) && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else setEnabled(v)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = FF.Amber))
                }
            }
            if (enabled) {
                key(tick) {
                    Card {
                        Text("Tell me when", color = FF.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Toggle("A brew looks ready to keg", "Gravity hasn't moved for 3 days", AlertPrefs.READY) { tick++ }
                        Toggle("The temperature leaves its range", "Set the range for each FermFrame below", AlertPrefs.TEMP) { tick++ }
                        Toggle("A FermFrame reports a problem", "For example a wrong key or no Fermenting batch", AlertPrefs.PROBLEM) { tick++ }
                        Toggle("A FermFrame stops answering", "Not answering for over 30 minutes", AlertPrefs.OFFLINE) { tick++ }
                    }
                    if (AlertPrefs.on(ctx, AlertPrefs.TEMP)) Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Temperature ranges", color = FF.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            listOf(false to "${FF.Deg}C", true to "${FF.Deg}F").forEach { (f, label) ->
                                FilterChip(useF == f, { useF = f; AlertPrefs.setUseF(ctx, f) }, { Text(label) }, Modifier.padding(start = 6.dp),
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FF.Amber, selectedLabelColor = Color.Black))
                            }
                        }
                        if (m.clocks.isEmpty()) Text("No FermFrames yet.", color = FF.Dim)
                        m.clocks.forEach { c -> RangeRow(c.mac, c.name, useF) }
                    }
                }
                SecondaryButton("Check now", Modifier.fillMaxWidth()) {
                    status = "Checking..."
                    scope.launch { withContext(Dispatchers.IO) { Alerts.checkAll(ctx) }; status = "Checked. Anything new has been sent as an alert." }
                }
            }
            if (!allowed && enabled) Text("Notifications are switched off for FermFrame in the phone's settings, so alerts can't appear.", color = FF.Bad, fontSize = 13.sp)
            if (status.isNotEmpty()) Text(status, color = FF.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun Toggle(title: String, sub: String, kind: String, changed: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AlertPrefs.on(ctx, kind)) }
    Row(Modifier.fillMaxWidth().clickable { on = !on; AlertPrefs.setOn(ctx, kind, on); changed() }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = FF.Text, fontSize = 15.sp); Text(sub, color = FF.Dim, fontSize = 12.sp) }
        Switch(on, { on = it; AlertPrefs.setOn(ctx, kind, it); changed() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = FF.Amber))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeRow(mac: String, name: String, useF: Boolean) {
    val ctx = LocalContext.current
    var range by remember(mac) { mutableStateOf(AlertPrefs.range(ctx, mac)) }
    fun show(c: Double) = if (useF) "%.0f${FF.Deg}F".format(c * 9 / 5 + 32) else "%.0f${FF.Deg}C".format(c)
    val temps = (0..35).map { it.toDouble() }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = FF.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(range != null, { on -> range = if (on) 17.0 to 21.0 else null; AlertPrefs.setRange(ctx, mac, range) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = FF.Amber))
        }
        range?.let { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Picker(Modifier.weight(1f), "Lowest", temps.filter { it < r.second }, r.first, ::show) { range = it to r.second; AlertPrefs.setRange(ctx, mac, range) }
                Picker(Modifier.weight(1f), "Highest", temps.filter { it > r.first }, r.second, ::show) { range = r.first to it; AlertPrefs.setRange(ctx, mac, range) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(modifier: Modifier, label: String, values: List<Double>, value: Double, show: (Double) -> String, onPick: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(show(value), {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
            ExposedDropdownMenu(open, { open = false }, containerColor = FF.Field) {
                values.forEach { v -> DropdownMenuItem(text = { Text(show(v)) }, onClick = { onPick(v); open = false }) }
            }
        }
    }
}
