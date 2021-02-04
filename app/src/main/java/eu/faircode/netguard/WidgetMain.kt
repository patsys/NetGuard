package eu.faircode.netguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.util.Log
import android.widget.RemoteViews
import androidx.preference.PreferenceManager

/*
   This file is part of NetGuard.

   NetGuard is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   NetGuard is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

   Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/   class WidgetMain constructor() : AppWidgetProvider() {
    public override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(appWidgetIds, appWidgetManager, context)
    }

    companion object {
        private val TAG: String = "NetGuard.Widget"
        private fun update(appWidgetIds: IntArray, appWidgetManager: AppWidgetManager, context: Context) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val enabled: Boolean = prefs.getBoolean("enabled", false)
            try {
                try {
                    val intent: Intent = Intent(if (enabled) WidgetAdmin.Companion.INTENT_OFF else WidgetAdmin.Companion.INTENT_ON)
                    intent.setPackage(context.getPackageName())
                    val pi: PendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                    for (id: Int in appWidgetIds) {
                        val views: RemoteViews = RemoteViews(context.getPackageName(), R.layout.widgetmain)
                        views.setOnClickPendingIntent(R.id.ivEnabled, pi)
                        views.setImageViewResource(R.id.ivEnabled, if (enabled) R.drawable.ic_security_color_24dp else R.drawable.ic_security_white_24dp_60)
                        appWidgetManager.updateAppWidget(id, views)
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        fun updateWidgets(context: Context) {
            val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds: IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WidgetMain::class.java))
            update(appWidgetIds, appWidgetManager, context)
        }
    }
}