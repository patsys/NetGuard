package eu.faircode.netguard

import android.content.Context
import android.content.res.TypedArray
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Build
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
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
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
*/   class AdapterAccess constructor(context: Context, cursor: Cursor) : CursorAdapter(context, cursor, 0) {
    private val colVersion: Int
    private val colProtocol: Int
    private val colDaddr: Int
    private val colDPort: Int
    private val colTime: Int
    private val colAllowed: Int
    private val colBlock: Int
    private val colCount: Int
    private val colSent: Int
    private val colReceived: Int
    private val colConnections: Int
    private var colorText: Int = 0
    private val colorOn: Int
    private val colorOff: Int
    public override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.access, parent, false)
    }

    public override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val version: Int = cursor.getInt(colVersion)
        val protocol: Int = cursor.getInt(colProtocol)
        val daddr: String = cursor.getString(colDaddr)
        val dport: Int = cursor.getInt(colDPort)
        val time: Long = cursor.getLong(colTime)
        val allowed: Int = cursor.getInt(colAllowed)
        val block: Int = cursor.getInt(colBlock)
        val count: Int = cursor.getInt(colCount)
        val sent: Long = if (cursor.isNull(colSent)) -1 else cursor.getLong(colSent)
        val received: Long = if (cursor.isNull(colReceived)) -1 else cursor.getLong(colReceived)
        val connections: Int = if (cursor.isNull(colConnections)) -1 else cursor.getInt(colConnections)

        // Get views
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val ivBlock: ImageView = view.findViewById(R.id.ivBlock)
        val tvDest: TextView = view.findViewById(R.id.tvDest)
        val llTraffic: LinearLayout = view.findViewById(R.id.llTraffic)
        val tvConnections: TextView = view.findViewById(R.id.tvConnections)
        val tvTraffic: TextView = view.findViewById(R.id.tvTraffic)

        // Set values
        tvTime.setText(SimpleDateFormat("dd HH:mm").format(time))
        if (block < 0) ivBlock.setImageDrawable(null) else {
            ivBlock.setImageResource(if (block > 0) R.drawable.host_blocked else R.drawable.host_allowed)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val wrap: Drawable = DrawableCompat.wrap(ivBlock.getDrawable())
                DrawableCompat.setTint(wrap, if (block > 0) colorOff else colorOn)
            }
        }
        val dest: String = (Util.getProtocolName(protocol, version, true) +
                " " + daddr + (if (dport > 0) "/" + dport else "") + (if (count > 1) " ?" + count else ""))
        val span: SpannableString = SpannableString(dest)
        span.setSpan(UnderlineSpan(), 0, dest.length, 0)
        tvDest.setText(span)
        if (Util.isNumericAddress(daddr)) object : AsyncTask<String?, Any?, String>() {
            override fun onPreExecute() {
                ViewCompat.setHasTransientState(tvDest, true)
            }

            protected override fun doInBackground(vararg args: String): String {
                try {
                    return InetAddress.getByName(args.get(0)).getHostName()
                } catch (ignored: UnknownHostException) {
                    return args.get(0)
                }
            }

            override fun onPostExecute(addr: String) {
                tvDest.setText(
                        (Util.getProtocolName(protocol, version, true) +
                                " >" + addr + (if (dport > 0) "/" + dport else "")))
                ViewCompat.setHasTransientState(tvDest, false)
            }
        }.execute(daddr)
        if (allowed < 0) tvDest.setTextColor(colorText) else if (allowed > 0) tvDest.setTextColor(colorOn) else tvDest.setTextColor(colorOff)
        llTraffic.setVisibility(if ((connections > 0) || (sent > 0) || (received > 0)) View.VISIBLE else View.GONE)
        if (connections > 0) tvConnections.setText(context.getString(R.string.msg_count, connections))
        if (sent > 1024 * 1204 * 1024L || received > 1024 * 1024 * 1024L) tvTraffic.setText(context.getString(R.string.msg_gb,
                (if (sent > 0) sent / (1024 * 1024 * 1024f) else 0),
                (if (received > 0) received / (1024 * 1024 * 1024f) else 0))) else if (sent > 1204 * 1024L || received > 1024 * 1024L) tvTraffic.setText(context.getString(R.string.msg_mb,
                (if (sent > 0) sent / (1024 * 1024f) else 0),
                (if (received > 0) received / (1024 * 1024f) else 0))) else tvTraffic.setText(context.getString(R.string.msg_kb,
                (if (sent > 0) sent / 1024f else 0),
                (if (received > 0) received / 1024f else 0)))
    }

    init {
        colVersion = cursor.getColumnIndex("version")
        colProtocol = cursor.getColumnIndex("protocol")
        colDaddr = cursor.getColumnIndex("daddr")
        colDPort = cursor.getColumnIndex("dport")
        colTime = cursor.getColumnIndex("time")
        colAllowed = cursor.getColumnIndex("allowed")
        colBlock = cursor.getColumnIndex("block")
        colCount = cursor.getColumnIndex("count")
        colSent = cursor.getColumnIndex("sent")
        colReceived = cursor.getColumnIndex("received")
        colConnections = cursor.getColumnIndex("connections")
        val ta: TypedArray = context.getTheme().obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        try {
            colorText = ta.getColor(0, 0)
        } finally {
            ta.recycle()
        }
        val tv: TypedValue = TypedValue()
        context.getTheme().resolveAttribute(R.attr.colorOn, tv, true)
        colorOn = tv.data
        context.getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        colorOff = tv.data
    }
}