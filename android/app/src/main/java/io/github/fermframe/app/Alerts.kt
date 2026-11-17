package io.github.fermframe.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What to be told about, kept on this phone. Temperatures are stored in °C. */
object AlertPrefs {
    private const val FILE = "fermframe-alerts"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun on(ctx: Context, kind: String, def: Boolean = true) = p(ctx).getBoolean("on_$kind", def)
    fun setOn(ctx: Context, kind: String, value: Boolean) = p(ctx).edit().putBoolean("on_$kind", value).apply()
    var Context.alertsEnabled: Boolean
        get() = p(this).getBoolean("enabled", false)
        set(v) { p(this).edit().putBoolean("enabled", v).apply() }
    fun useF(ctx: Context) = p(ctx).getBoolean("useF", false)
    fun setUseF(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("useF", v).apply()

    /** Temperature range for one FermFrame, or null when the alarm is off for it. */
    fun range(ctx: Context, mac: String): Pair<Double, Double>? {
        val s = p(ctx).getString("range_$mac", null) ?: return null
        val a = s.split(';').mapNotNull { it.toDoubleOrNull() }
        return if (a.size == 2) a[0] to a[1] else null
    }
    fun setRange(ctx: Context, mac: String, r: Pair<Double, Double>?) =
        p(ctx).edit().apply { if (r == null) remove("range_$mac") else putString("range_$mac", "${r.first};${r.second}") }.apply()

    /** What we last told the user about each FermFrame, so each alert fires once per change. */
    fun state(ctx: Context, mac: String): JSONObject = p(ctx).getString("state_$mac", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject()
    fun setState(ctx: Context, mac: String, j: JSONObject) = p(ctx).edit().putString("state_$mac", j.toString()).apply()

    const val READY = "ready"; const val TEMP = "temp"; const val PROBLEM = "problem"; const val OFFLINE = "offline"
}

object Alerts {
    private const val CHANNEL = "fermframe-alerts"
    private const val WORK = "fermframe-check"

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts (or stops) the checks every 15 minutes while on a Wi-Fi-type network. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        // Runs while alerts are on or a widget is on the home screen
        with(AlertPrefs) {
            if ((!ctx.alertsEnabled && !Widget.present(ctx)) || Demo.on) { wm.cancelUniqueWork(WORK); return }
        }
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "FermFrame alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ready to keg, temperature, problems and FermFrames that stop answering"
            })
    }

    fun notify(ctx: Context, mac: String, kind: String, title: String, text: String) {
        if (!canNotify(ctx) || !with(AlertPrefs) { ctx.alertsEnabled }) return
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, (mac + kind).hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("mac", mac).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setColor(0xFFFFCC33.toInt()).build()
        try { NotificationManagerCompat.from(ctx).notify((mac + kind).hashCode(), n) } catch (e: SecurityException) { }
    }

    private fun fmt(ctx: Context, c: Double) = if (AlertPrefs.useF(ctx)) "%.1f°F".format(c * 9 / 5 + 32) else "%.1f°C".format(c)

    /** Looks at every known FermFrame once and sends any alerts that are due. Used by the worker and by "Test". */
    fun checkAll(ctx: Context) {
        val clocks = Store.load(ctx)
        val fresh = ArrayList<Clock>()   // for the widget
        for (c in clocks) {
            if (c.ip.isEmpty()) continue
            val st = AlertPrefs.state(ctx, c.mac)
            val info = Net.clockInfo(c.base, 6000)
            if (info == null) {
                fresh.add(c.copy(online = false))
                val misses = st.optInt("misses") + 1
                st.put("misses", misses)
                // 3 missed checks = about 30 minutes or more not answering
                if (misses >= 3 && !st.optBoolean("offline") && AlertPrefs.on(ctx, AlertPrefs.OFFLINE)) {
                    notify(ctx, c.mac, AlertPrefs.OFFLINE, "${c.name} isn't answering", "It hasn't answered for over 30 minutes. Check it's plugged in and on your Wi-Fi.")
                    st.put("offline", true)
                }
                AlertPrefs.setState(ctx, c.mac, st); continue
            }
            st.put("misses", 0)
            if (st.optBoolean("offline")) st.put("offline", false)

            val problem = Html.decode(info.optString("problem"))
            if (problem != st.optString("problem")) {
                if (problem.isNotEmpty() && AlertPrefs.on(ctx, AlertPrefs.PROBLEM)) notify(ctx, c.mac, AlertPrefs.PROBLEM, "${c.name} has a problem", problem)
                st.put("problem", problem)
            }

            val g = Net.get(c.base + "/graph.json", 10000).let { r -> if (r.status == 200) Net.json(r.body)?.let { Graph.parse(it) } else null }
            fresh.add(c.copy(info = info, graph = g, online = true, checkedAt = System.currentTimeMillis()))
            if (g != null) {
                val brew = g.name.ifEmpty { "Your brew" }
                if (g.ready && !st.optBoolean("ready") && AlertPrefs.on(ctx, AlertPrefs.READY))
                    notify(ctx, c.mac, AlertPrefs.READY, "$brew might be ready to keg", "${c.name}: the gravity hasn't moved for 3 days" + (g.sg?.let { " (%.3f)".format(it) } ?: "") + ".")
                st.put("ready", g.ready)

                val range = AlertPrefs.range(ctx, c.mac)
                val t = g.temp
                if (range != null && t != null && AlertPrefs.on(ctx, AlertPrefs.TEMP)) {
                    val now = when { t < range.first -> "low"; t > range.second -> "high"; else -> "ok" }
                    val was = st.optString("temp", "ok")
                    if (now != was) {
                        when (now) {
                            "high" -> notify(ctx, c.mac, AlertPrefs.TEMP, "$brew is too warm", "${c.name}: ${fmt(ctx, t)}, above your ${fmt(ctx, range.second)} limit.")
                            "low" -> notify(ctx, c.mac, AlertPrefs.TEMP, "$brew is too cold", "${c.name}: ${fmt(ctx, t)}, below your ${fmt(ctx, range.first)} limit.")
                            else -> notify(ctx, c.mac, AlertPrefs.TEMP, "$brew is back in range", "${c.name}: ${fmt(ctx, t)}.")
                        }
                        st.put("temp", now)
                    }
                }
            }
            AlertPrefs.setState(ctx, c.mac, st)
        }
        if (fresh.isNotEmpty()) Widget.save(ctx, fresh)
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Alerts.checkAll(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}
