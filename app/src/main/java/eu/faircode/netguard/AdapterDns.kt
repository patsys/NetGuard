package eu.faircode.netguard

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.TextView
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
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
*/   class AdapterDns constructor(context: Context?, cursor: Cursor) : CursorAdapter(context, cursor, 0) {
    private var colorExpired: Int = 0
    private val colTime: Int
    private val colQName: Int
    private val colAName: Int
    private val colResource: Int
    private val colTTL: Int
    public override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.dns, parent, false)
    }

    public override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val time: Long = cursor.getLong(colTime)
        val qname: String = cursor.getString(colQName)
        val aname: String = cursor.getString(colAName)
        val resource: String = cursor.getString(colResource)
        val ttl: Int = cursor.getInt(colTTL)
        val now: Long = Date().getTime()
        val expired: Boolean = (time + ttl < now)
        view.setBackgroundColor(if (expired) colorExpired else Color.TRANSPARENT)

        // Get views
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvQName: TextView = view.findViewById(R.id.tvQName)
        val tvAName: TextView = view.findViewById(R.id.tvAName)
        val tvResource: TextView = view.findViewById(R.id.tvResource)
        val tvTTL: TextView = view.findViewById(R.id.tvTTL)

        // Set values
        tvTime.setText(SimpleDateFormat("dd HH:mm").format(time))
        tvQName.setText(qname)
        tvAName.setText(aname)
        tvResource.setText(resource)
        tvTTL.setText("+" + Integer.toString(ttl / 1000))
    }

    init {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean("dark_theme", false)) colorExpired = Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY), Color.blue(Color.DKGRAY)) else colorExpired = Color.argb(128, Color.red(Color.LTGRAY), Color.green(Color.LTGRAY), Color.blue(Color.LTGRAY))
        colTime = cursor.getColumnIndex("time")
        colQName = cursor.getColumnIndex("qname")
        colAName = cursor.getColumnIndex("aname")
        colResource = cursor.getColumnIndex("resource")
        colTTL = cursor.getColumnIndex("ttl")
    }
}