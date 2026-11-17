package io.github.fermframe.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/** "Share a brew": draws the brew as a picture and opens Android's share menu. */
object Share {
    private const val W = 1080; private const val H = 1350
    private fun c(color: androidx.compose.ui.graphics.Color) = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())

    fun picture(clock: Clock): Bitmap {
        val g = clock.graph
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(c(FF.Bg))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.textAlign = align
            p.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            cv.drawText(s, x, y, p)
        }
        fun card(l: Float, t: Float, r: Float, b: Float) { p.style = Paint.Style.FILL; p.color = c(FF.Card); cv.drawRoundRect(RectF(l, t, r, b), 36f, 36f, p) }

        text("FermFrame", 60f, 110f, 56f, c(FF.Amber), true)
        text(g?.name?.ifEmpty { null } ?: "My brew", 60f, 200f, 64f, c(FF.Text), true)
        text(clock.name, 60f, 255f, 36f, c(FF.Dim))

        val sg = g?.sg; val og = g?.og
        val tiles = listOf(
            Triple("Gravity", sg?.let { "%.3f".format(it) } ?: "–", og?.let { "Started at %.3f".format(it) } ?: ""),
            Triple("Temperature", g?.temp?.let { "%.1f°C".format(it) } ?: "–", ""),
            Triple("ABV so far", if (sg != null && og != null) "%.1f%%".format((og - sg) * 131.25) else "–",
                if (sg != null && og != null && og > 1) "Attenuation %.0f%%".format((og - sg) / (og - 1) * 100) else ""),
            Triple("Fermenting", g?.day?.let { "Day $it" } ?: "–", if (g?.ready == true) "Ready to keg?" else ""))
        val colors = listOf(c(FF.Amber), c(FF.Blue), c(FF.Text), if (g?.ready == true) c(FF.Good) else c(FF.Text))
        tiles.forEachIndexed { i, (cap, value, sub) ->
            val l = 60f + (i % 2) * 495f; val t = 310f + (i / 2) * 230f
            card(l, t, l + 465f, t + 200f)
            text(cap, l + 34f, t + 60f, 32f, c(FF.Dim))
            text(value, l + 30f, t + 140f, 76f, colors[i], true)
            text(sub, l + 34f, t + 182f, 28f, if (i == 3 && g?.ready == true) c(FF.Good) else c(FF.Dim))
        }

        // the graph
        val gl = 60f; val gt = 790f; val gr = W - 60f; val gb = 1210f
        card(gl, gt, gr, gb)
        fun range(a: List<Double?>?, pad: Double): Pair<Double, Double>? {
            val f = a?.filterNotNull().orEmpty(); if (f.isEmpty()) return null
            val lo = f.min(); val hi = f.max(); val d = maxOf((hi - lo) * 0.08, pad); return (lo - d) to (hi + d)
        }
        val rs = range(g?.s, 0.002); val rt = range(g?.t, 0.3)
        val x0 = gl + 120f; val x1 = gr - 110f; val y0 = gt + 70f; val y1 = gb - 60f
        p.strokeWidth = 2f
        for (k in 0..4) {
            val y = y0 + (y1 - y0) * k / 4f
            p.style = Paint.Style.STROKE; p.color = c(FF.Line); cv.drawLine(x0, y, x1, y, p)
            rs?.let { text("%.3f".format(it.second - (it.second - it.first) * k / 4), x0 - 14f, y + 10f, 26f, c(FF.Amber), align = Paint.Align.RIGHT) }
            rt?.let { text("%.1f°".format(it.second - (it.second - it.first) * k / 4), x1 + 14f, y + 10f, 26f, c(FF.Blue)) }
        }
        fun plot(a: List<Double?>?, r: Pair<Double, Double>?, color: Int, width: Float) {
            if (a == null || r == null || a.isEmpty()) return
            val path = Path(); var on = false
            a.forEachIndexed { i, v ->
                if (v == null) { on = false; return@forEachIndexed }
                val x = x0 + (x1 - x0) * i / maxOf(1, a.size - 1); val y = (y1 - (v - r.first) / (r.second - r.first) * (y1 - y0)).toFloat()
                if (on) path.lineTo(x, y) else { path.moveTo(x, y); on = true }
            }
            p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            cv.drawPath(path, p)
        }
        plot(g?.t, rt, c(FF.Blue), 4f); plot(g?.s, rs, c(FF.Amber), 7f)
        text("Gravity", gl + 120f, gt + 46f, 28f, c(FF.Amber), true)
        text("Temperature", gl + 260f, gt + 46f, 28f, c(FF.Blue), true)

        text("Made with FermFrame – free and open source", W / 2f, 1280f, 30f, c(FF.Dim), align = Paint.Align.CENTER)
        text("github.com/ammcknockiter-cyber/FermFrame", W / 2f, 1320f, 28f, c(FF.Dim), align = Paint.Align.CENTER)
        return bmp
    }

    fun share(ctx: Context, clock: Clock) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "my-brew.png")
        file.outputStream().use { picture(clock).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, (clock.graph?.name?.ifEmpty { null } ?: "My brew") + " – on my FermFrame").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share your brew").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
