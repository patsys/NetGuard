package eu.faircode.netguardimport

import android.Manifest
import android.annotation.TargetApi
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.*
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.preference.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.util.Log
import android.util.Xml
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.core.util.PatternsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.faircode.netguard.*
import eu.faircode.netguard.ActivitySettings
import eu.faircode.netguard.Util.DoubtListener
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import org.xmlpull.v1.XmlSerializer
import java.io.*
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

android.content.ServiceConnection
import android.os.IBinder
import kotlin.Throws
import org.json.JSONException
import android.os.Bundle
import org.json.JSONObject
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser
import android.os.Build
import android.content.pm.ApplicationInfo
import androidx.core.net.ConnectivityManagerCompat
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.os.PowerManager
import android.app.Activity
import android.annotation.TargetApi
import android.util.TypedValue
import android.app.ActivityManager.TaskDescription
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import eu.faircode.netguard.Util.DoubtListener
import android.widget.TextView
import android.app.ApplicationErrorReport
import android.app.ApplicationErrorReport.CrashInfo
import android.text.TextUtils
import android.os.AsyncTask
import eu.faircode.netguard.IPUtil.CIDR
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import android.util.Xml
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import androidx.appcompat.widget.SwitchCompat
import android.widget.CompoundButton
import android.widget.FilterQueryProvider
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView
import androidx.core.app.NavUtils
import android.widget.ImageButton
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import androidx.recyclerview.widget.RecyclerView
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.CheckBox
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.DecodeFormat
import androidx.core.widget.CompoundButtonCompat
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.text.Spanned
import androidx.core.app.NotificationManagerCompat
import android.widget.Filter.FilterResults
import android.content.res.TypedArray
import androidx.core.content.ContextCompat
import com.bumptech.glide.module.AppGlideModule
import android.app.AlarmManager
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.View.OnLongClickListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import android.text.method.LinkMovementMethod
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.text.SpannableString
import android.text.style.UnderlineSpan
import eu.faircode.netguard.DatabaseHelper.AccessChangedListener
import android.os.PowerManager.WakeLock
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.NotificationChannel
import android.database.sqlite.SQLiteOpenHelper
import eu.faircode.netguard.DatabaseHelper.ForwardChangedListener
import android.os.HandlerThread
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.app.IntentService
import android.telephony.PhoneStateListener
import android.os.ParcelFileDescriptor
import eu.faircode.netguard.ServiceSinkhole.IPKey
import eu.faircode.netguard.ServiceSinkhole.IPRule
import kotlin.jvm.Volatile
import android.os.Looper
import eu.faircode.netguard.ServiceSinkhole.StatsHandler
import eu.faircode.netguard.ServiceSinkhole.CommandHandler.StartFailedException
import org.json.JSONArray
import android.net.ConnectivityManager.NetworkCallback
import android.text.Spannable
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import kotlin.jvm.Synchronized
import android.service.quicksettings.TileService
import android.net.wifi.WifiConfiguration
import androidx.core.util.PatternsCompat
import android.content.res.AssetFileDescriptor
import eu.faircode.netguard.ActivitySettings.XmlImportHandler
import android.view.View.MeasureSpec
import kotlin.jvm.JvmOverloads
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.ArrayAdapter

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
*/   class ActivitySettings constructor() : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private var running: Boolean = false
    private var dialogFilter: AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        getFragmentManager().beginTransaction().replace(android.R.id.content, FragmentSettings()).commit()
        getSupportActionBar()!!.setTitle(R.string.menu_settings)
        running = true
    }

    private val preferenceScreen: PreferenceScreen
        private get() {
            return (getFragmentManager().findFragmentById(android.R.id.content) as PreferenceFragment).getPreferenceScreen()
        }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val screen: PreferenceScreen = preferenceScreen
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val cat_options: PreferenceGroup = (screen.findPreference("screen_options") as PreferenceGroup).findPreference("category_options") as PreferenceGroup
        val cat_network: PreferenceGroup = (screen.findPreference("screen_network_options") as PreferenceGroup).findPreference("category_network_options") as PreferenceGroup
        val cat_advanced: PreferenceGroup = (screen.findPreference("screen_advanced_options") as PreferenceGroup).findPreference("category_advanced_options") as PreferenceGroup
        val cat_stats: PreferenceGroup = (screen.findPreference("screen_stats") as PreferenceGroup).findPreference("category_stats") as PreferenceGroup
        val cat_backup: PreferenceGroup = (screen.findPreference("screen_backup") as PreferenceGroup).findPreference("category_backup") as PreferenceGroup

        // Handle auto enable
        val pref_auto_enable: Preference = screen.findPreference("auto_enable")
        pref_auto_enable.setTitle(getString(R.string.setting_auto, prefs.getString("auto_enable", "0")))

        // Handle screen delay
        val pref_screen_delay: Preference = screen.findPreference("screen_delay")
        pref_screen_delay.setTitle(getString(R.string.setting_delay, prefs.getString("screen_delay", "0")))

        // Handle theme
        val pref_screen_theme: Preference = screen.findPreference("theme")
        val theme: String? = prefs.getString("theme", "teal")
        val themeNames: Array<String> = getResources().getStringArray(R.array.themeNames)
        val themeValues: Array<String> = getResources().getStringArray(R.array.themeValues)
        for (i in themeNames.indices) if ((theme == themeValues.get(i))) {
            pref_screen_theme.setTitle(getString(R.string.setting_theme, themeNames.get(i)))
            break
        }

        // Wi-Fi home
        val pref_wifi_homes: MultiSelectListPreference = screen.findPreference("wifi_homes") as MultiSelectListPreference
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) cat_network.removePreference(pref_wifi_homes) else {
            val ssids: Set<String?>? = prefs.getStringSet("wifi_homes", HashSet())
            if (ssids!!.size > 0) pref_wifi_homes.setTitle(getString(R.string.setting_wifi_home, TextUtils.join(", ", (ssids)))) else pref_wifi_homes.setTitle(getString(R.string.setting_wifi_home, "-"))
            val wm: WifiManager = getApplicationContext().getSystemService(WIFI_SERVICE) as WifiManager
            val listSSID: MutableList<CharSequence?> = ArrayList()
            val configs: List<WifiConfiguration>? = wm.getConfiguredNetworks()
            if (configs != null) for (config: WifiConfiguration in configs) listSSID.add(if (config.SSID == null) "NULL" else config.SSID)
            for (ssid: String? in ssids) if (!listSSID.contains(ssid)) listSSID.add(ssid)
            pref_wifi_homes.setEntries(listSSID.toTypedArray())
            pref_wifi_homes.setEntryValues(listSSID.toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pref_handover: TwoStatePreference = screen.findPreference("handover") as TwoStatePreference
            cat_advanced.removePreference(pref_handover)
        }
        val pref_reset_usage: Preference = screen.findPreference("reset_usage")
        pref_reset_usage.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                Util.areYouSure(this@ActivitySettings, R.string.setting_reset_usage, object : DoubtListener {
                    public override fun onSure() {
                        object : AsyncTask<Any?, Any?, Throwable?>() {
                            protected override fun doInBackground(vararg objects: Any): Throwable? {
                                try {
                                    DatabaseHelper.Companion.getInstance(this@ActivitySettings)!!.resetUsage(-1)
                                    return null
                                } catch (ex: Throwable) {
                                    return ex
                                }
                            }

                            override fun onPostExecute(ex: Throwable?) {
                                if (ex == null) Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show() else Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                            }
                        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                    }
                })
                return false
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pref_reload_onconnectivity: TwoStatePreference = screen.findPreference("reload_onconnectivity") as TwoStatePreference
            pref_reload_onconnectivity.setChecked(true)
            pref_reload_onconnectivity.setEnabled(false)
        }

        // Handle port forwarding
        val pref_forwarding: Preference = screen.findPreference("forwarding")
        pref_forwarding.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                startActivity(Intent(this@ActivitySettings, ActivityForwarding::class.java))
                return true
            }
        })
        val can: Boolean = Util.canFilter(this)
        val pref_log_app: TwoStatePreference = screen.findPreference("log_app") as TwoStatePreference
        val pref_filter: TwoStatePreference = screen.findPreference("filter") as TwoStatePreference
        pref_log_app.setEnabled(can)
        pref_filter.setEnabled(can)
        if (!can) {
            pref_log_app.setSummary(R.string.msg_unavailable)
            pref_filter.setSummary(R.string.msg_unavailable)
        }

        // VPN parameters
        screen.findPreference("vpn4").setTitle(getString(R.string.setting_vpn4, prefs.getString("vpn4", "10.1.10.1")))
        screen.findPreference("vpn6").setTitle(getString(R.string.setting_vpn6, prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1")))
        val pref_dns1: EditTextPreference = screen.findPreference("dns") as EditTextPreference
        val pref_dns2: EditTextPreference = screen.findPreference("dns2") as EditTextPreference
        val pref_validate: EditTextPreference = screen.findPreference("validate") as EditTextPreference
        val pref_ttl: EditTextPreference = screen.findPreference("ttl") as EditTextPreference
        pref_dns1.setTitle(getString(R.string.setting_dns, prefs.getString("dns", "-")))
        pref_dns2.setTitle(getString(R.string.setting_dns, prefs.getString("dns2", "-")))
        pref_validate.setTitle(getString(R.string.setting_validate, prefs.getString("validate", "www.google.com")))
        pref_ttl.setTitle(getString(R.string.setting_ttl, prefs.getString("ttl", "259200")))

        // SOCKS5 parameters
        screen.findPreference("socks5_addr").setTitle(getString(R.string.setting_socks5_addr, prefs.getString("socks5_addr", "-")))
        screen.findPreference("socks5_port").setTitle(getString(R.string.setting_socks5_port, prefs.getString("socks5_port", "-")))
        screen.findPreference("socks5_username").setTitle(getString(R.string.setting_socks5_username, prefs.getString("socks5_username", "-")))
        screen.findPreference("socks5_password").setTitle(getString(R.string.setting_socks5_password, if (TextUtils.isEmpty(prefs.getString("socks5_username", ""))) "-" else "*****"))

        // PCAP parameters
        screen.findPreference("pcap_record_size").setTitle(getString(R.string.setting_pcap_record_size, prefs.getString("pcap_record_size", "64")))
        screen.findPreference("pcap_file_size").setTitle(getString(R.string.setting_pcap_file_size, prefs.getString("pcap_file_size", "2")))

        // Watchdog
        screen.findPreference("watchdog").setTitle(getString(R.string.setting_watchdog, prefs.getString("watchdog", "0")))

        // Show resolved
        val pref_show_resolved: Preference = screen.findPreference("show_resolved")
        if (Util.isPlayStoreInstall(this)) cat_advanced.removePreference(pref_show_resolved) else pref_show_resolved.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                startActivity(Intent(this@ActivitySettings, ActivityDns::class.java))
                return true
            }
        })

        // Handle stats
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cat_stats.removePreference(screen.findPreference("show_top"))
        val pref_stats_frequency: EditTextPreference = screen.findPreference("stats_frequency") as EditTextPreference
        val pref_stats_samples: EditTextPreference = screen.findPreference("stats_samples") as EditTextPreference
        pref_stats_frequency.setTitle(getString(R.string.setting_stats_frequency, prefs.getString("stats_frequency", "1000")))
        pref_stats_samples.setTitle(getString(R.string.setting_stats_samples, prefs.getString("stats_samples", "90")))

        // Handle export
        val pref_export: Preference = screen.findPreference("export")
        pref_export.setEnabled(intentCreateExport.resolveActivity(getPackageManager()) != null)
        pref_export.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                startActivityForResult(intentCreateExport, ActivitySettings.Companion.REQUEST_EXPORT)
                return true
            }
        })

        // Handle import
        val pref_import: Preference = screen.findPreference("import")
        pref_import.setEnabled(intentOpenExport.resolveActivity(getPackageManager()) != null)
        pref_import.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                startActivityForResult(intentOpenExport, ActivitySettings.Companion.REQUEST_IMPORT)
                return true
            }
        })

        // Hosts file settings
        val pref_block_domains: Preference = screen.findPreference("use_hosts")
        val pref_rcode: EditTextPreference = screen.findPreference("rcode") as EditTextPreference
        val pref_hosts_import: Preference = screen.findPreference("hosts_import")
        val pref_hosts_import_append: Preference = screen.findPreference("hosts_import_append")
        val pref_hosts_url: EditTextPreference = screen.findPreference("hosts_url") as EditTextPreference
        val pref_hosts_download: Preference = screen.findPreference("hosts_download")
        pref_rcode.setTitle(getString(R.string.setting_rcode, prefs.getString("rcode", "3")))
        if (Util.isPlayStoreInstall(this) || !Util.hasValidFingerprint(this)) cat_options.removePreference(screen.findPreference("update_check"))
        if (Util.isPlayStoreInstall(this)) {
            Log.i(ActivitySettings.Companion.TAG, "Play store install")
            cat_advanced.removePreference(pref_block_domains)
            cat_advanced.removePreference(pref_rcode)
            cat_advanced.removePreference(pref_forwarding)
            cat_backup.removePreference(pref_hosts_import)
            cat_backup.removePreference(pref_hosts_import_append)
            cat_backup.removePreference(pref_hosts_url)
            cat_backup.removePreference(pref_hosts_download)
        } else {
            val last_import: String? = prefs.getString("hosts_last_import", null)
            val last_download: String? = prefs.getString("hosts_last_download", null)
            if (last_import != null) pref_hosts_import.setSummary(getString(R.string.msg_import_last, last_import))
            if (last_download != null) pref_hosts_download.setSummary(getString(R.string.msg_download_last, last_download))

            // Handle hosts import
            // https://github.com/Free-Software-for-Android/AdAway/wiki/HostsSources
            pref_hosts_import.setEnabled(intentOpenHosts.resolveActivity(getPackageManager()) != null)
            pref_hosts_import.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
                public override fun onPreferenceClick(preference: Preference): Boolean {
                    startActivityForResult(intentOpenHosts, ActivitySettings.Companion.REQUEST_HOSTS)
                    return true
                }
            })
            pref_hosts_import_append.setEnabled(pref_hosts_import.isEnabled())
            pref_hosts_import_append.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
                public override fun onPreferenceClick(preference: Preference): Boolean {
                    startActivityForResult(intentOpenHosts, ActivitySettings.Companion.REQUEST_HOSTS_APPEND)
                    return true
                }
            })

            // Handle hosts file download
            pref_hosts_url.setSummary(pref_hosts_url.getText())
            pref_hosts_download.setOnPreferenceClickListener(object : Preference.OnPreferenceClickListener {
                public override fun onPreferenceClick(preference: Preference): Boolean {
                    val tmp: File = File(getFilesDir(), "hosts.tmp")
                    val hosts: File = File(getFilesDir(), "hosts.txt")
                    val pref_hosts_url: EditTextPreference = screen.findPreference("hosts_url") as EditTextPreference
                    var hosts_url: String = pref_hosts_url.getText()
                    if (("https://www.netguard.me/hosts" == hosts_url)) hosts_url = BuildConfig.HOSTS_FILE_URI
                    try {
                        DownloadTask(this@ActivitySettings, URL(hosts_url), tmp, object : DownloadTask.Listener {
                            public override fun onCompleted() {
                                if (hosts.exists()) hosts.delete()
                                tmp.renameTo(hosts)
                                val last: String = SimpleDateFormat.getDateTimeInstance().format(Date().getTime())
                                prefs.edit().putString("hosts_last_download", last).apply()
                                if (running) {
                                    pref_hosts_download.setSummary(getString(R.string.msg_download_last, last))
                                    Toast.makeText(this@ActivitySettings, R.string.msg_downloaded, Toast.LENGTH_LONG).show()
                                }
                                ServiceSinkhole.Companion.reload("hosts file download", this@ActivitySettings, false)
                            }

                            public override fun onCancelled() {
                                if (tmp.exists()) tmp.delete()
                            }

                            public override fun onException(ex: Throwable) {
                                if (tmp.exists()) tmp.delete()
                                if (running) Toast.makeText(this@ActivitySettings, ex.message, Toast.LENGTH_LONG).show()
                            }
                        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                    } catch (ex: MalformedURLException) {
                        Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                    return true
                }
            })
        }

        // Development
        if (!Util.isDebuggable(this)) screen.removePreference(screen.findPreference("screen_development"))

        // Handle technical info
        val listener: Preference.OnPreferenceClickListener = object : Preference.OnPreferenceClickListener {
            public override fun onPreferenceClick(preference: Preference): Boolean {
                updateTechnicalInfo()
                return true
            }
        }

        // Technical info
        val pref_technical_info: Preference = screen.findPreference("technical_info")
        val pref_technical_network: Preference = screen.findPreference("technical_network")
        pref_technical_info.setEnabled(ActivitySettings.Companion.INTENT_VPN_SETTINGS.resolveActivity(getPackageManager()) != null)
        pref_technical_info.setIntent(ActivitySettings.Companion.INTENT_VPN_SETTINGS)
        pref_technical_info.setOnPreferenceClickListener(listener)
        pref_technical_network.setOnPreferenceClickListener(listener)
        updateTechnicalInfo()
        markPro(screen.findPreference("theme"), ActivityPro.Companion.SKU_THEME)
        markPro(screen.findPreference("install"), ActivityPro.Companion.SKU_NOTIFY)
        markPro(screen.findPreference("show_stats"), ActivityPro.Companion.SKU_SPEED)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions(null)

        // Listen for preference changes
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Listen for interactive state changes
        val ifInteractive: IntentFilter = IntentFilter()
        ifInteractive.addAction(Intent.ACTION_SCREEN_ON)
        ifInteractive.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(interactiveStateReceiver, ifInteractive)

        // Listen for connectivity updates
        val ifConnectivity: IntentFilter = IntentFilter()
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityChangedReceiver, ifConnectivity)
    }

    override fun onPause() {
        super.onPause()
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(interactiveStateReceiver)
        unregisterReceiver(connectivityChangedReceiver)
    }

    override fun onDestroy() {
        running = false
        if (dialogFilter != null) {
            dialogFilter!!.dismiss()
            dialogFilter = null
        }
        super.onDestroy()
    }

    public override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.getItemId()) {
            android.R.id.home -> {
                Log.i(ActivitySettings.Companion.TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        // Pro features
        if (("theme" == name)) {
            if (!("teal" == prefs.getString(name, "teal")) && !IAB.Companion.isPurchased(ActivityPro.Companion.SKU_THEME, this)) {
                prefs.edit().putString(name, "teal").apply()
                (preferenceScreen.findPreference(name) as ListPreference).setValue("teal")
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
        } else if (("install" == name)) {
            if (prefs.getBoolean(name, false) && !IAB.Companion.isPurchased(ActivityPro.Companion.SKU_NOTIFY, this)) {
                prefs.edit().putBoolean(name, false).apply()
                (preferenceScreen.findPreference(name) as TwoStatePreference).setChecked(false)
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
        } else if (("show_stats" == name)) {
            if (prefs.getBoolean(name, false) && !IAB.Companion.isPurchased(ActivityPro.Companion.SKU_SPEED, this)) {
                prefs.edit().putBoolean(name, false).apply()
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
            (preferenceScreen.findPreference(name) as TwoStatePreference).setChecked(prefs.getBoolean(name, false))
        }
        val value: Any? = prefs.getAll().get(name)
        if (value is String && ("" == value)) prefs.edit().remove(name).apply()

        // Dependencies
        if (("screen_on" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("whitelist_wifi" == name) || ("screen_wifi" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("whitelist_other" == name) || ("screen_other" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("whitelist_roaming" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("auto_enable" == name)) preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_auto, prefs.getString(name, "0"))) else if (("screen_delay" == name)) preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_delay, prefs.getString(name, "0"))) else if (("theme" == name) || ("dark_theme" == name)) recreate() else if (("subnet" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("tethering" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("lan" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("ip6" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("wifi_homes" == name)) {
            val pref_wifi_homes: MultiSelectListPreference = preferenceScreen.findPreference(name) as MultiSelectListPreference
            val ssid: Set<String?>? = prefs.getStringSet(name, HashSet())
            if (ssid!!.size > 0) pref_wifi_homes.setTitle(getString(R.string.setting_wifi_home, TextUtils.join(", ", (ssid)))) else pref_wifi_homes.setTitle(getString(R.string.setting_wifi_home, "-"))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("use_metered" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if ((("unmetered_2g" == name) || ("unmetered_3g" == name) || ("unmetered_4g" == name))) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("national_roaming" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("eu_roaming" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("disable_on_call" == name)) {
            if (prefs.getBoolean(name, false)) {
                if (checkPermissions(name)) ServiceSinkhole.Companion.reload("changed " + name, this, false)
            } else ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("lockdown_wifi" == name) || ("lockdown_other" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("manage_system" == name)) {
            val manage: Boolean = prefs.getBoolean(name, false)
            if (!manage) prefs.edit().putBoolean("show_user", true).apply()
            prefs.edit().putBoolean("show_system", manage).apply()
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("log_app" == name)) {
            val ruleset: Intent = Intent(ActivityMain.Companion.ACTION_RULES_CHANGED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(ruleset)
        } else if (("filter" == name)) {
            // Show dialog
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && prefs.getBoolean(name, false)) {
                val inflater: LayoutInflater = LayoutInflater.from(this@ActivitySettings)
                val view: View = inflater.inflate(R.layout.filter, null, false)
                dialogFilter = AlertDialog.Builder(this@ActivitySettings)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.yes, object : DialogInterface.OnClickListener {
                            public override fun onClick(dialog: DialogInterface, which: Int) {
                                // Do nothing
                            }
                        })
                        .setOnDismissListener(object : DialogInterface.OnDismissListener {
                            public override fun onDismiss(dialogInterface: DialogInterface) {
                                dialogFilter = null
                            }
                        })
                        .create()
                dialogFilter!!.show()
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && !prefs.getBoolean(name, false)) {
                prefs.edit().putBoolean(name, true).apply()
                Toast.makeText(this@ActivitySettings, R.string.msg_filter4, Toast.LENGTH_SHORT).show()
            }
            (preferenceScreen.findPreference(name) as TwoStatePreference).setChecked(prefs.getBoolean(name, false))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("use_hosts" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("vpn4" == name)) {
            val vpn4: String? = prefs.getString(name, null)
            try {
                checkAddress(vpn4, false)
                prefs.edit().putString(name, vpn4!!.trim({ it <= ' ' })).apply()
            } catch (ex: Throwable) {
                prefs.edit().remove(name).apply()
                (preferenceScreen.findPreference(name) as EditTextPreference).setText(null)
                if (!TextUtils.isEmpty(vpn4)) Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
            }
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_vpn4, prefs.getString(name, "10.1.10.1")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("vpn6" == name)) {
            val vpn6: String? = prefs.getString(name, null)
            try {
                checkAddress(vpn6, false)
                prefs.edit().putString(name, vpn6!!.trim({ it <= ' ' })).apply()
            } catch (ex: Throwable) {
                prefs.edit().remove(name).apply()
                (preferenceScreen.findPreference(name) as EditTextPreference).setText(null)
                if (!TextUtils.isEmpty(vpn6)) Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
            }
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_vpn6, prefs.getString(name, "fd00:1:fd00:1:fd00:1:fd00:1")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("dns" == name) || ("dns2" == name)) {
            val dns: String? = prefs.getString(name, null)
            try {
                checkAddress(dns, true)
                prefs.edit().putString(name, dns!!.trim({ it <= ' ' })).apply()
            } catch (ex: Throwable) {
                prefs.edit().remove(name).apply()
                (preferenceScreen.findPreference(name) as EditTextPreference).setText(null)
                if (!TextUtils.isEmpty(dns)) Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
            }
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_dns, prefs.getString(name, "-")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("validate" == name)) {
            val host: String? = prefs.getString(name, "www.google.com")
            try {
                checkDomain(host)
                prefs.edit().putString(name, host!!.trim({ it <= ' ' })).apply()
            } catch (ex: Throwable) {
                prefs.edit().remove(name).apply()
                (preferenceScreen.findPreference(name) as EditTextPreference).setText(null)
                if (!TextUtils.isEmpty(host)) Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
            }
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_validate, prefs.getString(name, "www.google.com")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("ttl" == name)) preferenceScreen.findPreference(name).setTitle(
                getString(R.string.setting_ttl, prefs.getString(name, "259200"))) else if (("rcode" == name)) {
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_rcode, prefs.getString(name, "3")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("socks5_enabled" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false) else if (("socks5_addr" == name)) {
            val socks5_addr: String? = prefs.getString(name, null)
            try {
                if (!TextUtils.isEmpty(socks5_addr) && !Util.isNumericAddress(socks5_addr)) throw IllegalArgumentException("Bad address")
            } catch (ex: Throwable) {
                prefs.edit().remove(name).apply()
                (preferenceScreen.findPreference(name) as EditTextPreference).setText(null)
                if (!TextUtils.isEmpty(socks5_addr)) Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
            }
            preferenceScreen.findPreference(name).setTitle(
                    getString(R.string.setting_socks5_addr, prefs.getString(name, "-")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("socks5_port" == name)) {
            preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_socks5_port, prefs.getString(name, "-")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("socks5_username" == name)) {
            preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_socks5_username, prefs.getString(name, "-")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("socks5_password" == name)) {
            preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_socks5_password, if (TextUtils.isEmpty(prefs.getString(name, ""))) "-" else "*****"))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("pcap_record_size" == name) || ("pcap_file_size" == name)) {
            if (("pcap_record_size" == name)) preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_pcap_record_size, prefs.getString(name, "64"))) else preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_pcap_file_size, prefs.getString(name, "2")))
            ServiceSinkhole.Companion.setPcap(false, this)
            val pcap_file: File = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
            if (pcap_file.exists() && !pcap_file.delete()) Log.w(ActivitySettings.Companion.TAG, "Delete PCAP failed")
            if (prefs.getBoolean("pcap", false)) ServiceSinkhole.Companion.setPcap(true, this)
        } else if (("watchdog" == name)) {
            preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_watchdog, prefs.getString(name, "0")))
            ServiceSinkhole.Companion.reload("changed " + name, this, false)
        } else if (("show_stats" == name)) ServiceSinkhole.Companion.reloadStats("changed " + name, this) else if (("stats_frequency" == name)) preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_stats_frequency, prefs.getString(name, "1000"))) else if (("stats_samples" == name)) preferenceScreen.findPreference(name).setTitle(getString(R.string.setting_stats_samples, prefs.getString(name, "90"))) else if (("hosts_url" == name)) preferenceScreen.findPreference(name).setSummary(prefs.getString(name, BuildConfig.HOSTS_FILE_URI)) else if (("loglevel" == name)) ServiceSinkhole.Companion.reload("changed " + name, this, false)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun checkPermissions(name: String?): Boolean {
        val screen: PreferenceScreen = preferenceScreen
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // Check if permission was revoked
        if ((name == null || ("disable_on_call" == name)) && prefs.getBoolean("disable_on_call", false)) if (!Util.hasPhoneStatePermission(this)) {
            prefs.edit().putBoolean("disable_on_call", false).apply()
            (screen.findPreference("disable_on_call") as TwoStatePreference).setChecked(false)
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), ActivitySettings.Companion.REQUEST_CALL)
            if (name != null) return false
        }
        return true
    }

    public override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        val screen: PreferenceScreen = preferenceScreen
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val granted: Boolean = (grantResults.size > 0 && grantResults.get(0) == PackageManager.PERMISSION_GRANTED)
        if (requestCode == ActivitySettings.Companion.REQUEST_CALL) {
            prefs.edit().putBoolean("disable_on_call", granted).apply()
            (screen.findPreference("disable_on_call") as TwoStatePreference).setChecked(granted)
        }
        if (granted) ServiceSinkhole.Companion.reload("permission granted", this, false)
    }

    @Throws(IllegalArgumentException::class, UnknownHostException::class)
    private fun checkAddress(address: String?, allow_local: Boolean) {
        var address: String? = address
        if (address != null) address = address.trim({ it <= ' ' })
        if (TextUtils.isEmpty(address)) throw IllegalArgumentException("Bad address")
        if (!Util.isNumericAddress(address)) throw IllegalArgumentException("Bad address")
        if (!allow_local) {
            val iaddr: InetAddress = InetAddress.getByName(address)
            if (iaddr.isLoopbackAddress() || iaddr.isAnyLocalAddress()) throw IllegalArgumentException("Bad address")
        }
    }

    @Throws(IllegalArgumentException::class, UnknownHostException::class)
    private fun checkDomain(address: String?) {
        var address: String? = address
        if (address != null) address = address.trim({ it <= ' ' })
        if (TextUtils.isEmpty(address)) throw IllegalArgumentException("Bad address")
        if (Util.isNumericAddress(address)) throw IllegalArgumentException("Bad address")
        if (!PatternsCompat.DOMAIN_NAME.matcher(address).matches()) throw IllegalArgumentException("Bad address")
    }

    private val interactiveStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        public override fun onReceive(context: Context, intent: Intent) {
            Util.logExtras(intent)
            updateTechnicalInfo()
        }
    }
    private val connectivityChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        public override fun onReceive(context: Context, intent: Intent) {
            Util.logExtras(intent)
            updateTechnicalInfo()
        }
    }

    private fun markPro(pref: Preference, sku: String?) {
        if (sku == null || !IAB.Companion.isPurchased(sku, this)) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val dark: Boolean = prefs.getBoolean("dark_theme", false)
            val ssb: SpannableStringBuilder = SpannableStringBuilder("  " + pref.getTitle())
            ssb.setSpan(ImageSpan(this, if (dark) R.drawable.ic_shopping_cart_white_24dp else R.drawable.ic_shopping_cart_black_24dp), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pref.setTitle(ssb)
        }
    }

    private fun updateTechnicalInfo() {
        val screen: PreferenceScreen = preferenceScreen
        val pref_technical_info: Preference = screen.findPreference("technical_info")
        val pref_technical_network: Preference = screen.findPreference("technical_network")
        pref_technical_info.setSummary(Util.getGeneralInfo(this))
        pref_technical_network.setSummary(Util.getNetworkInfo(this))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(ActivitySettings.Companion.TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK))
        if (requestCode == ActivitySettings.Companion.REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null) handleExport(data)
        } else if (requestCode == ActivitySettings.Companion.REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null) handleImport(data)
        } else if (requestCode == ActivitySettings.Companion.REQUEST_HOSTS) {
            if (resultCode == RESULT_OK && data != null) handleHosts(data, false)
        } else if (requestCode == ActivitySettings.Companion.REQUEST_HOSTS_APPEND) {
            if (resultCode == RESULT_OK && data != null) handleHosts(data, true)
        } else {
            Log.w(ActivitySettings.Companion.TAG, "Unknown activity result request=" + requestCode)
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // text/xml
    private val intentCreateExport: Intent
        private get() {
            val intent: Intent
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                    intent = Intent("org.openintents.action.PICK_DIRECTORY")
                } else {
                    intent = Intent(Intent.ACTION_VIEW)
                    intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager"))
                }
            } else {
                intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType("*/*") // text/xml
                intent.putExtra(Intent.EXTRA_TITLE, "netguard_" + SimpleDateFormat("yyyyMMdd").format(Date().getTime()) + ".xml")
            }
            return intent
        }

    // text/xml
    private val intentOpenExport: Intent
        private get() {
            val intent: Intent
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) intent = Intent(Intent.ACTION_GET_CONTENT) else intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("*/*") // text/xml
            return intent
        }

    // text/plain
    private val intentOpenHosts: Intent
        private get() {
            val intent: Intent
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) intent = Intent(Intent.ACTION_GET_CONTENT) else intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("*/*") // text/plain
            return intent
        }

    private fun handleExport(data: Intent) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            protected override fun doInBackground(vararg objects: Any): Throwable? {
                var out: OutputStream? = null
                try {
                    var target: Uri? = data.getData()
                    if (data.hasExtra("org.openintents.extra.DIR_PATH")) target = Uri.parse(target.toString() + "/netguard_" + SimpleDateFormat("yyyyMMdd").format(Date().getTime()) + ".xml")
                    Log.i(ActivitySettings.Companion.TAG, "Writing URI=" + target)
                    out = getContentResolver().openOutputStream((target)!!)
                    xmlExport(out)
                    return null
                } catch (ex: Throwable) {
                    Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    if (out != null) try {
                        out.close()
                    } catch (ex: IOException) {
                        Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show() else Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun handleHosts(data: Intent, append: Boolean) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            protected override fun doInBackground(vararg objects: Any): Throwable? {
                val hosts: File = File(getFilesDir(), "hosts.txt")
                var out: FileOutputStream? = null
                var `in`: InputStream? = null
                try {
                    Log.i(ActivitySettings.Companion.TAG, "Reading URI=" + data.getData())
                    val resolver: ContentResolver = getContentResolver()
                    val streamTypes: Array<String>? = resolver.getStreamTypes((data.getData())!!, "*/*")
                    val streamType: String = (if (streamTypes == null || streamTypes.size == 0) "*/*" else streamTypes.get(0))
                    val descriptor: AssetFileDescriptor? = resolver.openTypedAssetFileDescriptor((data.getData())!!, streamType, null)
                    `in` = descriptor!!.createInputStream()
                    out = FileOutputStream(hosts, append)
                    var len: Int
                    var total: Long = 0
                    val buf: ByteArray = ByteArray(4096)
                    while ((`in`.read(buf).also({ len = it })) > 0) {
                        out.write(buf, 0, len)
                        total += len.toLong()
                    }
                    Log.i(ActivitySettings.Companion.TAG, "Copied bytes=" + total)
                    return null
                } catch (ex: Throwable) {
                    Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    if (out != null) try {
                        out.close()
                    } catch (ex: IOException) {
                        Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    if (`in` != null) try {
                        `in`.close()
                    } catch (ex: IOException) {
                        Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) {
                        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ActivitySettings)
                        val last: String = SimpleDateFormat.getDateTimeInstance().format(Date().getTime())
                        prefs.edit().putString("hosts_last_import", last).apply()
                        if (running) {
                            preferenceScreen.findPreference("hosts_import").setSummary(getString(R.string.msg_import_last, last))
                            Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                        }
                        ServiceSinkhole.Companion.reload("hosts import", this@ActivitySettings, false)
                    } else Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun handleImport(data: Intent) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            protected override fun doInBackground(vararg objects: Any): Throwable? {
                var `in`: InputStream? = null
                try {
                    Log.i(ActivitySettings.Companion.TAG, "Reading URI=" + data.getData())
                    val resolver: ContentResolver = getContentResolver()
                    val streamTypes: Array<String>? = resolver.getStreamTypes((data.getData())!!, "*/*")
                    val streamType: String = (if (streamTypes == null || streamTypes.size == 0) "*/*" else streamTypes.get(0))
                    val descriptor: AssetFileDescriptor? = resolver.openTypedAssetFileDescriptor((data.getData())!!, streamType, null)
                    `in` = descriptor!!.createInputStream()
                    xmlImport(`in`)
                    return null
                } catch (ex: Throwable) {
                    Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    if (`in` != null) try {
                        `in`.close()
                    } catch (ex: IOException) {
                        Log.e(ActivitySettings.Companion.TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) {
                        Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                        ServiceSinkhole.Companion.reloadStats("import", this@ActivitySettings)
                        // Update theme, request permissions
                        recreate()
                    } else Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    @Throws(IOException::class)
    private fun xmlExport(out: OutputStream?) {
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument(null, true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.startTag(null, "netguard")
        serializer.startTag(null, "application")
        xmlExport(PreferenceManager.getDefaultSharedPreferences(this), serializer)
        serializer.endTag(null, "application")
        serializer.startTag(null, "wifi")
        xmlExport(getSharedPreferences("wifi", MODE_PRIVATE), serializer)
        serializer.endTag(null, "wifi")
        serializer.startTag(null, "mobile")
        xmlExport(getSharedPreferences("other", MODE_PRIVATE), serializer)
        serializer.endTag(null, "mobile")
        serializer.startTag(null, "screen_wifi")
        xmlExport(getSharedPreferences("screen_wifi", MODE_PRIVATE), serializer)
        serializer.endTag(null, "screen_wifi")
        serializer.startTag(null, "screen_other")
        xmlExport(getSharedPreferences("screen_other", MODE_PRIVATE), serializer)
        serializer.endTag(null, "screen_other")
        serializer.startTag(null, "roaming")
        xmlExport(getSharedPreferences("roaming", MODE_PRIVATE), serializer)
        serializer.endTag(null, "roaming")
        serializer.startTag(null, "lockdown")
        xmlExport(getSharedPreferences("lockdown", MODE_PRIVATE), serializer)
        serializer.endTag(null, "lockdown")
        serializer.startTag(null, "apply")
        xmlExport(getSharedPreferences("apply", MODE_PRIVATE), serializer)
        serializer.endTag(null, "apply")
        serializer.startTag(null, "notify")
        xmlExport(getSharedPreferences("notify", MODE_PRIVATE), serializer)
        serializer.endTag(null, "notify")
        serializer.startTag(null, "filter")
        filterExport(serializer)
        serializer.endTag(null, "filter")
        serializer.startTag(null, "forward")
        forwardExport(serializer)
        serializer.endTag(null, "forward")
        serializer.endTag(null, "netguard")
        serializer.endDocument()
        serializer.flush()
    }

    @Throws(IOException::class)
    private fun xmlExport(prefs: SharedPreferences, serializer: XmlSerializer) {
        val settings: Map<String, *> = prefs.getAll()
        for (key: String in settings.keys) {
            val value: Any? = settings.get(key)
            if (("imported" == key)) continue
            if (value is Boolean) {
                serializer.startTag(null, "setting")
                serializer.attribute(null, "key", key)
                serializer.attribute(null, "type", "boolean")
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "setting")
            } else if (value is Int) {
                serializer.startTag(null, "setting")
                serializer.attribute(null, "key", key)
                serializer.attribute(null, "type", "integer")
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "setting")
            } else if (value is String) {
                serializer.startTag(null, "setting")
                serializer.attribute(null, "key", key)
                serializer.attribute(null, "type", "string")
                serializer.attribute(null, "value", value.toString())
                serializer.endTag(null, "setting")
            } else if (value is Set<*>) {
                val set: Set<String?> = value as Set<String?>
                serializer.startTag(null, "setting")
                serializer.attribute(null, "key", key)
                serializer.attribute(null, "type", "set")
                serializer.attribute(null, "value", TextUtils.join("\n", set))
                serializer.endTag(null, "setting")
            } else Log.e(ActivitySettings.Companion.TAG, "Unknown key=" + key)
        }
    }

    @Throws(IOException::class)
    private fun filterExport(serializer: XmlSerializer) {
        DatabaseHelper.Companion.getInstance(this)!!.getAccess().use({ cursor ->
            val colUid: Int = cursor.getColumnIndex("uid")
            val colVersion: Int = cursor.getColumnIndex("version")
            val colProtocol: Int = cursor.getColumnIndex("protocol")
            val colDAddr: Int = cursor.getColumnIndex("daddr")
            val colDPort: Int = cursor.getColumnIndex("dport")
            val colTime: Int = cursor.getColumnIndex("time")
            val colBlock: Int = cursor.getColumnIndex("block")
            while (cursor.moveToNext()) for (pkg: String? in getPackages(cursor.getInt(colUid))) {
                serializer.startTag(null, "rule")
                serializer.attribute(null, "pkg", pkg)
                serializer.attribute(null, "version", Integer.toString(cursor.getInt(colVersion)))
                serializer.attribute(null, "protocol", Integer.toString(cursor.getInt(colProtocol)))
                serializer.attribute(null, "daddr", cursor.getString(colDAddr))
                serializer.attribute(null, "dport", Integer.toString(cursor.getInt(colDPort)))
                serializer.attribute(null, "time", java.lang.Long.toString(cursor.getLong(colTime)))
                serializer.attribute(null, "block", Integer.toString(cursor.getInt(colBlock)))
                serializer.endTag(null, "rule")
            }
        })
    }

    @Throws(IOException::class)
    private fun forwardExport(serializer: XmlSerializer) {
        DatabaseHelper.Companion.getInstance(this).getForwarding().use({ cursor ->
            val colProtocol: Int = cursor.getColumnIndex("protocol")
            val colDPort: Int = cursor.getColumnIndex("dport")
            val colRAddr: Int = cursor.getColumnIndex("raddr")
            val colRPort: Int = cursor.getColumnIndex("rport")
            val colRUid: Int = cursor.getColumnIndex("ruid")
            while (cursor.moveToNext()) for (pkg: String? in getPackages(cursor.getInt(colRUid))) {
                serializer.startTag(null, "port")
                serializer.attribute(null, "pkg", pkg)
                serializer.attribute(null, "protocol", Integer.toString(cursor.getInt(colProtocol)))
                serializer.attribute(null, "dport", Integer.toString(cursor.getInt(colDPort)))
                serializer.attribute(null, "raddr", cursor.getString(colRAddr))
                serializer.attribute(null, "rport", Integer.toString(cursor.getInt(colRPort)))
                serializer.endTag(null, "port")
            }
        })
    }

    private fun getPackages(uid: Int): Array<String?> {
        if (uid == 0) return arrayOf("root") else if (uid == 1013) return arrayOf("mediaserver") else if (uid == 9999) return arrayOf("nobody") else {
            val pkgs: Array<String?>? = getPackageManager().getPackagesForUid(uid)
            if (pkgs == null) return arrayOfNulls(0) else return pkgs
        }
    }

    @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
    private fun xmlImport(`in`: InputStream?) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        prefs.edit().putBoolean("enabled", false).apply()
        ServiceSinkhole.Companion.stop("import", this, false)
        val reader: XMLReader = SAXParserFactory.newInstance().newSAXParser().getXMLReader()
        val handler: XmlImportHandler = XmlImportHandler(this)
        reader.setContentHandler(handler)
        reader.parse(InputSource(`in`))
        xmlImport(handler.application, prefs)
        xmlImport(handler.wifi, getSharedPreferences("wifi", MODE_PRIVATE))
        xmlImport(handler.mobile, getSharedPreferences("other", MODE_PRIVATE))
        xmlImport(handler.screen_wifi, getSharedPreferences("screen_wifi", MODE_PRIVATE))
        xmlImport(handler.screen_other, getSharedPreferences("screen_other", MODE_PRIVATE))
        xmlImport(handler.roaming, getSharedPreferences("roaming", MODE_PRIVATE))
        xmlImport(handler.lockdown, getSharedPreferences("lockdown", MODE_PRIVATE))
        xmlImport(handler.apply, getSharedPreferences("apply", MODE_PRIVATE))
        xmlImport(handler.notify, getSharedPreferences("notify", MODE_PRIVATE))

        // Upgrade imported settings
        ReceiverAutostart.Companion.upgrade(true, this)
        DatabaseHelper.Companion.clearCache()

        // Refresh UI
        prefs.edit().putBoolean("imported", true).apply()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    private fun xmlImport(settings: Map<String, Any>, prefs: SharedPreferences) {
        val editor: SharedPreferences.Editor = prefs.edit()

        // Clear existing setting
        for (key: String in prefs.getAll().keys) if (!("enabled" == key)) editor.remove(key)

        // Apply new settings
        for (key: String in settings.keys) {
            val value: Any? = settings.get(key)
            if (value is Boolean) editor.putBoolean(key, (value as Boolean?)!!) else if (value is Int) editor.putInt(key, (value as Int?)!!) else if (value is String) editor.putString(key, value as String?) else if (value is Set<*>) editor.putStringSet(key, value as Set<String?>?) else Log.e(ActivitySettings.Companion.TAG, "Unknown type=" + value!!.javaClass)
        }
        editor.apply()
    }

    private inner class XmlImportHandler constructor(private val context: Context) : DefaultHandler() {
        var enabled: Boolean = false
        var application: MutableMap<String, Any> = HashMap()
        var wifi: MutableMap<String, Any> = HashMap()
        var mobile: MutableMap<String, Any> = HashMap()
        var screen_wifi: MutableMap<String, Any> = HashMap()
        var screen_other: MutableMap<String, Any> = HashMap()
        var roaming: MutableMap<String, Any> = HashMap()
        var lockdown: MutableMap<String, Any> = HashMap()
        var apply: MutableMap<String, Any> = HashMap()
        var notify: MutableMap<String, Any> = HashMap()
        private var current: MutableMap<String, Any>? = null
        public override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if ((qName == "netguard")) ; else if ((qName == "application")) current = application else if ((qName == "wifi")) current = wifi else if ((qName == "mobile")) current = mobile else if ((qName == "screen_wifi")) current = screen_wifi else if ((qName == "screen_other")) current = screen_other else if ((qName == "roaming")) current = roaming else if ((qName == "lockdown")) current = lockdown else if ((qName == "apply")) current = apply else if ((qName == "notify")) current = notify else if ((qName == "filter")) {
                current = null
                Log.i(ActivitySettings.Companion.TAG, "Clearing filters")
                DatabaseHelper.Companion.getInstance(context)!!.clearAccess()
            } else if ((qName == "forward")) {
                current = null
                Log.i(ActivitySettings.Companion.TAG, "Clearing forwards")
                DatabaseHelper.Companion.getInstance(context)!!.deleteForward()
            } else if ((qName == "setting")) {
                val key: String = attributes.getValue("key")
                val type: String = attributes.getValue("type")
                val value: String = attributes.getValue("value")
                if (current == null) Log.e(ActivitySettings.Companion.TAG, "No current key=" + key) else {
                    if (("enabled" == key)) enabled = java.lang.Boolean.parseBoolean(value) else {
                        if (current === application) {
                            // Pro features
                            if (("log" == key)) {
                                if (!IAB.Companion.isPurchased(ActivityPro.Companion.SKU_LOG, context)) return
                            } else if (("theme" == key)) {
                                if (!IAB.Companion.isPurchased(ActivityPro.Companion.SKU_THEME, context)) return
                            } else if (("show_stats" == key)) {
                                if (!IAB.Companion.isPurchased(ActivityPro.Companion.SKU_SPEED, context)) return
                            }
                            if (("hosts_last_import" == key) || ("hosts_last_download" == key)) return
                        }
                        if (("boolean" == type)) current!!.put(key, java.lang.Boolean.parseBoolean(value)) else if (("integer" == type)) current!!.put(key, value.toInt()) else if (("string" == type)) current!!.put(key, value) else if (("set" == type)) {
                            val set: MutableSet<String> = HashSet()
                            if (!TextUtils.isEmpty(value)) for (s: String in value.split("\n").toTypedArray()) set.add(s)
                            current!!.put(key, set)
                        } else Log.e(ActivitySettings.Companion.TAG, "Unknown type key=" + key)
                    }
                }
            } else if ((qName == "rule")) {
                val pkg: String = attributes.getValue("pkg")
                val version: String? = attributes.getValue("version")
                val protocol: String? = attributes.getValue("protocol")
                val packet: Packet = Packet()
                packet.version = (if (version == null) 4 else version.toInt())
                packet.protocol = (if (protocol == null) 6 /* TCP */ else protocol.toInt())
                packet.daddr = attributes.getValue("daddr")
                packet.dport = attributes.getValue("dport").toInt()
                packet.time = attributes.getValue("time").toLong()
                val block: Int = attributes.getValue("block").toInt()
                try {
                    packet.uid = getUid(pkg)
                    DatabaseHelper.Companion.getInstance(context)!!.updateAccess(packet, null, block)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.w(ActivitySettings.Companion.TAG, "Package not found pkg=" + pkg)
                }
            } else if ((qName == "port")) {
                val pkg: String = attributes.getValue("pkg")
                val protocol: Int = attributes.getValue("protocol").toInt()
                val dport: Int = attributes.getValue("dport").toInt()
                val raddr: String = attributes.getValue("raddr")
                val rport: Int = attributes.getValue("rport").toInt()
                try {
                    val uid: Int = getUid(pkg)
                    DatabaseHelper.Companion.getInstance(context)!!.addForward(protocol, dport, raddr, rport, uid)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.w(ActivitySettings.Companion.TAG, "Package not found pkg=" + pkg)
                }
            } else Log.e(ActivitySettings.Companion.TAG, "Unknown element qname=" + qName)
        }

        @Throws(PackageManager.NameNotFoundException::class)
        private fun getUid(pkg: String): Int {
            if (("root" == pkg)) return 0 else if (("android.media" == pkg)) return 1013 else if (("android.multicast" == pkg)) return 1020 else if (("android.gps" == pkg)) return 1021 else if (("android.dns" == pkg)) return 1051 else if (("nobody" == pkg)) return 9999 else return getPackageManager().getApplicationInfo(pkg, 0).uid
        }
    }

    companion object {
        private val TAG: String = "NetGuard.Settings"
        private val REQUEST_EXPORT: Int = 1
        private val REQUEST_IMPORT: Int = 2
        private val REQUEST_HOSTS: Int = 3
        private val REQUEST_HOSTS_APPEND: Int = 4
        private val REQUEST_CALL: Int = 5
        private val INTENT_VPN_SETTINGS: Intent = Intent("android.net.vpn.SETTINGS")
    }
}