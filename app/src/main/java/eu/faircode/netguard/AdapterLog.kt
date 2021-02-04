package eu.faircode.netguard

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.*
import android.os.AsyncTask
import android.os.Build
import android.os.Process
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
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
*/   class AdapterLog constructor(context: Context, cursor: Cursor, private var resolve: Boolean, private var organization: Boolean) : CursorAdapter(context, cursor, 0) {
    private val colTime: Int
    private val colVersion: Int
    private val colProtocol: Int
    private val colFlags: Int
    private val colSAddr: Int
    private val colSPort: Int
    private val colDAddr: Int
    private val colDPort: Int
    private val colDName: Int
    private val colUid: Int
    private val colData: Int
    private val colAllowed: Int
    private val colConnection: Int
    private val colInteractive: Int
    private val colorOn: Int
    private val colorOff: Int
    private val iconSize: Int
    private var dns1: InetAddress? = null
    private var dns2: InetAddress? = null
    private var vpn4: InetAddress? = null
    private var vpn6: InetAddress? = null
    fun setResolve(resolve: Boolean) {
        this.resolve = resolve
    }

    fun setOrganization(organization: Boolean) {
        this.organization = organization
    }

    public override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.log, parent, false)
    }

    public override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val time: Long = cursor.getLong(colTime)
        val version: Int = (if (cursor.isNull(colVersion)) -1 else cursor.getInt(colVersion))
        val protocol: Int = (if (cursor.isNull(colProtocol)) -1 else cursor.getInt(colProtocol))
        val flags: String = cursor.getString(colFlags)
        val saddr: String = cursor.getString(colSAddr)
        val sport: Int = (if (cursor.isNull(colSPort)) -1 else cursor.getInt(colSPort))
        val daddr: String = cursor.getString(colDAddr)
        val dport: Int = (if (cursor.isNull(colDPort)) -1 else cursor.getInt(colDPort))
        val dname: String? = (if (cursor.isNull(colDName)) null else cursor.getString(colDName))
        var uid: Int = (if (cursor.isNull(colUid)) -1 else cursor.getInt(colUid))
        val data: String = cursor.getString(colData)
        val allowed: Int = (if (cursor.isNull(colAllowed)) -1 else cursor.getInt(colAllowed))
        val connection: Int = (if (cursor.isNull(colConnection)) -1 else cursor.getInt(colConnection))
        val interactive: Int = (if (cursor.isNull(colInteractive)) -1 else cursor.getInt(colInteractive))

        // Get views
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvFlags: TextView = view.findViewById(R.id.tvFlags)
        val tvSAddr: TextView = view.findViewById(R.id.tvSAddr)
        val tvSPort: TextView = view.findViewById(R.id.tvSPort)
        val tvDaddr: TextView = view.findViewById(R.id.tvDAddr)
        val tvDPort: TextView = view.findViewById(R.id.tvDPort)
        val tvOrganization: TextView = view.findViewById(R.id.tvOrganization)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvUid: TextView = view.findViewById(R.id.tvUid)
        val tvData: TextView = view.findViewById(R.id.tvData)
        val ivConnection: ImageView = view.findViewById(R.id.ivConnection)
        val ivInteractive: ImageView = view.findViewById(R.id.ivInteractive)

        // Show time
        tvTime.setText(SimpleDateFormat("HH:mm:ss").format(time))

        // Show connection type
        if (connection <= 0) ivConnection.setImageResource(if (allowed > 0) R.drawable.host_allowed else R.drawable.host_blocked) else {
            if (allowed > 0) ivConnection.setImageResource(if (connection == 1) R.drawable.wifi_on else R.drawable.other_on) else ivConnection.setImageResource(if (connection == 1) R.drawable.wifi_off else R.drawable.other_off)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(ivConnection.getDrawable())
            DrawableCompat.setTint(wrap, if (allowed > 0) colorOn else colorOff)
        }

        // Show if screen on
        if (interactive <= 0) ivInteractive.setImageDrawable(null) else {
            ivInteractive.setImageResource(R.drawable.screen_on)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val wrap: Drawable = DrawableCompat.wrap(ivInteractive.getDrawable())
                DrawableCompat.setTint(wrap, colorOn)
            }
        }

        // Show protocol name
        tvProtocol.setText(Util.getProtocolName(protocol, version, false))

        // SHow TCP flags
        tvFlags.setText(flags)
        tvFlags.setVisibility(if (TextUtils.isEmpty(flags)) View.GONE else View.VISIBLE)

        // Show source and destination port
        if (protocol == 6 || protocol == 17) {
            tvSPort.setText(if (sport < 0) "" else getKnownPort(sport))
            tvDPort.setText(if (dport < 0) "" else getKnownPort(dport))
        } else {
            tvSPort.setText(if (sport < 0) "" else Integer.toString(sport))
            tvDPort.setText(if (dport < 0) "" else Integer.toString(dport))
        }

        // Application icon
        var info: ApplicationInfo? = null
        val pm: PackageManager = context.getPackageManager()
        val pkg: Array<String>? = pm.getPackagesForUid(uid)
        if (pkg != null && pkg.size > 0) try {
            info = pm.getApplicationInfo(pkg.get(0), 0)
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        if (info == null) ivIcon.setImageDrawable(null) else {
            if (info.icon <= 0) ivIcon.setImageResource(android.R.drawable.sym_def_app_icon) else {
                val uri: Uri = Uri.parse("android.resource://" + info.packageName + "/" + info.icon)
                GlideApp.with(context)
                        .load(uri) //.diskCacheStrategy(DiskCacheStrategy.NONE)
                        //.skipMemoryCache(true)
                        .override(iconSize, iconSize)
                        .into(ivIcon)
            }
        }
        val we: Boolean = (Process.myUid() == uid)

        // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
        uid = uid % 100000 // strip off user ID
        if (uid == -1) tvUid.setText("") else if (uid == 0) tvUid.setText(context.getString(R.string.title_root)) else if (uid == 9999) tvUid.setText("-") // nobody
        else tvUid.setText(Integer.toString(uid))

        // Show source address
        tvSAddr.setText(getKnownAddress(saddr))

        // Show destination address
        if (!we && resolve && !isKnownAddress(daddr)) if (dname == null) {
            tvDaddr.setText(daddr)
            object : AsyncTask<String?, Any?, String>() {
                override fun onPreExecute() {
                    ViewCompat.setHasTransientState(tvDaddr, true)
                }

                protected override fun doInBackground(vararg args: String): String {
                    try {
                        return InetAddress.getByName(args.get(0)).getHostName()
                    } catch (ignored: UnknownHostException) {
                        return args.get(0)
                    }
                }

                override fun onPostExecute(name: String) {
                    tvDaddr.setText(">" + name)
                    ViewCompat.setHasTransientState(tvDaddr, false)
                }
            }.execute(daddr)
        } else tvDaddr.setText(dname) else tvDaddr.setText(getKnownAddress(daddr))

        // Show organization
        tvOrganization.setVisibility(View.GONE)
        if (!we && organization) {
            if (!isKnownAddress(daddr)) object : AsyncTask<String?, Any?, String?>() {
                override fun onPreExecute() {
                    ViewCompat.setHasTransientState(tvOrganization, true)
                }

                protected override fun doInBackground(vararg args: String): String? {
                    try {
                        return Util.getOrganization(args.get(0))
                    } catch (ex: Throwable) {
                        Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        return null
                    }
                }

                override fun onPostExecute(organization: String?) {
                    if (organization != null) {
                        tvOrganization.setText(organization)
                        tvOrganization.setVisibility(View.VISIBLE)
                    }
                    ViewCompat.setHasTransientState(tvOrganization, false)
                }
            }.execute(daddr)
        }

        // Show extra data
        if (TextUtils.isEmpty(data)) {
            tvData.setText("")
            tvData.setVisibility(View.GONE)
        } else {
            tvData.setText(data)
            tvData.setVisibility(View.VISIBLE)
        }
    }

    fun isKnownAddress(addr: String?): Boolean {
        try {
            val a: InetAddress = InetAddress.getByName(addr)
            if ((a == dns1) || (a == dns2) || (a == vpn4) || (a == vpn6)) return true
        } catch (ignored: UnknownHostException) {
        }
        return false
    }

    private fun getKnownAddress(addr: String): String {
        try {
            val a: InetAddress = InetAddress.getByName(addr)
            if ((a == dns1)) return "dns1"
            if ((a == dns2)) return "dns2"
            if ((a == vpn4) || (a == vpn6)) return "vpn"
        } catch (ignored: UnknownHostException) {
        }
        return addr
    }

    private fun getKnownPort(port: Int): String {
        // https://en.wikipedia.org/wiki/List_of_TCP_and_UDP_port_numbers#Well-known_ports
        when (port) {
            7 -> return "echo"
            25 -> return "smtp"
            53 -> return "dns"
            80 -> return "http"
            110 -> return "pop3"
            143 -> return "imap"
            443 -> return "https"
            465 -> return "smtps"
            993 -> return "imaps"
            995 -> return "pop3s"
            else -> return Integer.toString(port)
        }
    }

    companion object {
        private val TAG: String = "NetGuard.Log"
    }

    init {
        colTime = cursor.getColumnIndex("time")
        colVersion = cursor.getColumnIndex("version")
        colProtocol = cursor.getColumnIndex("protocol")
        colFlags = cursor.getColumnIndex("flags")
        colSAddr = cursor.getColumnIndex("saddr")
        colSPort = cursor.getColumnIndex("sport")
        colDAddr = cursor.getColumnIndex("daddr")
        colDPort = cursor.getColumnIndex("dport")
        colDName = cursor.getColumnIndex("dname")
        colUid = cursor.getColumnIndex("uid")
        colData = cursor.getColumnIndex("data")
        colAllowed = cursor.getColumnIndex("allowed")
        colConnection = cursor.getColumnIndex("connection")
        colInteractive = cursor.getColumnIndex("interactive")
        val tv: TypedValue = TypedValue()
        context.getTheme().resolveAttribute(R.attr.colorOn, tv, true)
        colorOn = tv.data
        context.getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        colorOff = tv.data
        iconSize = Util.dips2pixels(24, context)
        try {
            val lstDns: List<InetAddress?> = ServiceSinkhole.Companion.getDns(context)
            dns1 = (if (lstDns.size > 0) lstDns.get(0) else null)
            dns2 = (if (lstDns.size > 1) lstDns.get(1) else null)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"))
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        } catch (ex: UnknownHostException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }
}