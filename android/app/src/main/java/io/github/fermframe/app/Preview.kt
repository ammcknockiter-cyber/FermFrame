package io.github.fermframe.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A picture of the FermFrame's 240x240 screen, drawn the way the firmware draws it (same as the Windows app's preview):
 * theme colours in 16-bit colour, the 7-segment "Digital" font, graph styles and the four pages.
 */
class ScreenLook(
    val bg: Int, val text: Int, val sg: Int, val temp: Int, val grid: Int, val dim: Int,
    val font: String, val graph: String, val plot: String, val bright: Int, val rangeH: Int, val keg: Boolean, val fahrenheit: Boolean,
)

object ScreenPainter {
    private const val NB = 88; private const val GX0 = 32f; private const val GX1 = 208f; private const val GY0 = 98f; private const val GY1 = 222f
    private const val C_ERR = 0xFFFF3431.toInt(); private const val C_OK = 0xFF5AFF52.toInt()

    private fun c565(c: Int): Int { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return (0xFF shl 24) or (((r and 0xF8) or (r shr 5)) shl 16) or (((g and 0xFC) or (g shr 6)) shl 8) or ((b and 0xF8) or (b shr 5)) }
    private fun blend(a: Int, fg: Int, bg: Int): Int { fun ch(s: Int) = (((fg shr s) and 255) * a + ((bg shr s) and 255) * (255 - a)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0) }
    private fun fmt(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    private enum class Kind { F1, F2, F4, SEG7, HUGE, BIG, MID }

    private class Ctx(val cv: Canvas, val look: ScreenLook) { val p = Paint(Paint.ANTI_ALIAS_FLAG) }

    private fun txt(x: Ctx, s: String, px: Float, py: Float, datum: String, col: Int, kind0: Kind) {
        if (s.isEmpty()) return
        var k = kind0
        if (k == Kind.BIG && x.look.font == "digital") k = Kind.SEG7
        if (k == Kind.MID && x.look.font == "digital") k = Kind.F4
        val v = datum[0]; val h = datum[1]
        if (k == Kind.SEG7) { seg7(x, s, px, py, h, v, col); return }
        val p = x.p; p.style = Paint.Style.FILL; p.color = col
        var cellH = 0f; var pad = 0f
        when (k) {
            Kind.F1 -> { p.typeface = Typeface.MONOSPACE; p.textSize = 11f; cellH = 8f; pad = 1f }
            Kind.F2 -> { p.typeface = Typeface.SANS_SERIF; p.textSize = 15f; cellH = 16f; pad = 3f }
            Kind.F4 -> { p.typeface = Typeface.SANS_SERIF; p.textSize = 25f; cellH = 26f; pad = 4f }
            Kind.HUGE -> { p.typeface = Typeface.SANS_SERIF; p.textSize = 104f; cellH = 75f; pad = 4f }
            else -> {
                val big = k == Kind.BIG
                p.typeface = when (x.look.font) { "classic" -> Typeface.create(Typeface.SERIF, Typeface.BOLD); "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                p.textSize = if (big) 47f else 35f
            }
        }
        val r = Rect(); p.getTextBounds("0", 0, 1, r); val dh = r.height().toFloat()
        val top = if (cellH > 0) when (v) { 'T' -> py + pad; 'M' -> py - dh / 2; else -> py - pad - dh } else when (v) { 'T' -> py; 'M' -> py - dh / 2; else -> py - dh }
        val w = p.measureText(s)
        val left = when (h) { 'L' -> px; 'R' -> px - w; else -> px - w / 2 }
        x.cv.drawText(s, left, top + dh, p)
    }

    // TFT_eSPI font 7: 7-segment digits, 32 px wide and 48 px high
    private fun seg7(x: Ctx, s: String, px: Float, py: Float, h: Char, v: Char, col: Int) {
        fun adv(c: Char) = if (c == '.' || c == ':' || c == ' ') 12f else 32f
        val w = s.sumOf { adv(it).toDouble() }.toFloat()
        var left = when (h) { 'L' -> px; 'R' -> px - w; else -> px - w / 2 }
        val top = when (v) { 'T' -> py; 'M' -> py - 24; else -> py - 48 }
        val p = x.p; p.style = Paint.Style.FILL; p.color = col
        val digits = arrayOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")
        for (c in s) {
            when {
                c == '.' -> x.cv.drawRect(left + 3, top + 42, left + 9, top + 48, p)
                c == ':' -> { x.cv.drawRect(left + 3, top + 12, left + 9, top + 18, p); x.cv.drawRect(left + 3, top + 30, left + 9, top + 36, p) }
                c == '-' -> segs(x, left, top, "g")
                c in '0'..'9' -> segs(x, left, top, digits[c - '0'])
            }
            left += adv(c)
        }
    }

    private fun segs(x: Ctx, sx: Float, sy: Float, on: String) {
        val t = 6f; val l = sx + 5; val r = sx + 25; val t0 = sy + 1; val m = sy + 24; val b = sy + 47; val gap = 1f
        fun poly(vararg pts: Float) { val path = Path(); path.moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }; path.close(); x.cv.drawPath(path, x.p) }
        fun hz(x0: Float, x1: Float, yc: Float) = poly(x0, yc, x0 + t / 2, yc - t / 2, x1 - t / 2, yc - t / 2, x1, yc, x1 - t / 2, yc + t / 2, x0 + t / 2, yc + t / 2)
        fun vt(xc: Float, y0: Float, y1: Float) = poly(xc, y0, xc + t / 2, y0 + t / 2, xc + t / 2, y1 - t / 2, xc, y1, xc - t / 2, y1 - t / 2, xc - t / 2, y0 + t / 2)
        for (c in on) when (c) {
            'a' -> hz(l + gap, r - gap, t0 + t / 2); 'd' -> hz(l + gap, r - gap, b - t / 2); 'g' -> hz(l + gap, r - gap, m)
            'f' -> vt(l, t0 + t / 2 + gap, m - gap); 'b' -> vt(r, t0 + t / 2 + gap, m - gap)
            'e' -> vt(l, m + gap, b - t / 2 - gap); 'c' -> vt(r, m + gap, b - t / 2 - gap)
        }
    }

    /** Draws the screen (240x240 units) for `page`: 0 graph, 1 big number, 2 clock, 3 another brew. */
    fun draw(cv: Canvas, look: ScreenLook, g0: Graph?, page: Int) {
        val ex = g0 == null || g0.s.none { it != null }
        val s: List<Double?>; val t: List<Double?>; val name: String; val sg: Double?; val tmp: Double?; val og: Double?; val bat: Int; val day: Int; val ready: Boolean
        if (ex) {   // the same made-up brew the FermFrame's own web page uses
            s = (0 until NB).map { 1.012 + 0.038 / (1 + exp((it - 30) / 8.0)) }; t = (0 until NB).map { 18 + 1.2 * sin(it / 9.0) }
            name = "Example Pale Ale"; sg = s.last(); tmp = t.last(); og = 1.050; bat = 57; day = 6; ready = false
        } else { s = g0!!.s; t = g0.t; name = g0.name; sg = g0.sg; tmp = g0.temp; og = g0.og; bat = g0.battery?.toInt() ?: -1; day = g0.day ?: 0; ready = g0.ready }
        val x = Ctx(cv, look)
        val bg = c565(look.bg); val text = c565(look.text); val csg = c565(look.sg); val ctemp = c565(look.temp); val grid = c565(look.grid); val dim = c565(look.dim)
        cv.drawColor(bg)
        fun showT(c: Double) = if (look.fahrenheit) c * 9 / 5 + 32 else c
        fun sgText(v: Double?) = v?.let { fmt(it, 3) } ?: "-.---"
        fun tText(v: Double?) = v?.let { fmt(showT(it), 1) } ?: "--"
        fun header(n: String, b: Int) { txt(x, n.ifEmpty { "FermFrame" }.take(22), 4f, 2f, "TL", text, Kind.F2); if (b >= 0) txt(x, "$b%", 236f, 2f, "TR", if (b < 20) C_ERR else dim, Kind.F2) }
        val abv = if (og != null && sg != null) max(0.0, (og - sg) * 131.25) else null
        when (page) {
            1 -> {
                header(name, bat); txt(x, "GRAVITY", 120f, 30f, "TC", dim, Kind.F2); txt(x, sgText(sg), 120f, 88f, "MC", csg, Kind.HUGE)
                txt(x, tText(tmp), 64f, 150f, "MC", ctemp, Kind.MID); txt(x, abv?.let { fmt(it, 1) } ?: "--", 176f, 150f, "MC", text, Kind.MID)
                txt(x, if (look.fahrenheit) "temp F" else "temp C", 64f, 174f, "TC", dim, Kind.F2); txt(x, "% ABV", 176f, 174f, "TC", dim, Kind.F2)
                if (look.keg && ready) txt(x, "Ready to keg?", 120f, 200f, "TC", C_OK, Kind.F4) else if (day > 0) txt(x, "Day $day", 120f, 200f, "TC", text, Kind.F4)
                txt(x, "5m ago", 120f, 226f, "TC", dim, Kind.F2)
            }
            2 -> {
                val now = Date()
                txt(x, SimpleDateFormat("HH:mm", Locale.US).format(now), 120f, 84f, "MC", text, Kind.HUGE)
                txt(x, SimpleDateFormat("EEE d MMM", Locale.US).format(now), 120f, 150f, "MC", dim, Kind.MID)
                x.p.color = grid; x.p.strokeWidth = 1f; cv.drawLine(20f, 186f, 220f, 186f, x.p)
                txt(x, name.ifEmpty { "FermFrame" }.take(16), 120f, 196f, "TC", text, Kind.F2)
                txt(x, sgText(sg) + "    " + tText(tmp) + if (look.fahrenheit) " F" else " C", 120f, 216f, "TC", csg, Kind.F2)
            }
            3 -> {
                header("Your other brew", -1); txt(x, "GRAVITY", 120f, 30f, "TC", dim, Kind.F2); txt(x, "1.024", 120f, 88f, "MC", csg, Kind.HUGE)
                txt(x, fmt(showT(19.5), 1), 120f, 150f, "MC", ctemp, Kind.MID); txt(x, if (look.fahrenheit) "temp F" else "temp C", 120f, 174f, "TC", dim, Kind.F2)
                txt(x, "12m ago", 120f, 204f, "TC", dim, Kind.F2); txt(x, "Brew 2 of 2", 120f, 224f, "TC", dim, Kind.F2)
            }
            else -> {
                header(name, bat)
                txt(x, sgText(sg), 4f, 22f, "TL", csg, Kind.BIG)
                txt(x, tText(tmp), 226f, 24f, "TR", ctemp, Kind.MID)
                x.p.style = Paint.Style.STROKE; x.p.color = ctemp; x.p.strokeWidth = 1f; cv.drawCircle(231f, 29f, 2f, x.p)
                abv?.let { txt(x, fmt(it, 1) + "% ABV", 236f, 56f, "TR", text, Kind.F2) }
                if (look.keg && ready) { txt(x, "Ready to keg?", 4f, 78f, "TL", C_OK, Kind.F2); txt(x, "5m ago", 236f, 78f, "TR", dim, Kind.F2) }
                else txt(x, (if (day > 0) "Day $day  " else "") + (og?.let { "OG " + fmt(it, 3) + "  " } ?: "") + "5m ago", 4f, 78f, "TL", dim, Kind.F2)
                val wantSg = look.plot != "temp"; val wantT = look.plot != "sg"
                val tv = t.map { it?.let(::showT) }
                val sf = s.filterNotNull(); val tf = tv.filterNotNull()
                val haveSg = wantSg && sf.isNotEmpty(); val haveT = wantT && tf.isNotEmpty()
                x.p.style = Paint.Style.STROKE; x.p.color = grid; x.p.strokeWidth = 1f
                for (k in 0..4) { val yy = GY0 + (GY1 - GY0) * k / 4; cv.drawLine(GX0, yy, GX1, yy, x.p) }
                val span = if (look.rangeH > 0) look.rangeH * 3600L else max(1, if (day > 0) day else 7) * 86400L
                val step = if (span <= 2 * 86400L) 6 * 3600L else if (span <= 16 * 86400L) 86400L else 7 * 86400L
                var st = step; while (st < span) { val xx = GX1 - ((GX1 - GX0) * st.toDouble() / span).toFloat(); cv.drawLine(xx, GY0, xx, GY1, x.p); st += step }
                if (!haveSg && !haveT) { txt(x, "No readings yet in this range", 120f, (GY0 + GY1) / 2, "MC", dim, Kind.F2); return }
                var sMin = 0.0; var sMax = 0.0; var tMin = 0.0; var tMax = 0.0
                if (haveSg) { sMin = sf.min(); sMax = sf.max(); if (sMax - sMin < 0.004) { val mm = (sMax + sMin) / 2; sMin = mm - 0.002; sMax = mm + 0.002 }; val pd = (sMax - sMin) * 0.08; sMin -= pd; sMax += pd }
                if (haveT) { tMin = tf.min(); tMax = tf.max(); if (tMax - tMin < 2) { val mm = (tMax + tMin) / 2; tMin = mm - 1; tMax = mm + 1 }; val pd = (tMax - tMin) * 0.08; tMin -= pd; tMax += pd }
                if (haveSg) for (k in 0..4 step 2) { val vv = sMax - (sMax - sMin) * k / 4; val pts = min(999, max(0, ((vv - 1.0) * 1000).roundToInt())); txt(x, "%03d".format(pts), GX0 - 3, GY0 + (GY1 - GY0) * k / 4, "MR", csg, Kind.F1) }
                if (haveT) for (k in 0..4 step 2) txt(x, fmt(tMax - (tMax - tMin) * k / 4, 1), GX1 + 3, GY0 + (GY1 - GY0) * k / 4, "ML", ctemp, Kind.F1)
                fun yOf(v: Double, lo: Double, hi: Double) = GY1 - ((v - lo) / (hi - lo) * (GY1 - GY0)).toFloat()
                val n = min(NB, s.size)
                if (haveSg && look.graph == "bars") {
                    val barSecs = if (span <= 2 * 86400L) 2 * 3600L else 86400L
                    val nBars = max(1L, min(NB.toLong(), (span + barSecs - 1) / barSecs)).toInt()
                    for (b in 0 until nBars) {
                        val i0 = b * NB / nBars; val i1 = (b + 1) * NB / nBars
                        val vals = (i0 until min(i1, n)).mapNotNull { s[it] }; if (vals.isEmpty()) continue
                        val x0 = GX0 + i0 * 2 + 1; val w = max(2, (i1 - i0) * 2 - 2).toFloat(); val yy = yOf(vals.average(), sMin, sMax)
                        x.p.style = Paint.Style.FILL; x.p.color = blend(170, csg, bg); cv.drawRect(x0, yy, x0 + w, GY1, x.p)
                        x.p.style = Paint.Style.STROKE; x.p.color = csg; cv.drawLine(x0, yy, x0 + w, yy, x.p)
                    }
                } else if (haveSg) {
                    val pts = (0 until n).mapNotNull { i -> s[i]?.let { (GX0 + i * 2 + 1) to yOf(it, sMin, sMax) } }
                    if (look.graph == "dots") { x.p.style = Paint.Style.FILL; x.p.color = csg; pts.forEach { (px, py) -> cv.drawRect(px - 1, py - 1, px + 2, py + 2, x.p) } }
                    else {
                        if (look.graph == "area" && pts.size > 1) {
                            val path = Path(); path.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { path.lineTo(it.first, it.second) }
                            path.lineTo(pts.last().first, GY1); path.lineTo(pts[0].first, GY1); path.close()
                            x.p.style = Paint.Style.FILL; x.p.color = blend(80, csg, bg); cv.drawPath(path, x.p)
                        }
                        if (pts.size > 1) { val path = Path(); path.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { path.lineTo(it.first, it.second) }
                            x.p.style = Paint.Style.STROKE; x.p.color = csg; x.p.strokeWidth = 2f; cv.drawPath(path, x.p) }
                    }
                }
                if (haveT) {
                    val pts = (0 until min(NB, tv.size)).mapNotNull { i -> tv[i]?.let { (GX0 + i * 2 + 1) to yOf(it, tMin, tMax) } }
                    if (look.graph == "dots") { x.p.style = Paint.Style.FILL; x.p.color = ctemp; pts.forEach { (px, py) -> cv.drawRect(px - 1, py - 1, px + 1, py + 1, x.p) } }
                    else if (pts.size > 1) { val path = Path(); path.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { path.lineTo(it.first, it.second) }
                        x.p.style = Paint.Style.STROKE; x.p.color = ctemp; x.p.strokeWidth = 1f; cv.drawPath(path, x.p) }
                }
                txt(x, if (look.rangeH == 0) "start" else if (look.rangeH < 48) "-24h" else "-${look.rangeH / 24}d", GX0, 240f, "BL", dim, Kind.F1)
                txt(x, "now", GX1, 240f, "BR", dim, Kind.F1)
                if (haveSg) txt(x, "SG", 70f, 240f, "BC", csg, Kind.F1)
                if (haveT) txt(x, if (look.fahrenheit) "Temp F" else "Temp C", 160f, 240f, "BC", ctemp, Kind.F1)
            }
        }
        // brightness: the backlight dims the whole screen
        if (look.bright < 100) { x.p.style = Paint.Style.FILL; x.p.color = android.graphics.Color.argb(((100 - look.bright) * 2.2).toInt(), 0, 0, 0); cv.drawRect(0f, 0f, 240f, 240f, x.p) }
    }
}

/** The clock's body with its screen, sized to fit. */
@Composable
fun ScreenPreview(look: ScreenLook, graph: Graph?, page: Int, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFF343434)).drawBehind {
        val side = min(size.width, size.height); val bez = side * 0.045f; val scr = side - 2 * bez
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save(); nc.translate(bez, bez); nc.scale(scr / 240f, scr / 240f); nc.clipRect(RectF(0f, 0f, 240f, 240f))
            ScreenPainter.draw(nc, look, graph, page)
            nc.restore()
        }
    })
}
