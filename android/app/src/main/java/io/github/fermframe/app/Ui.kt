package io.github.fermframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The same colours as the Windows app. */
object FF {
    val Bg = Color(0xFF141414)
    val Card = Color(0xFF242424)
    val Field = Color(0xFF2E2E2E)
    val Line = Color(0xFF3A3A3A)
    val Amber = Color(0xFFFFCC33)
    val Blue = Color(0xFF5BBEFF)
    val Text = Color(0xFFE8E8E8)
    val Dim = Color(0xFF969696)
    val Good = Color(0xFF6EDC78)
    val Bad = Color(0xFFFF806E)
    const val Deg = "°"
    const val Dot = "  ·  "
}

@Composable
fun FermFrameTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FF.Amber, onPrimary = Color.Black, background = FF.Bg, onBackground = FF.Text,
            surface = FF.Card, onSurface = FF.Text, surfaceVariant = FF.Field, onSurfaceVariant = FF.Dim, outline = FF.Line,
        ),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FF.Amber, contentColor = Color.Black)) { Text(text, fontWeight = FontWeight.Bold) }

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = FF.Field, contentColor = FF.Text),
        border = androidx.compose.foundation.BorderStroke(1.dp, FF.Line)) { Text(text) }

// ------------------------------------------------------------------ the list of FermFrames

@Composable
fun HomeScreen(m: AppModel) {
    Column(Modifier.fillMaxSize().background(FF.Bg).systemBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FermFrame", color = FF.Amber, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("My FermFrames", color = FF.Dim, fontSize = 15.sp)
            }
            SecondaryButton("🔔 Alerts") { m.showAlerts = true }
        }
        Spacer(Modifier.height(14.dp))
        m.update?.let { UpdateCard(it) { m.dismissUpdate() }; Spacer(Modifier.height(10.dp)) }
        if (m.clocks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (m.busy) "Looking for FermFrames on your Wi-Fi..." else "No FermFrames yet.\n\nTap \"Find my FermFrames\" to look for them on your Wi-Fi.",
                    color = FF.Dim, fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(m.clocks, key = { it.mac }) { c -> ClockCard(c) { m.selected = c.mac } }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = FF.Amber, trackColor = FF.Field)
        Text(m.status, color = FF.Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Find my FermFrames", Modifier.weight(1f), enabled = !m.busy) { m.refresh(scan = true) }
            SecondaryButton("+ Set up new", Modifier.weight(1f)) { m.showSetup = true }
        }
        TextButton({ m.checkForUpdate(force = true) }, Modifier.align(Alignment.CenterHorizontally)) {
            Text("FermFrame app ${BuildConfigVersion.name}  ·  Check for updates", color = FF.Dim, fontSize = 12.sp)
        }
    }
}

