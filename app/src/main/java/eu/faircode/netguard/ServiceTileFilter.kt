package eu.faircode.netguardimport

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import eu.faircode.netguard.*

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
class ServiceTileFilter constructor() : TileService(), OnSharedPreferenceChangeListener {
    public override fun onStartListening() {
        Log.i(ServiceTileFilter.Companion.TAG, "Start listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        if (("filter" == key)) update()
    }

    private fun update() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val filter: Boolean = prefs.getBoolean("filter", false)
        val tile: Tile? = getQsTile()
        if (tile != null) {
            tile.setState(if (filter) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
            tile.setIcon(Icon.createWithResource(this, if (filter) R.drawable.ic_filter_list_white_24dp else R.drawable.ic_filter_list_white_24dp_60))
            tile.updateTile()
        }
    }

    public override fun onStopListening() {
        Log.i(ServiceTileFilter.Companion.TAG, "Stop listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    public override fun onClick() {
        Log.i(ServiceTileFilter.Companion.TAG, "Click")
        if (Util.canFilter()) {
            if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, this)) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                prefs.edit().putBoolean("filter", !prefs.getBoolean("filter", false)).apply()
                ServiceSinkhole.reload("tile", this, false)
            } else Toast.makeText(this, R.string.title_pro_feature, Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, R.string.msg_unavailable, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val TAG: String = "NetGuard.TileFilter"
    }
}