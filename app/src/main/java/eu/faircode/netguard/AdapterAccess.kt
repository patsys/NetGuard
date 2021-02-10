package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import eu.faircode.netguard.Util.getProtocolName
import eu.faircode.netguard.Util.isNumericAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat

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
*/   class AdapterAccess(context: Context, cursor: Cursor) :
    CursorAdapter(context, cursor, 0) {
    val uiScope = CoroutineScope(Dispatchers.Main)

    private val colVersion: Int = cursor.getColumnIndex("version")
    private val colProtocol: Int = cursor.getColumnIndex("protocol")
    private val colDaddr: Int = cursor.getColumnIndex("daddr")
    private val colDPort: Int = cursor.getColumnIndex("dport")
    private val colTime: Int = cursor.getColumnIndex("time")
    private val colAllowed: Int = cursor.getColumnIndex("allowed")
    private val colBlock: Int = cursor.getColumnIndex("block")
    private val colCount: Int = cursor.getColumnIndex("count")
    private val colSent: Int = cursor.getColumnIndex("sent")
    private val colReceived: Int = cursor.getColumnIndex("received")
    private val colConnections: Int = cursor.getColumnIndex("connections")
    private var colorText = 0
    private val colorOn: Int
    private val colorOff: Int
    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.access, parent, false)
    }

    @SuppressLint("SetTextI18n")
    override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val version = cursor.getInt(colVersion)
        val protocol = cursor.getInt(colProtocol)
        val daddr = cursor.getString(colDaddr)
        val dport = cursor.getInt(colDPort)
        val time = cursor.getLong(colTime)
        val allowed = cursor.getInt(colAllowed)
        val block = cursor.getInt(colBlock)
        val count = cursor.getInt(colCount)
        val sent = if (cursor.isNull(colSent)) -1 else cursor.getLong(colSent)
        val received = if (cursor.isNull(colReceived)) -1 else cursor.getLong(colReceived)
        val connections = if (cursor.isNull(colConnections)) -1 else cursor.getInt(colConnections)

        // Get views
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val ivBlock = view.findViewById<ImageView>(R.id.ivBlock)
        val tvDest = view.findViewById<TextView>(R.id.tvDest)
        val llTraffic = view.findViewById<LinearLayout>(R.id.llTraffic)
        val tvConnections = view.findViewById<TextView>(R.id.tvConnections)
        val tvTraffic = view.findViewById<TextView>(R.id.tvTraffic)

        // Set values
        tvTime.text = SimpleDateFormat("dd HH:mm").format(time)
        if (block < 0) ivBlock.setImageDrawable(null) else {
            ivBlock.setImageResource(if (block > 0) R.drawable.host_blocked else R.drawable.host_allowed)
        }
        val dest = getProtocolName(protocol, version, true) +
                " " + daddr + (if (dport > 0) "/$dport" else "") + if (count > 1) " ?$count" else ""
        val span = SpannableString(dest)
        span.setSpan(UnderlineSpan(), 0, dest.length, 0)
        tvDest.text = span
        if (isNumericAddress(daddr)) uiScope.launch {
            withContext(Dispatchers.Main) {
                ViewCompat.setHasTransientState(tvDest, true)
                withContext(Dispatchers.Default) {
                    try {

                        tvDest.text = getProtocolName(protocol, version, true) +
                                " >" + InetAddress.getByName(daddr).hostName + if (dport > 0) "/$dport" else ""
                        ViewCompat.setHasTransientState(tvDest, false)
                    } catch (ignored: UnknownHostException) {
                        withContext(Dispatchers.Main) {
                            tvDest.text = getProtocolName(protocol, version, true) +
                                    " >" + daddr + if (dport > 0) "/$dport" else ""
                            ViewCompat.setHasTransientState(tvDest, false)
                        }
                    }
                }
            }
        }
        when {
            allowed < 0 -> tvDest.setTextColor(colorText)
            allowed > 0 -> tvDest.setTextColor(
                colorOn
            )
            else -> tvDest.setTextColor(colorOff)
        }
        llTraffic.visibility =
            if (connections > 0 || sent > 0 || received > 0) View.VISIBLE else View.GONE
        if (connections > 0) tvConnections.text = context.getString(R.string.msg_count, connections)
        if (sent > 1024 * 1204 * 1024L || received > 1024 * 1024 * 1024L) tvTraffic.text =
            context.getString(
                R.string.msg_gb,
                if (sent > 0) sent / (1024 * 1024 * 1024) else 0,
                if (received > 0) received / (1024 * 1024 * 1024) else 0
            ) else if (sent > 1204 * 1024L || received > 1024 * 1024L) tvTraffic.text =
            context.getString(
                R.string.msg_mb,
                if (sent > 0) sent / (1024 * 1024) else 0,
                if (received > 0) received / (1024 * 1024) else 0
            ) else tvTraffic.text = context.getString(
            R.string.msg_kb,
            if (sent > 0) sent / 1024 else 0,
            if (received > 0) received / 1024 else 0
        )
    }

    init {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        colorText = try {
            ta.getColor(0, 0)
        } finally {
            ta.recycle()
        }
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOn, tv, true)
        colorOn = tv.data
        context.theme.resolveAttribute(R.attr.colorOff, tv, true)
        colorOff = tv.data
    }
}