@Composable
fun ClockCard(c: Clock, onClick: () -> Unit) {
    val dot = when { !c.online -> Color(0xFF6E6E6E); c.problem.isNotEmpty() -> FF.Bad; else -> FF.Good }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = FF.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val line = when { !c.online -> "Not answering" + if (c.ip.isNotEmpty()) FF.Dot + c.ip else ""
                c.problem.isNotEmpty() -> c.problem
                else -> c.graph?.name?.takeIf { it.isNotEmpty() } ?: ("On Wi-Fi" + FF.Dot + c.ip) }
            Text(line, color = if (c.online && c.problem.isNotEmpty()) FF.Bad else FF.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        c.graph?.sg?.takeIf { c.online }?.let {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("%.3f".format(it), color = FF.Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                c.graph.temp?.let { t -> Text("%.1f%sC".format(t, FF.Deg), color = FF.Blue, fontSize = 13.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ one FermFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(m: AppModel, c: Clock) {
    var tab by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(FF.Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { m.selected = null }) { Text("←", color = FF.Text, fontSize = 24.sp) }
            Column {
                Text(c.name, color = FF.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (c.online) "FermFrame ${c.version}${FF.Dot}${c.ip}" else "Not answering" + if (c.ip.isNotEmpty()) " at ${c.ip}" else "",
                    color = if (c.online) FF.Dim else FF.Bad, fontSize = 13.sp)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Overview", "Settings", "Firmware").forEachIndexed { i, name ->
                if (i == tab) PrimaryButton(name, Modifier.weight(1f)) { tab = i } else SecondaryButton(name, Modifier.weight(1f)) { tab = i }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Overview(m, c, onRename = { renaming = true }, onRemove = { removing = true })
                1 -> SettingsTab(m, c)
                else -> FirmwareTab(m, c)
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(c.name) }
        AlertDialog(onDismissRequest = { renaming = false }, containerColor = FF.Card,
            title = { Text("Rename FermFrame") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name, e.g. Garage fridge") }) },
            confirmButton = { TextButton({ m.rename(c.mac, text); renaming = false }) { Text("OK", color = FF.Amber) } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel", color = FF.Dim) } })
    }
    if (removing) {
        AlertDialog(onDismissRequest = { removing = false }, containerColor = FF.Card,
            title = { Text("Remove ${c.name}?") },
            text = { Text("Nothing changes on the FermFrame. If it's still on your Wi-Fi, Find my FermFrames will add it back.") },
            confirmButton = { TextButton({ m.remove(c.mac); removing = false }) { Text("Remove", color = FF.Amber) } },
            dismissButton = { TextButton({ removing = false }) { Text("Cancel", color = FF.Dim) } })
    }
}

@Composable
fun Overview(m: AppModel, c: Clock, onRename: () -> Unit, onRemove: () -> Unit) {
    val g = c.graph
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(g?.name?.takeIf { it.isNotEmpty() } ?: if (c.online) "No brew showing" else "", color = FF.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        val brews = c.info?.optInt("brews") ?: 0
        if (brews > 1) Text("${brews - 1} other brew${if (brews > 2) "s" else ""} fermenting too", color = FF.Dim, fontSize = 13.sp)
        if (c.online && c.problem.isNotEmpty()) Text("The FermFrame says: ${c.problem}", color = FF.Bad, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        val sg = g?.sg; val og = g?.og
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Gravity", sg?.let { "%.3f".format(it) } ?: "-", og?.let { "Started at %.3f".format(it) } ?: "", FF.Amber, Modifier.weight(1f))
            Tile("Temperature", g?.temp?.let { "%.1f%sC".format(it, FF.Deg) } ?: "-", g?.battery?.let { "Battery %.0f%%".format(it) } ?: "", FF.Blue, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val abv = if (sg != null && og != null) "%.1f%%".format((og - sg) * 131.25) else "-"
            val att = if (sg != null && og != null && og > 1) "Attenuation %.0f%%".format((og - sg) / (og - 1) * 100) else ""
            Tile("ABV so far", abv, att, FF.Text, Modifier.weight(1f))
            Tile("Fermenting", g?.day?.let { "Day $it" } ?: "-", if (g?.ready == true) "Ready to keg?" else "", if (g?.ready == true) FF.Good else FF.Text, Modifier.weight(1f),
                subColor = if (g?.ready == true) FF.Good else FF.Dim)
        }
        Spacer(Modifier.height(12.dp))
        GraphView(g, Modifier.fillMaxWidth().height(260.dp))
        Spacer(Modifier.height(8.dp))
        c.info?.let {
            Text("Wi-Fi ${Html.decode(it.optString("wifi"))}${FF.Dot}${it.optInt("readings")} readings${FF.Dot}checked " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.checkedAt)), color = FF.Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (c.online && g != null) PrimaryButton("Share this brew", Modifier.fillMaxWidth()) { Share.share(ctx, c) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Refresh", Modifier.weight(1f), enabled = !m.busy) { m.refresh() }
            SecondaryButton("Rename", Modifier.weight(1f)) { onRename() }
            SecondaryButton("Remove", Modifier.weight(1f)) { onRemove() }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun Tile(caption: String, value: String, sub: String, accent: Color, modifier: Modifier, subColor: Color = FF.Dim) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(14.dp)) {
        Text(caption, color = FF.Dim, fontSize = 13.sp)
        Text(value, color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = subColor, fontSize = 12.sp, maxLines = 1)
    }
}

/** Gravity (amber, left scale) and temperature (blue, right scale), like the Windows app. */
@Composable
fun GraphView(g: Graph?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(FF.Card)) {
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx() }
        fun range(a: List<Double?>, minPad: Double): Pair<Double, Double>? {
            val f = a.filterNotNull(); if (f.isEmpty()) return null
            val lo = f.min(); val hi = f.max(); val p = maxOf((hi - lo) * 0.08, minPad)
            return (lo - p) to (hi + p)
        }
        val rs = g?.let { range(it.s, 0.002) }
        val rt = g?.let { range(it.t, 0.3) }
        if (rs == null && rt == null) {
            paint.color = FF.Dim.toArgb(); paint.textAlign = android.graphics.Paint.Align.CENTER; paint.textSize = 14.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText("No readings to draw yet", size.width / 2, size.height / 2, paint); return@Canvas
        }
        val x0 = 52.dp.toPx(); val x1 = size.width - 46.dp.toPx(); val y0 = 34.dp.toPx(); val y1 = size.height - 26.dp.toPx()
        val nc = drawContext.canvas.nativeCanvas
        for (k in 0..4) {
            val y = y0 + (y1 - y0) * k / 4f
            drawLine(FF.Line, Offset(x0, y), Offset(x1, y), 1.dp.toPx())
            rs?.let { paint.color = FF.Amber.toArgb(); paint.textAlign = android.graphics.Paint.Align.RIGHT; nc.drawText("%.3f".format(it.second - (it.second - it.first) * k / 4), x0 - 6.dp.toPx(), y + 4.dp.toPx(), paint) }
            rt?.let { paint.color = FF.Blue.toArgb(); paint.textAlign = android.graphics.Paint.Align.LEFT; nc.drawText("%.1f%s".format(it.second - (it.second - it.first) * k / 4, FF.Deg), x1 + 6.dp.toPx(), y + 4.dp.toPx(), paint) }
        }
        paint.color = FF.Dim.toArgb(); paint.textAlign = android.graphics.Paint.Align.LEFT
        nc.drawText("earlier", x0, y1 + 18.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT; nc.drawText("now", x1, y1 + 18.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.LEFT
        var lx = x0
        rs?.let { drawRect(FF.Amber, Offset(lx, 12.dp.toPx()), Size(9.dp.toPx(), 9.dp.toPx())); paint.color = FF.Text.toArgb(); nc.drawText("Gravity", lx + 14.dp.toPx(), 21.dp.toPx(), paint); lx += 80.dp.toPx() }
        rt?.let { drawRect(FF.Blue, Offset(lx, 12.dp.toPx()), Size(9.dp.toPx(), 9.dp.toPx())); paint.color = FF.Text.toArgb(); nc.drawText("Temperature", lx + 14.dp.toPx(), 21.dp.toPx(), paint) }
        fun plot(a: List<Double?>, r: Pair<Double, Double>, color: Color, width: Float) {
            val n = a.size; if (n == 0) return
            val path = Path(); var drawing = false
            a.forEachIndexed { i, v ->
                if (v == null) { drawing = false; return@forEachIndexed }
                val x = if (n == 1) x1 else x0 + (x1 - x0) * i / (n - 1)
                val y = (y1 - (v - r.first) / (r.second - r.first) * (y1 - y0)).toFloat()
                if (drawing) path.lineTo(x, y) else { path.moveTo(x, y); drawing = true }
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (g != null) {
            rt?.let { plot(g.t, it, FF.Blue, 1.6.dp.toPx()) }
            rs?.let { plot(g.s, it, FF.Amber, 2.6.dp.toPx()) }
        }
    }
}
