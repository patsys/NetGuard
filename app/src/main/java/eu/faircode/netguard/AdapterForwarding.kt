package eu.faircode.netguardimport

import android.content.Context
import android.database.Cursor
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.TextView
import eu.faircode.netguard.R
import eu.faircode.netguard.Util

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
*/   class AdapterForwarding constructor(context: Context?, cursor: Cursor) : CursorAdapter(context, cursor, 0) {
    private val colProtocol: Int = cursor.getColumnIndex("protocol")
    private val colDPort: Int = cursor.getColumnIndex("dport")
    private val colRAddr: Int = cursor.getColumnIndex("raddr")
    private val colRPort: Int = cursor.getColumnIndex("rport")
    private val colRUid: Int = cursor.getColumnIndex("ruid")
    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.forward, parent, false)
    }

    override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val protocol: Int = cursor.getInt(colProtocol)
        val dport: Int = cursor.getInt(colDPort)
        val raddr: String = cursor.getString(colRAddr)
        val rport: Int = cursor.getInt(colRPort)
        val ruid: Int = cursor.getInt(colRUid)

        // Get views
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvDPort: TextView = view.findViewById(R.id.tvDPort)
        val tvRAddr: TextView = view.findViewById(R.id.tvRAddr)
        val tvRPort: TextView = view.findViewById(R.id.tvRPort)
        val tvRUid: TextView = view.findViewById(R.id.tvRUid)
        tvProtocol.text = Util.getProtocolName(protocol, 0, false)
        tvDPort.text = dport.toString()
        tvRAddr.text = raddr
        tvRPort.text = rport.toString()
        tvRUid.text = TextUtils.join(", ", Util.getApplicationNames(ruid, context))
    }

}