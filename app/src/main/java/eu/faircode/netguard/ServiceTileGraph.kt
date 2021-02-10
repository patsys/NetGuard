package eu.faircode.netguard

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.util.Log
import androidx.preference.PreferenceManager

import android.content.SharedPreferences
import android.os.Build
import android.annotation.TargetApi
import android.widget.Toast
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.service.quicksettings.TileService

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
class ServiceTileGraph : TileService(), OnSharedPreferenceChangeListener {
    override fun onStartListening() {
        Log.i(TAG, "Start listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        if (("show_stats" == key)) update()
    }

    private fun update() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val stats: Boolean = prefs.getBoolean("show_stats", false)
        val tile: Tile? = qsTile
        if (tile != null) {
            tile.state = if (stats) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, if (stats) R.drawable.ic_equalizer_white_24dp else R.drawable.ic_equalizer_white_24dp_60)
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

        // Check state
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val stats: Boolean = !prefs.getBoolean("show_stats", false)
        if (stats && !IAB.isPurchased(ActivityPro.SKU_SPEED, this)) Toast.makeText(this, R.string.title_pro_feature, Toast.LENGTH_SHORT).show() else prefs.edit().putBoolean("show_stats", stats).apply()
        ServiceSinkhole.reloadStats("tile", this)
    }

    companion object {
        private const val TAG: String = "NetGuard.TileGraph"
    }
}