package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.*
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NavUtils
import androidx.preference.PreferenceManager
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.UnknownHostException
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
*/   class ActivityLog : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private var running: Boolean = false
    private lateinit var lvLog: ListView
    private lateinit var adapter: AdapterLog
    private var menuSearch: MenuItem? = null
    private var live: Boolean = false
    private var resolve: Boolean = false
    private var organization: Boolean = false
    private var vpn4: InetAddress? = null
    private var vpn6: InetAddress? = null
    val uiScope = CoroutineScope(Dispatchers.Main)
    private val listener: LogChangedListener by lazy {
        object : LogChangedListener {
            override fun onChanged() {
                runOnUiThread { updateAdapter() }
            }
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!IAB.isPurchased(ActivityPro.SKU_LOG, this)) {
            startActivity(Intent(this, ActivityPro::class.java))
            finish()
        }
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.logging)
        running = true

        // Action bar
        val actionView: View = layoutInflater.inflate(R.layout.actionlog, null, false)
        val swEnabled: SwitchCompat
        swEnabled = actionView.findViewById(R.id.swEnabled)
        supportActionBar!!.setDisplayShowCustomEnabled(true)
        supportActionBar!!.customView = actionView
        supportActionBar!!.setTitle(R.string.menu_log)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Get settings
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        resolve = prefs.getBoolean("resolve", false)
        organization = prefs.getBoolean("organization", false)
        val log: Boolean = prefs.getBoolean("log", false)

        // Show disabled message
        val tvDisabled: TextView = findViewById(R.id.tvDisabled)
        tvDisabled.visibility = if (log) View.GONE else View.VISIBLE

        // Set enabled switch
        swEnabled.isChecked = log
        swEnabled.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("log", isChecked).apply() }

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
        lvLog = findViewById(R.id.lvLog)
        val udp: Boolean = prefs.getBoolean("proto_udp", true)
        val tcp: Boolean = prefs.getBoolean("proto_tcp", true)
        val other: Boolean = prefs.getBoolean("proto_other", true)
        val allowed: Boolean = prefs.getBoolean("traffic_allowed", true)
        val blocked: Boolean = prefs.getBoolean("traffic_blocked", true)
        adapter = AdapterLog(this, DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked), resolve, organization)
        adapter.filterQueryProvider = FilterQueryProvider { constraint -> DatabaseHelper.getInstance(this@ActivityLog).searchLog(constraint.toString()) }
        lvLog.adapter = adapter
        try {
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"))
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        } catch (ex: UnknownHostException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        lvLog.onItemClickListener = object : OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                val pm: PackageManager = packageManager
                val cursor: Cursor = adapter.getItem(position) as Cursor
                val time: Long = cursor.getLong(cursor.getColumnIndex("time"))
                val version: Int = cursor.getInt(cursor.getColumnIndex("version"))
                val protocol: Int = cursor.getInt(cursor.getColumnIndex("protocol"))
                val saddr: String = cursor.getString(cursor.getColumnIndex("saddr"))
                val sport: Int = (if (cursor.isNull(cursor.getColumnIndex("sport"))) -1 else cursor.getInt(cursor.getColumnIndex("sport")))
                val daddr: String = cursor.getString(cursor.getColumnIndex("daddr"))
                val dport: Int = (if (cursor.isNull(cursor.getColumnIndex("dport"))) -1 else cursor.getInt(cursor.getColumnIndex("dport")))
                val dname: String? = cursor.getString(cursor.getColumnIndex("dname"))
                val uid: Int = (if (cursor.isNull(cursor.getColumnIndex("uid"))) -1 else cursor.getInt(cursor.getColumnIndex("uid")))
                val allowed: Int = (if (cursor.isNull(cursor.getColumnIndex("allowed"))) -1 else cursor.getInt(cursor.getColumnIndex("allowed")))

                // Get external address
                var addr: InetAddress? = null
                try {
                    addr = InetAddress.getByName(daddr)
                } catch (ex: UnknownHostException) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
                val ip: String
                val port: Int
                if ((addr == vpn4) || (addr == vpn6)) {
                    ip = saddr
                    port = sport
                } else {
                    ip = daddr
                    port = dport
                }

                // Build popup menu
                val popup = PopupMenu(this@ActivityLog, findViewById(R.id.vwPopupAnchor))
                popup.inflate(R.menu.log)

                // Application name
                if (uid >= 0) popup.menu.findItem(R.id.menu_application).title = TextUtils.join(", ", Util.getApplicationNames(uid, this@ActivityLog)) else popup.menu.removeItem(R.id.menu_application)

                // Destination IP
                popup.menu.findItem(R.id.menu_protocol).title = Util.getProtocolName(protocol, version, false)

                // Whois
                val lookupIP = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dnslytics.com/whois-lookup/$ip"))
                if (pm.resolveActivity(lookupIP, 0) == null) {
                    popup.menu.removeItem(R.id.menu_whois)
                } else popup.menu.findItem(R.id.menu_whois).title = getString(R.string.title_log_whois, ip)

                // Lookup port
                val lookupPort = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedguide.net/port.php?port=$port"))
                if (port <= 0 || pm.resolveActivity(lookupPort, 0) == null) popup.menu.removeItem(R.id.menu_port) else popup.menu.findItem(R.id.menu_port).title = getString(R.string.title_log_port, port)
                if (prefs.getBoolean("filter", false)) {
                    if (uid <= 0) {
                        popup.menu.removeItem(R.id.menu_allow)
                        popup.menu.removeItem(R.id.menu_block)
                    }
                } else {
                    with(popup) {
                        menu.removeItem(R.id.menu_allow)
                        menu.removeItem(R.id.menu_block)
                    }
                }
                val packet = Packet()
                version.also { packet.version = it }
                packet.protocol = protocol
                packet.daddr = daddr
                packet.dport = dport
                packet.time = time
                packet.uid = uid
                packet.allowed = (allowed > 0)

                // Time
                popup.menu.findItem(R.id.menu_time).title = SimpleDateFormat.getDateTimeInstance().format(time)

                // Handle click
                popup.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
                    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
                        when (menuItem.itemId) {
                            R.id.menu_application -> {
                                val main = Intent(this@ActivityLog, ActivityMain::class.java)
                                main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                                startActivity(main)
                                return true
                            }
                            R.id.menu_whois -> {
                                startActivity(lookupIP)
                                return true
                            }
                            R.id.menu_port -> {
                                startActivity(lookupPort)
                                return true
                            }
                            R.id.menu_allow -> {
                                if (IAB.isPurchased(ActivityPro.SKU_FILTER, this@ActivityLog)) {
                                    DatabaseHelper.getInstance(this@ActivityLog).updateAccess(packet, dname, 0)
                                    ServiceSinkhole.reload("allow host", this@ActivityLog, false)
                                    val main = Intent(this@ActivityLog, ActivityMain::class.java)
                                    main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                                    startActivity(main)
                                } else startActivity(Intent(this@ActivityLog, ActivityPro::class.java))
                                return true
                            }
                            R.id.menu_block -> {
                                if (IAB.isPurchased(ActivityPro.SKU_FILTER, this@ActivityLog)) {
                                    DatabaseHelper.getInstance(this@ActivityLog).updateAccess(packet, dname, 1)
                                    ServiceSinkhole.reload("block host", this@ActivityLog, false)
                                    val main = Intent(this@ActivityLog, ActivityMain::class.java)
                                    main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                                    startActivity(main)
                                } else startActivity(Intent(this@ActivityLog, ActivityPro::class.java))
                                return true
                            }
                            R.id.menu_copy -> {
                                val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip: ClipData = ClipData.newPlainText("netguard", dname
                                        ?: daddr)
                                clipboard.setPrimaryClip(clip)
                                return true
                            }
                            else -> return false
                        }
                    }
                })

                // Show
                popup.show()
            }
        }
        live = true
    }

    override fun onResume() {
        super.onResume()
        if (live) {
            DatabaseHelper.getInstance(this).addLogChangedListener(listener)
            updateAdapter()
        }
    }

    override fun onPause() {
        super.onPause()
        if (live) DatabaseHelper.getInstance(this).removeLogChangedListener(listener)
    }

    override fun onDestroy() {
        running = false
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        Log.i(TAG, "Preference " + name + "=" + prefs.all[name])
        if (("log" == name)) {
            // Get enabled
            val log: Boolean = prefs.getBoolean(name, false)

            // Display disabled warning
            val tvDisabled: TextView = findViewById(R.id.tvDisabled)
            tvDisabled.visibility = if (log) View.GONE else View.VISIBLE

            // Check switch state
            val swEnabled: SwitchCompat = this.supportActionBar!!.customView.findViewById(R.id.swEnabled)
            if (swEnabled.isChecked != log) swEnabled.isChecked = log
            ServiceSinkhole.reload("changed $name", this@ActivityLog, false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.logging, menu)
        menuSearch = menu.findItem(R.id.menu_search)
        val searchView: SearchView = menuSearch!!.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                adapter.filter.filter(getUidForName(query))
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                adapter.filter.filter(getUidForName(newText))
                return true
            }
        })
        searchView.setOnCloseListener {
            adapter.filter.filter(null)
            true
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // https://gist.github.com/granoeste/5574148
        val pcap_file = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
        val export: Boolean = (packageManager.resolveActivity(intentPCAPDocument, 0) != null)
        menu.findItem(R.id.menu_protocol_udp).isChecked = prefs.getBoolean("proto_udp", true)
        menu.findItem(R.id.menu_protocol_tcp).isChecked = prefs.getBoolean("proto_tcp", true)
        menu.findItem(R.id.menu_protocol_other).isChecked = prefs.getBoolean("proto_other", true)
        menu.findItem(R.id.menu_traffic_allowed).isEnabled = prefs.getBoolean("filter", false)
        menu.findItem(R.id.menu_traffic_allowed).isChecked = prefs.getBoolean("traffic_allowed", true)
        menu.findItem(R.id.menu_traffic_blocked).isChecked = prefs.getBoolean("traffic_blocked", true)
        menu.findItem(R.id.menu_refresh).isEnabled = !menu.findItem(R.id.menu_log_live).isChecked
        menu.findItem(R.id.menu_log_resolve).isChecked = prefs.getBoolean("resolve", false)
        menu.findItem(R.id.menu_log_organization).isChecked = prefs.getBoolean("organization", false)
        menu.findItem(R.id.menu_pcap_enabled).isChecked = prefs.getBoolean("pcap", false)
        menu.findItem(R.id.menu_pcap_export).isEnabled = pcap_file.exists() && export
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val pcap_file: File = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
        when (item.itemId) {
            android.R.id.home -> {
                Log.i(TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
            }
            R.id.menu_protocol_udp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_udp", item.isChecked).apply()
                updateAdapter()
            }
            R.id.menu_protocol_tcp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_tcp", item.isChecked).apply()
                updateAdapter()
            }
            R.id.menu_protocol_other -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_other", item.isChecked).apply()
                updateAdapter()
            }
            R.id.menu_traffic_allowed -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_allowed", item.isChecked).apply()
                updateAdapter()
            }
            R.id.menu_traffic_blocked -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_blocked", item.isChecked).apply()
                updateAdapter()
            }
            R.id.menu_log_live -> {
                item.isChecked = !item.isChecked
                live = item.isChecked
                if (live) {
                    DatabaseHelper.getInstance(this).addLogChangedListener(listener)
                    updateAdapter()
                } else DatabaseHelper.getInstance(this).removeLogChangedListener(listener)
            }
            R.id.menu_refresh -> {
                updateAdapter()
            }
            R.id.menu_log_resolve -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("resolve", item.isChecked).apply()
                adapter.setResolve(item.isChecked)
                adapter.notifyDataSetChanged()
            }
            R.id.menu_log_organization -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("organization", item.isChecked).apply()
                adapter.setOrganization(item.isChecked)
                adapter.notifyDataSetChanged()
            }
            R.id.menu_pcap_enabled -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("pcap", item.isChecked).apply()
                ServiceSinkhole.setPcap(item.isChecked, this@ActivityLog)
            }
            R.id.menu_pcap_export -> {
                startActivityForResult(intentPCAPDocument, REQUEST_PCAP)
            }
            R.id.menu_log_clear -> {
                uiScope.launch {
                    withContext(Dispatchers.Default) {
                        DatabaseHelper.getInstance(this@ActivityLog).clearLog(-1)
                        if (prefs.getBoolean("pcap", false)) {
                            ServiceSinkhole.setPcap(false, this@ActivityLog)
                            if (pcap_file.exists() && !pcap_file.delete()) Log.w(TAG, "Delete PCAP failed")
                            ServiceSinkhole.setPcap(true, this@ActivityLog)
                        } else {
                            if (pcap_file.exists() && !pcap_file.delete()) Log.w(TAG, "Delete PCAP failed")
                        }
                        withContext(Dispatchers.Main) {
                            if (running) updateAdapter()
                        }
                    }
                }
            }
            R.id.menu_log_support -> {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://github.com/M66B/NetGuard/blob/master/FAQ.md#user-content-faq27")
                if (packageManager.resolveActivity(intent, 0) != null) startActivity(intent)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return false
    }

    private fun updateAdapter() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val udp: Boolean = prefs.getBoolean("proto_udp", true)
        val tcp: Boolean = prefs.getBoolean("proto_tcp", true)
        val other: Boolean = prefs.getBoolean("proto_other", true)
        val allowed: Boolean = prefs.getBoolean("traffic_allowed", true)
        val blocked: Boolean = prefs.getBoolean("traffic_blocked", true)
        adapter.changeCursor(DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked))
        if (menuSearch != null && menuSearch!!.isActionViewExpanded) {
            val searchView: SearchView = menuSearch!!.actionView as SearchView
            adapter.filter.filter(getUidForName(searchView.query.toString()))
        }
    }

    private fun getUidForName(query: String?): String? {
        if (query != null && query.isNotEmpty()) {
            for (rule: Rule? in Rule.getRules(true, this@ActivityLog)) if (rule!!.name != null && rule.name!!.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                val newQuery: String = rule.uid.toString()
                Log.i(TAG, "Search $query  found  ${rule.name} new $newQuery")
                return newQuery
            }
            Log.i(TAG, "Search $query not found")
        }
        return query
    }

    private val intentPCAPDocument: Intent
        @SuppressLint("SimpleDateFormat") get() {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/octet-stream"
            intent.putExtra(Intent.EXTRA_TITLE, "netguard_" + SimpleDateFormat("yyyyMMdd").format(Date().time) + ".pcap")
            return intent
        }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK))
        if (requestCode == REQUEST_PCAP) {
            if (resultCode == RESULT_OK && data != null) handleExportPCAP(data)
        } else {
            Log.w(TAG, "Unknown activity result request=$requestCode")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleExportPCAP(data: Intent) {
        uiScope.launch {
            withContext(Dispatchers.Default) {
                var out: OutputStream? = null
                var `in`: FileInputStream? = null
                try {
                    // Stop capture
                    ServiceSinkhole.setPcap(false, this@ActivityLog)
                    var target: Uri? = data.data
                    if (data.hasExtra("org.openintents.extra.DIR_PATH")) target = Uri.parse(target.toString() + "/netguard.pcap")
                    Log.i(TAG, "Export PCAP URI=$target")
                    out = contentResolver.openOutputStream((target)!!)
                    val pcap = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
                    `in` = FileInputStream(pcap)
                    var len: Int
                    var total: Long = 0
                    val buf = ByteArray(4096)
                    while ((`in`.read(buf).also { len = it }) > 0) {
                        out!!.write(buf, 0, len)
                        total += len.toLong()
                    }
                    Log.i(TAG, "Copied bytes=$total")
                    withContext(Dispatchers.Main){
                        Toast.makeText(this@ActivityLog, R.string.msg_completed, Toast.LENGTH_LONG).show()
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    withContext(Dispatchers.Main){
                         Toast.makeText(this@ActivityLog, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (out != null) try {
                        out.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    if (`in` != null) try {
                        `in`.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }

                    // Resume capture
                    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ActivityLog)
                    if (prefs.getBoolean("pcap", false)) ServiceSinkhole.setPcap(true, this@ActivityLog)
                }
            }
        }
    }

    companion object {
        private const val TAG: String = "NetGuard.Log"
        private const val REQUEST_PCAP: Int = 1
    }
}