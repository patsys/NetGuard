package eu.faircode.netguard

import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
*/@TargetApi(Build.VERSION_CODES.N)
class ServiceTileMain : TileService(), OnSharedPreferenceChangeListener {
    override fun onStartListening() {
        Log.i(TAG, "Start listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        if (("enabled" == key)) update()
    }

    private fun update() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled: Boolean = prefs.getBoolean("enabled", false)
        val tile: Tile? = qsTile
        if (tile != null) {
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, if (enabled) R.drawable.ic_security_white_24dp else R.drawable.ic_security_white_24dp_60)
            tile.updateTile()
        }
    }

    override fun onStopListening() {
        Log.i(TAG, "Stop listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onClick() {
        Log.i(TAG, "Click")

        // Cancel set alarm
        val am: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(WidgetAdmin.INTENT_ON)
        intent.setPackage(packageName)
        val pi: PendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)

        // Check state
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled: Boolean = !prefs.getBoolean("enabled", false)
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled) ServiceSinkhole.start("tile", this) else {
            ServiceSinkhole.stop("tile", this, false)

            // Auto enable
            val auto: Int = prefs.getString("auto_enable", "0")!!.toInt()
            if (auto > 0) {
                Log.i(TAG, "Scheduling enabled after minutes=$auto")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) am.set(AlarmManager.RTC_WAKEUP, Date().time + auto * 60 * 1000L, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().time + auto * 60 * 1000L, pi)
            }
        }
    }

    companion object {
        private const val TAG: String = "NetGuard.TileMain"
    }
}