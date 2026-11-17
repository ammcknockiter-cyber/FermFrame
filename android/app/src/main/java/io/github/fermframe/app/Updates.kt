package io.github.fermframe.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Looks for a newer FermFrame app on GitHub (the same releases as the Windows app).
 * A release counts when it has the file FermFrame-<version>.apk. The phone downloads it in the
 * browser and Android installs it over this one (same signature), keeping everything.
 */
object Updates {
    private const val FEED = "https://api.github.com/repos/ammcknockiter-cyber/FermFrame/releases/latest"
    private const val PREFS = "fermframe-updates"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L

    class Release(val version: String, val apkUrl: String, val page: String)

    fun appVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** The newer release, or null (nothing newer, already skipped, checked recently, or no internet). */
    fun check(ctx: Context, force: Boolean = false): Release? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked", 0) < EVERY_MS) return p.getString("found", null)?.let { parse(ctx, it, p) }
        val r = Net.get(FEED, 10000)
        if (r.status != 200) return null
        p.edit().putLong("checked", now).putString("found", r.body).apply()
        return parse(ctx, r.body, p, force)
    }

    private fun parse(ctx: Context, body: String, p: android.content.SharedPreferences, force: Boolean = false): Release? {
        val j = Net.json(body) ?: return null
        val ver = j.optString("tag_name").trimStart('v', 'V')
        if (ver.isEmpty() || !Firmware.older(appVersion(ctx), ver)) return null
        if (!force && p.getString("skip", "") == ver) return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").equals("FermFrame-$ver.apk", ignoreCase = true))
                return Release(ver, a.optString("browser_download_url"), j.optString("html_url"))
        }
        return null
    }

    fun skip(ctx: Context, version: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skip", version).apply()
}

@Composable
fun UpdateCard(r: Updates.Release, onLater: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FF.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("FermFrame app ${r.version} is out", color = FF.Amber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("You have ${Updates.appVersion(ctx)}. Tap Download, then open the file to install it - your FermFrames and settings stay.", color = FF.Text, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Download") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SecondaryButton("What's new") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            TextButton(onLater) { Text("Not now", color = FF.Dim) }
        }
    }
}
