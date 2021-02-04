package eu.faircode.netguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.*

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
*/   class WidgetAdmin constructor() : ReceiverAutostart() {
    public override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.i(TAG, "Received " + intent)
        Util.logExtras(intent)
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        // Cancel set alarm
        val am: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i: Intent = Intent(INTENT_ON)
        i.setPackage(context.getPackageName())
        val pi: PendingIntent = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT)
        if ((INTENT_ON == intent.getAction()) || (INTENT_OFF == intent.getAction())) am.cancel(pi)

        // Vibrate
        val vs: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vs.hasVibrator()) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vs.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) else vs.vibrate(50)
        try {
            if ((INTENT_ON == intent.getAction()) || (INTENT_OFF == intent.getAction())) {
                val enabled: Boolean = (INTENT_ON == intent.getAction())
                prefs.edit().putBoolean("enabled", enabled).apply()
                if (enabled) ServiceSinkhole.Companion.start("widget", context) else ServiceSinkhole.Companion.stop("widget", context, false)

                // Auto enable
                val auto: Int = prefs.getString("auto_enable", "0")!!.toInt()
                if (!enabled && auto > 0) {
                    Log.i(TAG, "Scheduling enabled after minutes=" + auto)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) am.set(AlarmManager.RTC_WAKEUP, Date().getTime() + auto * 60 * 1000L, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().getTime() + auto * 60 * 1000L, pi)
                }
            } else if ((INTENT_LOCKDOWN_ON == intent.getAction()) || (INTENT_LOCKDOWN_OFF == intent.getAction())) {
                val lockdown: Boolean = (INTENT_LOCKDOWN_ON == intent.getAction())
                prefs.edit().putBoolean("lockdown", lockdown).apply()
                ServiceSinkhole.Companion.reload("widget", context, false)
                WidgetLockdown.Companion.updateWidgets(context)
            }
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }

    companion object {
        private val TAG: String = "NetGuard.Widget"
        val INTENT_ON: String = "eu.faircode.netguard.ON"
        val INTENT_OFF: String = "eu.faircode.netguard.OFF"
        val INTENT_LOCKDOWN_ON: String = "eu.faircode.netguard.LOCKDOWN_ON"
        val INTENT_LOCKDOWN_OFF: String = "eu.faircode.netguard.LOCKDOWN_OFF"
    }
}