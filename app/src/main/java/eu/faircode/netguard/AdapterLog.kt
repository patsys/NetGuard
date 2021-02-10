package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.*
import android.os.Process
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
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
*/   class AdapterLog constructor(context: Context, cursor: Cursor, private var resolve: Boolean, private var organization: Boolean) : CursorAdapter(context, cursor, 0) {
    private val colTime: Int = cursor.getColumnIndex("time")
    private val colVersion: Int = cursor.getColumnIndex("version")
    private val colProtocol: Int = cursor.getColumnIndex("protocol")
    private val colFlags: Int = cursor.getColumnIndex("flags")
    private val colSAddr: Int = cursor.getColumnIndex("saddr")
    private val colSPort: Int = cursor.getColumnIndex("sport")
    private val colDAddr: Int = cursor.getColumnIndex("daddr")
    private val colDPort: Int = cursor.getColumnIndex("dport")
    private val colDName: Int = cursor.getColumnIndex("dname")
    private val colUid: Int = cursor.getColumnIndex("uid")
    private val colData: Int = cursor.getColumnIndex("data")
    private val colAllowed: Int = cursor.getColumnIndex("allowed")
    private val colConnection: Int = cursor.getColumnIndex("connection")
    private val colInteractive: Int = cursor.getColumnIndex("interactive")
    private val colorOn: Int
    private val colorOff: Int
    private val iconSize: Int
    private var dns1: InetAddress? = null
    private var dns2: InetAddress? = null
    private var vpn4: InetAddress? = null
    private var vpn6: InetAddress? = null
    val uiScope = CoroutineScope(Dispatchers.Main)

    fun setResolve(resolve: Boolean) {
        this.resolve = resolve
    }

    fun setOrganization(organization: Boolean) {
        this.organization = organization
    }

    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.log, parent, false)
    }

    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    override fun bindView(view: View, context: Context, cursor: Cursor) {
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
        tvTime.text = SimpleDateFormat("HH:mm:ss").format(time)

        // Show connection type
        if (connection <= 0) ivConnection.setImageResource(if (allowed > 0) R.drawable.host_allowed else R.drawable.host_blocked) else {
            if (allowed > 0) ivConnection.setImageResource(if (connection == 1) R.drawable.wifi_on else R.drawable.other_on) else ivConnection.setImageResource(if (connection == 1) R.drawable.wifi_off else R.drawable.other_off)
        }

        // Show if screen on
        if (interactive <= 0) ivInteractive.setImageDrawable(null) else {
            ivInteractive.setImageResource(R.drawable.screen_on)
        }

        // Show protocol name
        tvProtocol.text = Util.getProtocolName(protocol, version, false)

        // SHow TCP flags
        tvFlags.text = flags
        tvFlags.visibility = if (TextUtils.isEmpty(flags)) View.GONE else View.VISIBLE

        // Show source and destination port
        if (protocol == 6 || protocol == 17) {
            tvSPort.text = if (sport < 0) "" else getKnownPort(sport)
            tvDPort.text = if (dport < 0) "" else getKnownPort(dport)
        } else {
            tvSPort.text = if (sport < 0) "" else sport.toString()
            tvDPort.text = if (dport < 0) "" else dport.toString()
        }

        // Application icon
        var info: ApplicationInfo? = null
        val pm: PackageManager = context.packageManager
        val pkg: Array<String>? = pm.getPackagesForUid(uid)
        if (pkg != null && pkg.isNotEmpty()) try {
            info = pm.getApplicationInfo(pkg[0], 0)
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
        uid %= 100000 // strip off user ID
        when (uid) {
            -1 -> tvUid.text = ""
            0 -> tvUid.text = context.getString(R.string.title_root)
            9999 -> tvUid.text = "-" // nobody
            else -> tvUid.text = uid.toString()
        }

        // Show source address
        tvSAddr.text = getKnownAddress(saddr)

        // Show destination address
        if (!we && resolve && !isKnownAddress(daddr)) if (dname == null) {
            tvDaddr.text = daddr
            uiScope.launch {
                withContext(Dispatchers.Main) {
                    ViewCompat.setHasTransientState(tvDaddr, true)
                    withContext(Dispatchers.Default) {
                        try {
                            val name = InetAddress.getByName(daddr).hostName
                            withContext(Dispatchers.Main) {
                                tvDaddr.text = ">$name"
                                ViewCompat.setHasTransientState(tvDaddr, false)
                            }
                        } catch (_: UnknownHostException) {
                            withContext(Dispatchers.Main) {
                                tvDaddr.text = ">$daddr"
                                ViewCompat.setHasTransientState(tvDaddr, false)
                            }
                        }

                    }
                }
            }
        } else tvDaddr.text = dname else tvDaddr.text = getKnownAddress(daddr)

        // Show organization
        tvOrganization.visibility = View.GONE
        if (!we && organization) {
            if (!isKnownAddress(daddr)) uiScope.launch {
                withContext(Dispatchers.Main) {
                    ViewCompat.setHasTransientState(tvOrganization, true)
                    withContext(Dispatchers.Default) {
                        try {
                            val organization = Util.getOrganization(daddr)
                            withContext(Dispatchers.Main) {
                                tvOrganization.text = organization
                                tvOrganization.visibility = View.VISIBLE
                                ViewCompat.setHasTransientState(tvOrganization, false)
                            }
                        } catch (ex: Throwable) {
                            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                            ViewCompat.setHasTransientState(tvOrganization, false)
                        }
                    }
                }
            }
        }

        // Show extra data
        if (TextUtils.isEmpty(data)) {
            tvData.text = ""
            tvData.visibility = View.GONE
        } else {
            tvData.text = data
            tvData.visibility = View.VISIBLE
        }
    }

    private fun isKnownAddress(addr: String?): Boolean {
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
        return when (port) {
            7 -> "echo"
            25 -> "smtp"
            53 -> "dns"
            80 -> "http"
            110 -> "pop3"
            143 -> "imap"
            443 -> "https"
            465 -> "smtps"
            993 -> "imaps"
            995 -> "pop3s"
            else -> port.toString()
        }
    }

    companion object {
        private const val TAG: String = "NetGuard.Log"
    }

    init {
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOn, tv, true)
        colorOn = tv.data
        context.theme.resolveAttribute(R.attr.colorOff, tv, true)
        colorOff = tv.data
        iconSize = Util.dips2pixels(24, context)
        try {
            val lstDns: List<InetAddress?> = ServiceSinkhole.getDns(context)
            dns1 = (if (lstDns.isNotEmpty()) lstDns[0] else null)
            dns2 = (if (lstDns.size > 1) lstDns[1] else null)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"))
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        } catch (ex: UnknownHostException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }
}