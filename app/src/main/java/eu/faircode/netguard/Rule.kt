package eu.faircode.netguard

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.Cursor
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
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
*/   class Rule private constructor(dh: DatabaseHelper?, info: PackageInfo, context: Context) {
    var uid: Int = info.applicationInfo.uid
    var packageName: String = info.packageName
    var icon: Int = info.applicationInfo.icon
    var name: String? = null
    var version: String = info.versionName
    var system = false
    var internet = false
    var enabled = false
    var pkg = true
    var wifi_default = false
    var other_default = false
    var screen_wifi_default = false
    var screen_other_default = false
    var roaming_default = false
    var wifi_blocked = false
    var other_blocked = false
    var screen_wifi = false
    var screen_other = false
    var roaming = false
    var lockdown = false
    var apply = true
    var notify = true
    var relateduids = false
    var related: Array<String>? = null
    var hosts: Long = 0
    var changed = false
    var expanded = false
    private fun updateChanged(default_wifi: Boolean, default_other: Boolean, default_roaming: Boolean) {
        changed = wifi_blocked != default_wifi ||
                other_blocked != default_other ||
                wifi_blocked && screen_wifi != screen_wifi_default ||
                other_blocked && screen_other != screen_other_default ||
                (!other_blocked || screen_other) && roaming != default_roaming || hosts > 0 || lockdown || !apply
    }

    fun updateChanged(context: Context?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val screen_on = prefs.getBoolean("screen_on", false)
        val default_wifi = prefs.getBoolean("whitelist_wifi", true) && screen_on
        val default_other = prefs.getBoolean("whitelist_other", true) && screen_on
        val default_roaming = prefs.getBoolean("whitelist_roaming", true)
        updateChanged(default_wifi, default_other, default_roaming)
    }

    override fun toString(): String {
        // This is used in the port forwarding dialog application selector
        return name!!
    }

    companion object {
        private const val TAG = "NetGuard.Rule"
        private var cachePackageInfo: List<PackageInfo>? = null
        private val cacheLabel: MutableMap<PackageInfo, String> = HashMap()
        private val cacheSystem: MutableMap<String, Boolean> = HashMap()
        private val cacheInternet: MutableMap<String, Boolean> = HashMap()
        private val cacheEnabled: MutableMap<PackageInfo, Boolean> = HashMap()
        private fun getPackages(context: Context): MutableList<PackageInfo> {
            if (cachePackageInfo == null) {
                val pm = context.packageManager
                cachePackageInfo = pm.getInstalledPackages(0)
            }
            return ArrayList(cachePackageInfo)
        }

        private fun getLabel(info: PackageInfo, context: Context): String? {
            if (!cacheLabel.containsKey(info)) {
                val pm = context.packageManager
                cacheLabel[info] = info.applicationInfo.loadLabel(pm).toString()
            }
            return cacheLabel[info]
        }

        private fun isSystem(packageName: String, context: Context): Boolean {
            if (!cacheSystem.containsKey(packageName)) cacheSystem[packageName] = Util.isSystem(packageName, context)
            return cacheSystem[packageName]!!
        }

        private fun hasInternet(packageName: String, context: Context): Boolean {
            if (!cacheInternet.containsKey(packageName)) cacheInternet[packageName] = Util.hasInternet(packageName, context)
            return cacheInternet[packageName]!!
        }

        private fun isEnabled(info: PackageInfo, context: Context): Boolean {
            if (!cacheEnabled.containsKey(info)) cacheEnabled[info] = Util.isEnabled(info, context)
            return cacheEnabled[info]!!
        }

        fun clearCache(context: Context) {
            Log.i(TAG, "Clearing cache")
            synchronized(context.applicationContext) {
                cachePackageInfo = null
                cacheLabel.clear()
                cacheSystem.clear()
                cacheInternet.clear()
                cacheEnabled.clear()
            }
            val dh: DatabaseHelper = DatabaseHelper.getInstance(context)
            dh.clearApps()
        }

        @RequiresApi(Build.VERSION_CODES.N)
        fun getRules(all: Boolean, context: Context): List<Rule?> {
            synchronized(context.applicationContext) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE)
                val other = context.getSharedPreferences("other", Context.MODE_PRIVATE)
                val screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE)
                val screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE)
                val roaming = context.getSharedPreferences("roaming", Context.MODE_PRIVATE)
                val lockdown = context.getSharedPreferences("lockdown", Context.MODE_PRIVATE)
                val apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE)
                val notify = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

                // Get settings
                val default_wifi = prefs.getBoolean("whitelist_wifi", true)
                val default_other = prefs.getBoolean("whitelist_other", true)
                var default_screen_wifi = prefs.getBoolean("screen_wifi", false)
                var default_screen_other = prefs.getBoolean("screen_other", false)
                val default_roaming = prefs.getBoolean("whitelist_roaming", true)
                val manage_system = prefs.getBoolean("manage_system", false)
                val screen_on = prefs.getBoolean("screen_on", true)
                val show_user = prefs.getBoolean("show_user", true)
                val show_system = prefs.getBoolean("show_system", false)
                val show_nointernet = prefs.getBoolean("show_nointernet", true)
                val show_disabled = prefs.getBoolean("show_disabled", true)
                default_screen_wifi = default_screen_wifi && screen_on
                default_screen_other = default_screen_other && screen_on

                // Get predefined rules
                val pre_wifi_blocked: MutableMap<String, Boolean> = HashMap()
                val pre_other_blocked: MutableMap<String, Boolean> = HashMap()
                val pre_roaming: MutableMap<String, Boolean> = HashMap()
                val pre_related: MutableMap<String, Array<String>> = HashMap()
                val pre_system: MutableMap<String, Boolean> = HashMap()
                try {
                    val xml = context.resources.getXml(R.xml.predefined)
                    var eventType = xml.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) when (xml.name) {
                            "wifi" -> {
                                val pkg = xml.getAttributeValue(null, "package")
                                val pblocked = xml.getAttributeBooleanValue(null, "blocked", false)
                                pre_wifi_blocked[pkg] = pblocked
                            }
                            "other" -> {
                                val pkg = xml.getAttributeValue(null, "package")
                                val pblocked = xml.getAttributeBooleanValue(null, "blocked", false)
                                val proaming = xml.getAttributeBooleanValue(null, "roaming", default_roaming)
                                pre_other_blocked[pkg] = pblocked
                                pre_roaming[pkg] = proaming
                            }
                            "relation" -> {
                                val pkg = xml.getAttributeValue(null, "package")
                                val rel = xml.getAttributeValue(null, "related").split(",").toTypedArray()
                                pre_related[pkg] = rel
                            }
                            "type" -> {
                                val pkg = xml.getAttributeValue(null, "package")
                                val system = xml.getAttributeBooleanValue(null, "system", true)
                                pre_system[pkg] = system
                            }
                        }
                        eventType = xml.next()
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }

                // Build rule list
                val listRules: MutableList<Rule?> = ArrayList()
                val listPI = getPackages(context)
                val userId = Process.myUid() / 100000

                // Add root
                val root = PackageInfo()
                root.packageName = "root"
                root.versionCode = Build.VERSION.SDK_INT
                root.versionName = Build.VERSION.RELEASE
                root.applicationInfo = ApplicationInfo()
                root.applicationInfo.uid = 0
                root.applicationInfo.icon = 0
                listPI.add(root)

                // Add mediaserver
                val media = PackageInfo()
                media.packageName = "android.media"
                media.versionCode = Build.VERSION.SDK_INT
                media.versionName = Build.VERSION.RELEASE
                media.applicationInfo = ApplicationInfo()
                media.applicationInfo.uid = 1013 + userId * 100000
                media.applicationInfo.icon = 0
                listPI.add(media)

                // MulticastDNSResponder
                val mdr = PackageInfo()
                mdr.packageName = "android.multicast"
                mdr.versionCode = Build.VERSION.SDK_INT
                mdr.versionName = Build.VERSION.RELEASE
                mdr.applicationInfo = ApplicationInfo()
                mdr.applicationInfo.uid = 1020 + userId * 100000
                mdr.applicationInfo.icon = 0
                listPI.add(mdr)

                // Add GPS daemon
                val gps = PackageInfo()
                gps.packageName = "android.gps"
                gps.versionCode = Build.VERSION.SDK_INT
                gps.versionName = Build.VERSION.RELEASE
                gps.applicationInfo = ApplicationInfo()
                gps.applicationInfo.uid = 1021 + userId * 100000
                gps.applicationInfo.icon = 0
                listPI.add(gps)

                // Add DNS daemon
                val dns = PackageInfo()
                dns.packageName = "android.dns"
                dns.versionCode = Build.VERSION.SDK_INT
                dns.versionName = Build.VERSION.RELEASE
                dns.applicationInfo = ApplicationInfo()
                dns.applicationInfo.uid = 1051 + userId * 100000
                dns.applicationInfo.icon = 0
                listPI.add(dns)

                // Add nobody
                val nobody = PackageInfo()
                nobody.packageName = "nobody"
                nobody.versionCode = Build.VERSION.SDK_INT
                nobody.versionName = Build.VERSION.RELEASE
                nobody.applicationInfo = ApplicationInfo()
                nobody.applicationInfo.uid = 9999
                nobody.applicationInfo.icon = 0
                listPI.add(nobody)
                val dh: DatabaseHelper = DatabaseHelper.getInstance(context)
                for (info: PackageInfo in listPI) try {
                    // Skip self
                    if (info.applicationInfo.uid == Process.myUid()) continue
                    val rule = Rule(dh, info, context)
                    if (pre_system.containsKey(info.packageName)) rule.system = (pre_system[info.packageName])!!
                    if (info.applicationInfo.uid == Process.myUid()) rule.system = true
                    if (all ||
                            (((if (rule.system) show_system else show_user) &&
                                    (show_nointernet || rule.internet) &&
                                    (show_disabled || rule.enabled)))) {
                        rule.wifi_default = (if (pre_wifi_blocked.containsKey(info.packageName)) (pre_wifi_blocked[info.packageName])!! else default_wifi)
                        rule.other_default = (if (pre_other_blocked.containsKey(info.packageName)) (pre_other_blocked[info.packageName])!! else default_other)
                        rule.screen_wifi_default = default_screen_wifi
                        rule.screen_other_default = default_screen_other
                        rule.roaming_default = (if (pre_roaming.containsKey(info.packageName)) (pre_roaming[info.packageName])!! else default_roaming)
                        rule.wifi_blocked = (!(rule.system && !manage_system) && wifi.getBoolean(info.packageName, rule.wifi_default))
                        rule.other_blocked = (!(rule.system && !manage_system) && other.getBoolean(info.packageName, rule.other_default))
                        rule.screen_wifi = screen_wifi.getBoolean(info.packageName, rule.screen_wifi_default) && screen_on
                        rule.screen_other = screen_other.getBoolean(info.packageName, rule.screen_other_default) && screen_on
                        rule.roaming = roaming.getBoolean(info.packageName, rule.roaming_default)
                        rule.lockdown = lockdown.getBoolean(info.packageName, false)
                        rule.apply = apply.getBoolean(info.packageName, true)
                        rule.notify = notify.getBoolean(info.packageName, true)

                        // Related packages
                        val listPkg: MutableList<String> = ArrayList()
                        if (pre_related.containsKey(info.packageName)) listPkg.addAll(arrayListOf(pre_related[info.packageName].toString()))
                        for (pi: PackageInfo in listPI) if (pi.applicationInfo.uid == rule.uid && pi.packageName != rule.packageName) {
                            rule.relateduids = true
                            listPkg.add(pi.packageName)
                        }
                        rule.related = listPkg.toTypedArray()
                        rule.hosts = dh.getHostCount(rule.uid, true)
                        rule.updateChanged(default_wifi, default_other, default_roaming)
                        listRules.add(rule)
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }

                // Sort rule list
                val collator = Collator.getInstance(Locale.getDefault())
                collator.strength = Collator.SECONDARY // Case insensitive, process accents etc
                val sort = prefs.getString("sort", "name")
                if (("uid" == sort)) listRules.sortWith { rule, other ->
                    when {
                        rule!!.uid < other?.uid!! -> -1
                        rule.uid > other.uid -> 1
                        else -> {
                            val i = collator.compare(rule.name, other.name)
                            (if (i == 0) rule.packageName.compareTo(other.packageName) else i)
                        }
                    }
                } else listRules.sortWith { rule, other ->
                    if (all || rule!!.changed == other!!.changed) {
                        val i = collator.compare(rule!!.name, other!!.name)
                        return@sortWith (if (i == 0) rule.packageName.compareTo(other.packageName) else i)
                    }
                    return@sortWith (if (rule.changed) -1 else 1)
                }
                return listRules
            }
        }
    }

    init {
        when (info.applicationInfo.uid) {
            0 -> {
                name = context.getString(R.string.title_root)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1013 -> {
                name = context.getString(R.string.title_mediaserver)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1020 -> {
                name = "MulticastDNSResponder"
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1021 -> {
                name = context.getString(R.string.title_gpsdaemon)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1051 -> {
                name = context.getString(R.string.title_dnsdaemon)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            9999 -> {
                name = context.getString(R.string.title_nobody)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            else -> {
                var cursor: Cursor? = null
                try {
                    cursor = dh!!.getApp(packageName)
                    if (cursor.moveToNext()) {
                        name = cursor.getString(cursor.getColumnIndex("label"))
                        system = cursor.getInt(cursor.getColumnIndex("system")) > 0
                        internet = cursor.getInt(cursor.getColumnIndex("internet")) > 0
                        enabled = cursor.getInt(cursor.getColumnIndex("enabled")) > 0
                    } else {
                        name = getLabel(info, context)
                        system = isSystem(info.packageName, context)
                        internet = hasInternet(info.packageName, context)
                        enabled = isEnabled(info, context)
                        dh.addApp(packageName, name, system, internet, enabled)
                    }
                } finally {
                    cursor?.close()
                }
            }
        }
    }
}