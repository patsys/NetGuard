package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.*
import android.os.AsyncTask
import android.os.Build
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
import eu.faircode.netguard.ActivityLog
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
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
*/   class ActivityLog constructor() : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private var running: Boolean = false
    private var lvLog: ListView? = null
    private var adapter: AdapterLog? = null
    private var menuSearch: MenuItem? = null
    private var live: Boolean = false
    private var resolve: Boolean = false
    private var organization: Boolean = false
    private var vpn4: InetAddress? = null
    private var vpn6: InetAddress? = null
    private val listener: LogChangedListener = object : LogChangedListener {
        public override fun onChanged() {
            runOnUiThread { updateAdapter() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!IAB.Companion.isPurchased(ActivityPro.Companion.SKU_LOG, this)) {
            startActivity(Intent(this, ActivityPro::class.java))
            finish()
        }
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.logging)
        running = true

        // Action bar
        val actionView: View = getLayoutInflater().inflate(R.layout.actionlog, null, false)
        val swEnabled: SwitchCompat = actionView.findViewById(R.id.swEnabled)
        getSupportActionBar()!!.setDisplayShowCustomEnabled(true)
        getSupportActionBar()!!.setCustomView(actionView)
        getSupportActionBar()!!.setTitle(R.string.menu_log)
        getSupportActionBar()!!.setDisplayHomeAsUpEnabled(true)

        // Get settings
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        resolve = prefs.getBoolean("resolve", false)
        organization = prefs.getBoolean("organization", false)
        val log: Boolean = prefs.getBoolean("log", false)

        // Show disabled message
        val tvDisabled: TextView = findViewById(R.id.tvDisabled)
        tvDisabled.setVisibility(if (log) View.GONE else View.VISIBLE)

        // Set enabled switch
        swEnabled.setChecked(log)
        swEnabled.setOnCheckedChangeListener { buttonView, isChecked -> prefs.edit().putBoolean("log", isChecked).apply() }

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
        lvLog = findViewById(R.id.lvLog)
        val udp: Boolean = prefs.getBoolean("proto_udp", true)
        val tcp: Boolean = prefs.getBoolean("proto_tcp", true)
        val other: Boolean = prefs.getBoolean("proto_other", true)
        val allowed: Boolean = prefs.getBoolean("traffic_allowed", true)
        val blocked: Boolean = prefs.getBoolean("traffic_blocked", true)
        adapter = AdapterLog(this, DatabaseHelper.Companion.getInstance(this)!!.getLog(udp, tcp, other, allowed, blocked), resolve, organization)
        adapter!!.filterQueryProvider = object : FilterQueryProvider {
            public override fun runQuery(constraint: CharSequence): Cursor {
                return DatabaseHelper.Companion.getInstance(this@ActivityLog)!!.searchLog(constraint.toString())
            }
        }
        lvLog.setAdapter(adapter)
        try {
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"))
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        } catch (ex: UnknownHostException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        lvLog.setOnItemClickListener(object : OnItemClickListener {
            public override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                val pm: PackageManager = packageManager
                val cursor: Cursor = adapter!!.getItem(position) as Cursor
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
                val popup: PopupMenu = PopupMenu(this@ActivityLog, findViewById(R.id.vwPopupAnchor))
                popup.inflate(R.menu.log)

                // Application name
                if (uid >= 0) popup.getMenu().findItem(R.id.menu_application).setTitle(TextUtils.join(", ", Util.getApplicationNames(uid, this@ActivityLog))) else popup.menu.removeItem(R.id.menu_application)

                // Destination IP
                popup.getMenu().findItem(R.id.menu_protocol).setTitle(Util.getProtocolName(protocol, version, false))

                // Whois
                val lookupIP: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dnslytics.com/whois-lookup/$ip"))
                if (pm.resolveActivity(lookupIP, 0) == null) popup.getMenu().removeItem(R.id.menu_whois) else popup.menu.findItem(R.id.menu_whois).title = getString(R.string.title_log_whois, ip)

                // Lookup port
                val lookupPort: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedguide.net/port.php?port=$port"))
                if (port <= 0 || pm.resolveActivity(lookupPort, 0) == null) popup.getMenu().removeItem(R.id.menu_port) else popup.getMenu().findItem(R.id.menu_port).title = getString(R.string.title_log_port, port)
                if (prefs.getBoolean("filter", false)) {
                    if (uid <= 0) {
                        popup.menu.removeItem(R.id.menu_allow)
                        popup.menu.removeItem(R.id.menu_block)
                    }
                } else {
                    popup.menu.removeItem(R.id.menu_allow)
                    popup.menu.removeItem(R.id.menu_block)
                }
                val packet: Packet = Packet()
                packet.version = version
                packet.protocol = protocol
                packet.daddr = daddr
                packet.dport = dport
                packet.time = time
                packet.uid = uid
                packet.allowed = (allowed > 0)

                // Time
                popup.getMenu().findItem(R.id.menu_time).setTitle(SimpleDateFormat.getDateTimeInstance().format(time))

                // Handle click
                popup.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
                    public override fun onMenuItemClick(menuItem: MenuItem): Boolean {
                        when (menuItem.itemId) {
                            R.id.menu_application -> {
                                val main: Intent = Intent(this@ActivityLog, ActivityMain::class.java)
                                main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, uid.toString())
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
                                if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, this@ActivityLog)) {
                                    DatabaseHelper.Companion.getInstance(this@ActivityLog)!!.updateAccess(packet, dname, 0)
                                    ServiceSinkhole.Companion.reload("allow host", this@ActivityLog, false)
                                    val main: Intent = Intent(this@ActivityLog, ActivityMain::class.java)
                                    main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, uid.toString())
                                    startActivity(main)
                                } else startActivity(Intent(this@ActivityLog, ActivityPro::class.java))
                                return true
                            }
                            R.id.menu_block -> {
                                if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, this@ActivityLog)) {
                                    DatabaseHelper.Companion.getInstance(this@ActivityLog)!!.updateAccess(packet, dname, 1)
                                    ServiceSinkhole.Companion.reload("block host", this@ActivityLog, false)
                                    val main: Intent = Intent(this@ActivityLog, ActivityMain::class.java)
                                    main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, Integer.toString(uid))
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
        })
        live = true
    }

    override fun onResume() {
        super.onResume()
        if (live) {
            DatabaseHelper.Companion.getInstance(this)!!.addLogChangedListener(listener)
            updateAdapter()
        }
    }

    override fun onPause() {
        super.onPause()
        if (live) DatabaseHelper.Companion.getInstance(this)!!.removeLogChangedListener(listener)
    }

    override fun onDestroy() {
        running = false
        adapter = null
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name))
        if (("log" == name)) {
            // Get enabled
            val log: Boolean = prefs.getBoolean(name, false)

            // Display disabled warning
            val tvDisabled: TextView = findViewById(R.id.tvDisabled)
            tvDisabled.visibility = if (log) View.GONE else View.VISIBLE

            // Check switch state
            val swEnabled: SwitchCompat = supportActionBar!!.getCustomView().findViewById(R.id.swEnabled)
            if (swEnabled.isChecked() != log) swEnabled.isChecked = log
            ServiceSinkhole.Companion.reload("changed $name", this@ActivityLog, false)
        }
    }

    public override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.logging, menu)
        menuSearch = menu.findItem(R.id.menu_search)
        val searchView: SearchView = menuSearch.getActionView() as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            public override fun onQueryTextSubmit(query: String): Boolean {
                if (adapter != null) adapter!!.getFilter().filter(getUidForName(query))
                return true
            }

            public override fun onQueryTextChange(newText: String): Boolean {
                if (adapter != null) adapter!!.getFilter().filter(getUidForName(newText))
                return true
            }
        })
        searchView.setOnCloseListener {
            if (adapter != null) adapter!!.filter.filter(null)
            true
        }
        return true
    }

    public override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // https://gist.github.com/granoeste/5574148
        val pcap_file: File = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
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

    public override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val pcap_file: File = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
        when (item.itemId) {
            android.R.id.home -> {
                Log.i(TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
                return true
            }
            R.id.menu_protocol_udp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_udp", item.isChecked).apply()
                updateAdapter()
                return true
            }
            R.id.menu_protocol_tcp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_tcp", item.isChecked).apply()
                updateAdapter()
                return true
            }
            R.id.menu_protocol_other -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_other", item.isChecked).apply()
                updateAdapter()
                return true
            }
            R.id.menu_traffic_allowed -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_allowed", item.isChecked).apply()
                updateAdapter()
                return true
            }
            R.id.menu_traffic_blocked -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_blocked", item.isChecked).apply()
                updateAdapter()
                return true
            }
            R.id.menu_log_live -> {
                item.isChecked = !item.isChecked
                live = item.isChecked
                if (live) {
                    DatabaseHelper.Companion.getInstance(this)!!.addLogChangedListener(listener)
                    updateAdapter()
                } else DatabaseHelper.Companion.getInstance(this)!!.removeLogChangedListener(listener)
                return true
            }
            R.id.menu_refresh -> {
                updateAdapter()
                return true
            }
            R.id.menu_log_resolve -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("resolve", item.isChecked).apply()
                adapter!!.setResolve(item.isChecked)
                adapter!!.notifyDataSetChanged()
                return true
            }
            R.id.menu_log_organization -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("organization", item.isChecked()).apply()
                adapter!!.setOrganization(item.isChecked())
                adapter!!.notifyDataSetChanged()
                return true
            }
            R.id.menu_pcap_enabled -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("pcap", item.isChecked).apply()
                ServiceSinkhole.Companion.setPcap(item.isChecked, this@ActivityLog)
                return true
            }
            R.id.menu_pcap_export -> {
                startActivityForResult(intentPCAPDocument, REQUEST_PCAP)
                return true
            }
            R.id.menu_log_clear -> {
                object : AsyncTask<Any?, Any?, Any?>() {
                    protected override fun doInBackground(vararg objects: Any): Any? {
                        DatabaseHelper.Companion.getInstance(this@ActivityLog)!!.clearLog(-1)
                        if (prefs.getBoolean("pcap", false)) {
                            ServiceSinkhole.Companion.setPcap(false, this@ActivityLog)
                            if (pcap_file.exists() && !pcap_file.delete()) Log.w(TAG, "Delete PCAP failed")
                            ServiceSinkhole.Companion.setPcap(true, this@ActivityLog)
                        } else {
                            if (pcap_file.exists() && !pcap_file.delete()) Log.w(TAG, "Delete PCAP failed")
                        }
                        return null
                    }

                    override fun onPostExecute(result: Any?) {
                        if (running) updateAdapter()
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                return true
            }
            R.id.menu_log_support -> {
                val intent: Intent = Intent(Intent.ACTION_VIEW)
                intent.setData(Uri.parse("https://github.com/M66B/NetGuard/blob/master/FAQ.md#user-content-faq27"))
                if (getPackageManager().resolveActivity(intent, 0) != null) startActivity(intent)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun updateAdapter() {
        if (adapter != null) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val udp: Boolean = prefs.getBoolean("proto_udp", true)
            val tcp: Boolean = prefs.getBoolean("proto_tcp", true)
            val other: Boolean = prefs.getBoolean("proto_other", true)
            val allowed: Boolean = prefs.getBoolean("traffic_allowed", true)
            val blocked: Boolean = prefs.getBoolean("traffic_blocked", true)
            adapter!!.changeCursor(DatabaseHelper.Companion.getInstance(this)!!.getLog(udp, tcp, other, allowed, blocked))
            if (menuSearch != null && menuSearch!!.isActionViewExpanded()) {
                val searchView: SearchView = menuSearch!!.getActionView() as SearchView
                adapter!!.getFilter().filter(getUidForName(searchView.getQuery().toString()))
            }
        }
    }

    private fun getUidForName(query: String?): String? {
        if (query != null && query.isNotEmpty()) {
            for (rule: Rule? in Rule.Companion.getRules(true, this@ActivityLog)) if (rule!!.name != null && rule.name!!.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                val newQuery: String = rule.uid.toString()
                Log.i(TAG, "Search $query  found  ${rule.name} new $newQuery")
                return newQuery
            }
            Log.i(TAG, "Search $query not found")
        }
        return query
    }

    private val intentPCAPDocument: Intent
        @SuppressLint("SimpleDateFormat") private get() {
            val intent: Intent
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                    intent = Intent("org.openintents.action.PICK_DIRECTORY")
                } else {
                    intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager")
                }
            } else {
                intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType("application/octet-stream")
                intent.putExtra(Intent.EXTRA_TITLE, "netguard_" + SimpleDateFormat("yyyyMMdd").format(Date().time) + ".pcap")
            }
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
        object : AsyncTask<Any?, Any?, Throwable?>() {
            protected override fun doInBackground(vararg objects: Any): Throwable? {
                var out: OutputStream? = null
                var `in`: FileInputStream? = null
                try {
                    // Stop capture
                    ServiceSinkhole.Companion.setPcap(false, this@ActivityLog)
                    var target: Uri? = data.data
                    if (data.hasExtra("org.openintents.extra.DIR_PATH")) target = Uri.parse(target.toString() + "/netguard.pcap")
                    Log.i(TAG, "Export PCAP URI=$target")
                    out = contentResolver.openOutputStream((target)!!)
                    val pcap: File = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
                    `in` = FileInputStream(pcap)
                    var len: Int
                    var total: Long = 0
                    val buf: ByteArray = ByteArray(4096)
                    while ((`in`.read(buf).also { len = it }) > 0) {
                        out!!.write(buf, 0, len)
                        total += len.toLong()
                    }
                    Log.i(TAG, "Copied bytes=$total")
                    return null
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
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
                    if (prefs.getBoolean("pcap", false)) ServiceSinkhole.Companion.setPcap(true, this@ActivityLog)
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (ex == null) Toast.makeText(this@ActivityLog, R.string.msg_completed, Toast.LENGTH_LONG).show() else Toast.makeText(this@ActivityLog, ex.toString(), Toast.LENGTH_LONG).show()
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    companion object {
        private val TAG: String = "NetGuard.Log"
        private val REQUEST_PCAP: Int = 1
    }
}