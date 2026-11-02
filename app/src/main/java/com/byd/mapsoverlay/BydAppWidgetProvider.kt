package com.byd.mapsoverlay

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

class BydAppWidgetProvider : AppWidgetProvider() {

    companion object {
        var lastSoc: Double = 90.0
        var lastConsumption: Double = 10.5
        var lastRealRange: Double = 420.0
        var lastSoh: Int = 98
        var lastDestText: String = "🏁 Google Maps: แตะเพื่อเริ่มนำทาง"

        fun updateAllWidgets(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, BydAppWidgetProvider::class.java))
                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            } catch (_: Exception) {}
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.layout_dudu_widget)
            views.setTextViewText(R.id.tv_widget_soc, String.format("🔋 %.0f%%", lastSoc))
            views.setTextViewText(R.id.tv_widget_real_range, String.format("🚗 ~%.0f km", lastRealRange))
            views.setTextViewText(R.id.tv_widget_consumption, String.format("⚡ %.1f kWh/100km (50km)", lastConsumption))
            views.setTextViewText(R.id.tv_widget_soh, String.format("🩺 SOH %d%%", lastSoh))
            views.setTextViewText(R.id.tv_widget_nav_dest, lastDestText)
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }
}
