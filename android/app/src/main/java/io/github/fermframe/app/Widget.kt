package io.github.fermframe.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: up to two FermFrames with their brew, gravity, temperature and day. */
class FermFrameWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widget.draw(ctx)
    override fun onEnabled(ctx: Context) { Alerts.schedule(ctx) }
    override fun onDisabled(ctx: Context) { Alerts.schedule(ctx) }
}

object Widget {
    private const val FILE = "fermframe-widget"

    fun present(ctx: Context) = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, FermFrameWidget::class.java)).isNotEmpty()

    /** Remembers the latest readings for the widget (from the app or the background check) and redraws it. */
    fun save(ctx: Context, clocks: List<Clock>) {
        if (Demo.on) return
        val a = JSONArray()
        clocks.forEach { c ->
            a.put(JSONObject().put("name", c.name).put("online", c.online).put("brew", c.graph?.name ?: "")
                .put("sg", c.graph?.sg ?: JSONObject.NULL).put("temp", c.graph?.temp ?: JSONObject.NULL)
                .put("day", c.graph?.day ?: JSONObject.NULL).put("ready", c.graph?.ready == true).put("problem", c.problem))
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("data", a.toString()).putLong("at", System.currentTimeMillis()).apply()
        draw(ctx)
    }

    fun draw(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FermFrameWidget::class.java))
        if (ids.isEmpty()) return
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val data = try { JSONArray(p.getString("data", "[]")) } catch (e: Exception) { JSONArray() }
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        val rows = listOf(
            intArrayOf(R.id.w_row1, R.id.w_name1, R.id.w_sub1, R.id.w_sg1, R.id.w_temp1),
            intArrayOf(R.id.w_row2, R.id.w_name2, R.id.w_sub2, R.id.w_sg2, R.id.w_temp2))
        for ((i, r) in rows.withIndex()) {
            val o = if (i < data.length()) data.getJSONObject(i) else null
            if (o == null) { v.setViewVisibility(r[0], if (i == 0) View.VISIBLE else View.GONE); if (i == 0) { v.setTextViewText(r[1], "No FermFrames yet"); v.setTextViewText(r[2], "Open the app to find them"); v.setTextViewText(r[3], ""); v.setTextViewText(r[4], "") }; continue }
            v.setViewVisibility(r[0], View.VISIBLE)
            v.setTextViewText(r[1], o.optString("name"))
            val online = o.optBoolean("online")
            val sub = when {
                !online -> "Not answering"
                o.optString("problem").isNotEmpty() -> o.optString("problem")
                o.optBoolean("ready") -> "Ready to keg?"
                else -> listOfNotNull(o.optString("brew").takeIf { it.isNotEmpty() }, if (o.isNull("day")) null else "Day ${o.optInt("day")}").joinToString(" · ")
            }
            v.setTextViewText(r[2], sub)
            v.setTextColor(r[2], when { !online -> 0xFF969696.toInt(); o.optString("problem").isNotEmpty() -> 0xFFFF806E.toInt(); o.optBoolean("ready") -> 0xFF6EDC78.toInt(); else -> 0xFF969696.toInt() })
            v.setTextViewText(r[3], if (online && !o.isNull("sg")) "%.3f".format(o.optDouble("sg")) else "–")
            v.setTextViewText(r[4], if (online && !o.isNull("temp")) "%.1f°C".format(o.optDouble("temp")) else "")
        }
        val at = p.getLong("at", 0)
        v.setTextViewText(R.id.w_when, if (at > 0) "Updated " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(at)) else "")
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.w_root, open)
        mgr.updateAppWidget(ids, v)
    }
}
