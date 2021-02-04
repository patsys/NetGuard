package eu.faircode.netguard

import android.R.string.yes
import android.annotation.SuppressLint
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ImageSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import eu.faircode.netguard.DatabaseHelper.AccessChangedListener
import eu.faircode.netguardimport.ReceiverAutostart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
*/   class ActivityMain : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private var running: Boolean = false
    private lateinit var ivIcon: ImageView
    private lateinit var ivQueue: ImageView
    private lateinit var swEnabled: SwitchCompat
    private lateinit var ivMetered: ImageView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: AdapterRule
    private var menuSearch: MenuItem? = null
    private lateinit var dialogFirst: AlertDialog
    private var dialogVpn: AlertDialog? = null
    private var dialogDoze: AlertDialog? = null
    private var dialogLegend: AlertDialog? = null
    private var dialogAbout: AlertDialog? = null
    private var iab: IAB? = null
    val uiScope = CoroutineScope(Dispatchers.Main)
    @SuppressLint("InflateParams", "RtlHardcoded")
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this))
        Util.logExtras(intent)

        // Check minimum Android version
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            Log.i(TAG, "SDK=" + Build.VERSION.SDK_INT)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.android)
            return
        }

        // Check for Xposed
        if (Util.hasXposed(this)) {
            Log.i(TAG, "Xposed running")
            super.onCreate(savedInstanceState)
            setContentView(R.layout.xposed)
            return
        }
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        running = true
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled: Boolean = prefs.getBoolean("enabled", false)
        val initialized: Boolean = prefs.getBoolean("initialized", false)

        // Upgrade
        ReceiverAutostart.upgrade(initialized, this)
        if (!intent.hasExtra(EXTRA_APPROVE)) {
            if (enabled) ServiceSinkhole.start("UI", this) else ServiceSinkhole.stop("UI", this, false)
        }

        // Action bar
        val actionView: View = layoutInflater.inflate(R.layout.actionmain, null, false)
        ivIcon = actionView.findViewById(R.id.ivIcon)
        ivQueue = actionView.findViewById(R.id.ivQueue)
        swEnabled = actionView.findViewById(R.id.swEnabled)
        ivMetered = actionView.findViewById(R.id.ivMetered)

        // Icon
        ivIcon.setOnLongClickListener {
            menu_about()
            true
        }

        // Title
        supportActionBar!!.setTitle("")

        // Netguard is busy
        ivQueue.setOnLongClickListener {
            val location = IntArray(2)
            actionView.getLocationOnScreen(location)
            val toast: Toast = Toast.makeText(this@ActivityMain, R.string.msg_queue, Toast.LENGTH_LONG)
            toast.setGravity(
                    Gravity.TOP or Gravity.LEFT,
                    location[0] + ivQueue.left,
                    (location[1] + ivQueue.bottom - toast.view!!.paddingTop).toFloat().roundToInt())
            toast.show()
            true
        }

        // On/off switch
        swEnabled.isChecked = enabled
        swEnabled.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                Log.i(TAG, "Switch=$isChecked")
                prefs.edit().putBoolean("enabled", isChecked).apply()
                if (isChecked) {
                    val alwaysOn: String = Settings.Secure.getString(contentResolver, "always_on_vpn_app")
                    Log.i(TAG, "Always-on=$alwaysOn")
                    if (!TextUtils.isEmpty(alwaysOn)) if ((packageName == alwaysOn)) {
                        if (prefs.getBoolean("filter", false)) {
                            val lockdown: Int = Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0)
                            Log.i(TAG, "Lockdown=$lockdown")
                            if (lockdown != 0) {
                                swEnabled.isChecked = false
                                Toast.makeText(this@ActivityMain, R.string.msg_always_on_lockdown, Toast.LENGTH_LONG).show()
                                return
                            }
                        }
                    } else {
                        swEnabled.isChecked = false
                        Toast.makeText(this@ActivityMain, R.string.msg_always_on, Toast.LENGTH_LONG).show()
                        return
                    }
                    val filter: Boolean = prefs.getBoolean("filter", false)
                    if (filter && Util.isPrivateDns(this@ActivityMain)) Toast.makeText(this@ActivityMain, R.string.msg_private_dns, Toast.LENGTH_LONG).show()
                    try {
                        val prepare: Intent? = VpnService.prepare(this@ActivityMain)
                        if (prepare == null) {
                            Log.i(TAG, "Prepare done")
                            onActivityResult(REQUEST_VPN, RESULT_OK, null)
                        } else {
                            // Show dialog
                            val inflater: LayoutInflater = LayoutInflater.from(this@ActivityMain)
                            val view: View = inflater.inflate(R.layout.vpn, null, false)
                            dialogVpn = AlertDialog.Builder(this@ActivityMain)
                                    .setView(view)
                                    .setCancelable(false)
                                    .setPositiveButton(yes) { _, _ ->
                                        if (running) {
                                            Log.i(TAG, "Start intent=$prepare")
                                            try {
                                                // com.android.vpndialogs.ConfirmDialog required
                                                startActivityForResult(prepare, REQUEST_VPN)
                                            } catch (ex: Throwable) {
                                                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                                                onActivityResult(REQUEST_VPN, RESULT_CANCELED, null)
                                                prefs.edit().putBoolean("enabled", false).apply()
                                            }
                                        }
                                    }
                                    .setOnDismissListener { dialogVpn = null }
                                    .create()
                            dialogVpn!!.show()
                        }
                    } catch (ex: Throwable) {
                        // Prepare failed
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        prefs.edit().putBoolean("enabled", false).apply()
                    }
                } else ServiceSinkhole.stop("switch off", this@ActivityMain, false)
            }
        })
        if (enabled) checkDoze()

        // Network is metered
        ivMetered.setOnLongClickListener {
            val location = IntArray(2)
            actionView.getLocationOnScreen(location)
            val toast: Toast = Toast.makeText(this@ActivityMain, R.string.msg_metered, Toast.LENGTH_LONG)
            toast.setGravity(
                    Gravity.TOP or Gravity.LEFT,
                    location[0] + ivMetered.left,
                    (location[1] + ivMetered.bottom - toast.view!!.paddingTop).toFloat().roundToInt())
            toast.show()
            true
        }
        supportActionBar!!.setDisplayShowCustomEnabled(true)
        supportActionBar!!.customView = actionView

        // Disabled warning
        val tvDisabled: TextView = findViewById(R.id.tvDisabled)
        tvDisabled.visibility = if (enabled) View.GONE else View.VISIBLE

        // Application list
        val rvApplication: RecyclerView = findViewById(R.id.rvApplication)
        rvApplication.setHasFixedSize(false)
        val llm = LinearLayoutManager(this)
        llm.isAutoMeasureEnabled = true
        rvApplication.layoutManager = llm
        adapter = AdapterRule(this, findViewById(R.id.vwPopupAnchor))
        rvApplication.adapter = adapter

        // Swipe to refresh
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(Color.WHITE, Color.WHITE, Color.WHITE)
        swipeRefresh.setProgressBackgroundColorSchemeColor(tv.data)
        swipeRefresh.setOnRefreshListener {
            Rule.clearCache(this@ActivityMain)
            ServiceSinkhole.reload("pull", this@ActivityMain, false)
            updateApplicationList(null)
        }

        // Hint usage
        val llUsage: LinearLayout = findViewById(R.id.llUsage)
        val btnUsage: Button = findViewById(R.id.btnUsage)
        val hintUsage: Boolean = prefs.getBoolean("hint_usage", true)
        llUsage.visibility = if (hintUsage) View.VISIBLE else View.GONE
        btnUsage.setOnClickListener {
            prefs.edit().putBoolean("hint_usage", false).apply()
            llUsage.visibility = View.GONE
            showHints()
        }
        val llFairEmail: LinearLayout = findViewById(R.id.llFairEmail)
        val tvFairEmail: TextView = findViewById(R.id.tvFairEmail)
        tvFairEmail.movementMethod = LinkMovementMethod.getInstance()
        val btnFairEmail: Button = findViewById(R.id.btnFairEmail)
        val hintFairEmail: Boolean = prefs.getBoolean("hint_fairemail", true)
        llFairEmail.visibility = if (hintFairEmail) View.VISIBLE else View.GONE
        btnFairEmail.setOnClickListener {
            prefs.edit().putBoolean("hint_fairemail", false).apply()
            llFairEmail.visibility = View.GONE
        }
        showHints()

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Listen for rule set changes
        val ifr = IntentFilter(ACTION_RULES_CHANGED)
        LocalBroadcastManager.getInstance(this).registerReceiver(onRulesChanged, ifr)

        // Listen for queue changes
        val ifq = IntentFilter(ACTION_QUEUE_CHANGED)
        LocalBroadcastManager.getInstance(this).registerReceiver(onQueueChanged, ifq)

        // Listen for added/removed applications
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilter.addDataScheme("package")
        registerReceiver(packageChangedReceiver, intentFilter)

        // First use
        if (!initialized) {
            // Create view
            val inflater: LayoutInflater = LayoutInflater.from(this)
            val view: View = inflater.inflate(R.layout.first, null, false)
            val tvFirst: TextView = view.findViewById(R.id.tvFirst)
            val tvEula: TextView = view.findViewById(R.id.tvEula)
            val tvPrivacy: TextView = view.findViewById(R.id.tvPrivacy)
            tvFirst.movementMethod = LinkMovementMethod.getInstance()
            tvEula.movementMethod = LinkMovementMethod.getInstance()
            tvPrivacy.movementMethod = LinkMovementMethod.getInstance()

            // Show dialog
            dialogFirst = AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.app_agree) { _, _ ->
                        if (running) {
                            prefs.edit().putBoolean("initialized", true).apply()
                        }
                    }
                    .setNegativeButton(R.string.app_disagree) { _, _ -> if (running) finish() }
                    .create()
            dialogFirst.show()
        }

        // Fill application list
        updateApplicationList(intent.getStringExtra(EXTRA_SEARCH))

        // Update IAB SKUs
        try {
            iab = IAB(object : IAB.Delegate {
                override fun onReady(iab: IAB) {
                    try {
                        iab.updatePurchases()
                        if (!IAB.isPurchased(ActivityPro.SKU_LOG, this@ActivityMain)) prefs.edit().putBoolean("log", false).apply()
                        if (!IAB.isPurchased(ActivityPro.SKU_THEME, this@ActivityMain)) {
                            if ("teal" != prefs.getString("theme", "teal")) prefs.edit().putString("theme", "teal").apply()
                        }
                        if (!IAB.isPurchased(ActivityPro.SKU_NOTIFY, this@ActivityMain)) prefs.edit().putBoolean("install", false).apply()
                        if (!IAB.isPurchased(ActivityPro.SKU_SPEED, this@ActivityMain)) prefs.edit().putBoolean("show_stats", false).apply()
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    } finally {
                        iab.unbind()
                    }
                }
            }, this)
            iab!!.bind()
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }

        // Support
        val llSupport: LinearLayout = findViewById(R.id.llSupport)
        val tvSupport: TextView = findViewById(R.id.tvSupport)
        val content = SpannableString(getString(R.string.app_support))
        content.setSpan(UnderlineSpan(), 0, content.length, 0)
        tvSupport.text = content
        llSupport.setOnClickListener { startActivity(getIntentPro(this@ActivityMain)) }

        // Handle intent
        checkExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        Log.i(TAG, "New intent")
        Util.logExtras(intent)
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) return
        setIntent(intent)
        if (Build.VERSION.SDK_INT >= MIN_SDK) {
            if (intent.hasExtra(EXTRA_REFRESH)) updateApplicationList(intent.getStringExtra(EXTRA_SEARCH)) else updateSearch(intent.getStringExtra(EXTRA_SEARCH))
            checkExtras(intent)
        }
    }

    override fun onResume() {
        Log.i(TAG, "Resume")
        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) {
            super.onResume()
            return
        }
        DatabaseHelper.getInstance(this).addAccessChangedListener(accessChangedListener)
        adapter.notifyDataSetChanged()
        val pm: PackageManager = packageManager
        val llSupport: LinearLayout = findViewById(R.id.llSupport)
        llSupport.visibility = if (IAB.isPurchasedAny(this) || getIntentPro(this).resolveActivity(pm) == null) View.GONE else View.VISIBLE
        super.onResume()
    }

    override fun onPause() {
        Log.i(TAG, "Pause")
        super.onPause()
        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) return
        DatabaseHelper.getInstance(this).removeAccessChangedListener(accessChangedListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        Log.i(TAG, "Config")
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) return
    }

    public override fun onDestroy() {
        Log.i(TAG, "Destroy")
        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) {
            super.onDestroy()
            return
        }
        running = false
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(onRulesChanged)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(onQueueChanged)
        unregisterReceiver(packageChangedReceiver)
        dialogFirst.dismiss()
        if (dialogVpn != null) {
            dialogVpn!!.dismiss()
            dialogVpn = null
        }
        if (dialogDoze != null) {
            dialogDoze!!.dismiss()
            dialogDoze = null
        }
        if (dialogLegend != null) {
            dialogLegend!!.dismiss()
            dialogLegend = null
        }
        if (dialogAbout != null) {
            dialogAbout!!.dismiss()
            dialogAbout = null
        }
        if (iab != null) {
            iab!!.unbind()
            iab = null
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK))
        Util.logExtras(data)
        if (requestCode == REQUEST_VPN) {
            // Handle VPN approval
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit().putBoolean("enabled", resultCode == RESULT_OK).apply()
            if (resultCode == RESULT_OK) {
                ServiceSinkhole.start("prepared", this)
                val on: Toast = Toast.makeText(this@ActivityMain, R.string.msg_on, Toast.LENGTH_LONG)
                on.setGravity(Gravity.CENTER, 0, 0)
                on.show()
                checkDoze()
            } else if (resultCode == RESULT_CANCELED) Toast.makeText(this, R.string.msg_vpn_cancelled, Toast.LENGTH_LONG).show()
        } else if (requestCode == REQUEST_INVITE) {
            // Do nothing
        } else if (requestCode == REQUEST_LOGCAT) {
            // Send logcat by e-mail
            if (resultCode == RESULT_OK) {
                var target: Uri? = data!!.data
                if (data.hasExtra("org.openintents.extra.DIR_PATH")) target = Uri.parse(target.toString() + "/logcat.txt")
                Log.i(TAG, "Export URI=$target")
                Util.sendLogcat(target, this)
            }
        } else {
            Log.w(TAG, "Unknown activity result request=$requestCode")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_ROAMING) if (grantResults[0] == PackageManager.PERMISSION_GRANTED) ServiceSinkhole.reload("permission granted", this, false)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        Log.i(TAG, "Preference " + name + "=" + prefs.all[name])
        if (("enabled" == name)) {
            // Get enabled
            val enabled: Boolean = prefs.getBoolean(name, false)

            // Display disabled warning
            val tvDisabled: TextView = findViewById(R.id.tvDisabled)
            tvDisabled.visibility = if (enabled) View.GONE else View.VISIBLE

            // Check switch state
            val swEnabled: SwitchCompat = supportActionBar!!.customView.findViewById(R.id.swEnabled)
            if (swEnabled.isChecked != enabled) swEnabled.isChecked = enabled
        } else if ((("whitelist_wifi" == name) || ("screen_on" == name) || ("screen_wifi" == name) || ("whitelist_other" == name) || ("screen_other" == name) || ("whitelist_roaming" == name) || ("show_user" == name) || ("show_system" == name) || ("show_nointernet" == name) || ("show_disabled" == name) || ("sort" == name) || ("imported" == name))) {
            updateApplicationList(null)
            val llWhitelist: LinearLayout = findViewById(R.id.llWhitelist)
            val screen_on: Boolean = prefs.getBoolean("screen_on", true)
            val whitelist_wifi: Boolean = prefs.getBoolean("whitelist_wifi", false)
            val whitelist_other: Boolean = prefs.getBoolean("whitelist_other", false)
            val hintWhitelist: Boolean = prefs.getBoolean("hint_whitelist", true)
            llWhitelist.visibility = if (!(whitelist_wifi || whitelist_other) && screen_on && hintWhitelist) View.VISIBLE else View.GONE
        } else if (("manage_system" == name)) {
            invalidateOptionsMenu()
            updateApplicationList(null)
            val llSystem: LinearLayout = findViewById(R.id.llSystem)
            val system: Boolean = prefs.getBoolean("manage_system", false)
            val hint: Boolean = prefs.getBoolean("hint_system", true)
            llSystem.visibility = if (!system && hint) View.VISIBLE else View.GONE
        } else if (("theme" == name) || ("dark_theme" == name)) recreate()
    }

    private val accessChangedListener: AccessChangedListener = object : AccessChangedListener {
        override fun onChanged() {
            runOnUiThread { if (adapter.isLive) adapter.notifyDataSetChanged() }
        }
    }
    private val onRulesChanged: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            if (intent.hasExtra(EXTRA_CONNECTED) && intent.hasExtra(EXTRA_METERED)) {
                ivIcon.setImageResource(if (Util.isNetworkActive(this@ActivityMain)) R.drawable.ic_security_white_24dp else R.drawable.ic_security_white_24dp_60)
                if (intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
                    if (intent.getBooleanExtra(EXTRA_METERED, false)) adapter.setMobileActive() else adapter.setWifiActive()
                    ivMetered.visibility = if (Util.isMeteredNetwork(this@ActivityMain)) View.VISIBLE else View.INVISIBLE
                } else {
                    adapter.setDisconnected()
                    ivMetered.visibility = View.INVISIBLE
                }
            } else updateApplicationList(null)
        }
    }
    private val onQueueChanged: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            val size: Int = intent.getIntExtra(EXTRA_SIZE, -1)
            ivIcon.visibility = if (size == 0) View.VISIBLE else View.GONE
            ivQueue.visibility = if (size == 0) View.GONE else View.VISIBLE
        }
    }
    private val packageChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            updateApplicationList(null)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false
        val pm: PackageManager = packageManager
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.main, menu)

        // Search
        menuSearch = menu.findItem(R.id.menu_search)
        menuSearch!!.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (intent.hasExtra(EXTRA_SEARCH) && !intent.getBooleanExtra(EXTRA_RELATED, false)) finish()
                return true
            }
        })
        val searchView: SearchView = menuSearch!!.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                adapter.filter.filter(query)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                adapter.filter.filter(newText)
                return true
            }
        })
        searchView.setOnCloseListener {
            val intent: Intent = intent
            intent.removeExtra(EXTRA_SEARCH)
            adapter.filter.filter(null)
            true
        }
        val search: String? = intent.getStringExtra(EXTRA_SEARCH)
        if (search != null) {
            menuSearch!!.expandActionView()
            searchView.setQuery(search, true)
        }
        markPro(menu.findItem(R.id.menu_log), ActivityPro.SKU_LOG)
        if (!IAB.isPurchasedAny(this)) markPro(menu.findItem(R.id.menu_pro), null)
        if (!Util.hasValidFingerprint(this) || getIntentInvite(this).resolveActivity(pm) == null) menu.removeItem(R.id.menu_invite)
        if (intentSupport.resolveActivity(packageManager) == null) menu.removeItem(R.id.menu_support)
        menu.findItem(R.id.menu_apps).isEnabled = getIntentApps(this).resolveActivity(pm) != null
        return true
    }

    private fun markPro(menu: MenuItem, sku: String?) {
        if (sku == null || !IAB.isPurchased(sku, this)) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val dark: Boolean = prefs.getBoolean("dark_theme", false)
            val ssb = SpannableStringBuilder("  " + menu.title)
            ssb.setSpan(ImageSpan(this, if (dark) R.drawable.ic_shopping_cart_white_24dp else R.drawable.ic_shopping_cart_black_24dp), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            menu.title = ssb
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("manage_system", false)) {
            menu.findItem(R.id.menu_app_user).isChecked = prefs.getBoolean("show_user", true)
            menu.findItem(R.id.menu_app_system).isChecked = prefs.getBoolean("show_system", false)
        } else {
            val submenu: Menu = menu.findItem(R.id.menu_filter).subMenu
            submenu.removeItem(R.id.menu_app_user)
            submenu.removeItem(R.id.menu_app_system)
        }
        menu.findItem(R.id.menu_app_nointernet).isChecked = prefs.getBoolean("show_nointernet", true)
        menu.findItem(R.id.menu_app_disabled).isChecked = prefs.getBoolean("show_disabled", true)
        val sort: String? = prefs.getString("sort", "name")
        if (("uid" == sort)) menu.findItem(R.id.menu_sort_uid).isChecked = true else menu.findItem(R.id.menu_sort_name).isChecked = true
        menu.findItem(R.id.menu_lockdown).isChecked = prefs.getBoolean("lockdown", false)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.i(TAG, "Menu=" + item.title)

        // Handle item selection
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        when (item.itemId) {
            R.id.menu_app_user -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("show_user", item.isChecked).apply()
                return true
            }
            R.id.menu_app_system -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("show_system", item.isChecked).apply()
                return true
            }
            R.id.menu_app_nointernet -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("show_nointernet", item.isChecked).apply()
                return true
            }
            R.id.menu_app_disabled -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("show_disabled", item.isChecked).apply()
                return true
            }
            R.id.menu_sort_name -> {
                item.isChecked = true
                prefs.edit().putString("sort", "name").apply()
                return true
            }
            R.id.menu_sort_uid -> {
                item.isChecked = true
                prefs.edit().putString("sort", "uid").apply()
                return true
            }
            R.id.menu_lockdown -> {
                menu_lockdown(item)
                return true
            }
            R.id.menu_log -> {
                if (Util.canFilter(this)) if (IAB.isPurchased(ActivityPro.SKU_LOG, this)) startActivity(Intent(this, ActivityLog::class.java)) else startActivity(Intent(this, ActivityPro::class.java)) else Toast.makeText(this, R.string.msg_unavailable, Toast.LENGTH_SHORT).show()
                return true
            }
            R.id.menu_settings -> {
                startActivity(Intent(this, ActivitySettings::class.java))
                return true
            }
            R.id.menu_pro -> {
                startActivity(Intent(this@ActivityMain, ActivityPro::class.java))
                return true
            }
            R.id.menu_invite -> {
                startActivityForResult(getIntentInvite(this), REQUEST_INVITE)
                return true
            }
            R.id.menu_legend -> {
                menu_legend()
                return true
            }
            R.id.menu_support -> {
                startActivity(intentSupport)
                return true
            }
            R.id.menu_about -> {
                menu_about()
                return true
            }
            R.id.menu_apps -> {
                menu_apps()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun showHints() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hintUsage: Boolean = prefs.getBoolean("hint_usage", true)

        // Hint white listing
        val llWhitelist: LinearLayout = findViewById(R.id.llWhitelist)
        val btnWhitelist: Button = findViewById(R.id.btnWhitelist)
        val whitelist_wifi: Boolean = prefs.getBoolean("whitelist_wifi", false)
        val whitelist_other: Boolean = prefs.getBoolean("whitelist_other", false)
        val hintWhitelist: Boolean = prefs.getBoolean("hint_whitelist", true)
        llWhitelist.visibility = if (!(whitelist_wifi || whitelist_other) && hintWhitelist && !hintUsage) View.VISIBLE else View.GONE
        btnWhitelist.setOnClickListener {
            prefs.edit().putBoolean("hint_whitelist", false).apply()
            llWhitelist.visibility = View.GONE
        }

        // Hint push messages
        val llPush: LinearLayout = findViewById(R.id.llPush)
        val btnPush: Button = findViewById(R.id.btnPush)
        val hintPush: Boolean = prefs.getBoolean("hint_push", true)
        llPush.visibility = if (hintPush && !hintUsage) View.VISIBLE else View.GONE
        btnPush.setOnClickListener {
            prefs.edit().putBoolean("hint_push", false).apply()
            llPush.visibility = View.GONE
        }

        // Hint system applications
        val llSystem: LinearLayout = findViewById(R.id.llSystem)
        val btnSystem: Button = findViewById(R.id.btnSystem)
        val system: Boolean = prefs.getBoolean("manage_system", false)
        val hintSystem: Boolean = prefs.getBoolean("hint_system", true)
        llSystem.visibility = if (!system && hintSystem) View.VISIBLE else View.GONE
        btnSystem.setOnClickListener {
            prefs.edit().putBoolean("hint_system", false).apply()
            llSystem.visibility = View.GONE
        }
    }

    private fun checkExtras(intent: Intent) {
        // Approve request
        if (intent.hasExtra(EXTRA_APPROVE)) {
            Log.i(TAG, "Requesting VPN approval")
            swEnabled.toggle()
        }
        if (intent.hasExtra(EXTRA_LOGCAT)) {
            Log.i(TAG, "Requesting logcat")
            val logcat: Intent = intentLogcat
            if (logcat.resolveActivity(packageManager) != null) startActivityForResult(logcat, REQUEST_LOGCAT)
        }
    }

    private fun updateApplicationList(search: String?) {
        Log.i(TAG, "Update search=$search")
        uiScope.launch {
            withContext(Dispatchers.Main) {
                var refreshing = true
                swipeRefresh.post { if (refreshing) swipeRefresh.isRefreshing = true }
                withContext(Dispatchers.Default) {}
                val rule = Rule.getRules(false, this@ActivityMain)
                withContext(Dispatchers.Main) {
                    if (running) {
                        adapter.set(rule)
                        updateSearch(search)
                        refreshing = false
                        swipeRefresh.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun updateSearch(search: String?) {
        if (menuSearch != null) {
            val searchView: SearchView = menuSearch!!.actionView as SearchView
            if (search == null) {
                if (menuSearch!!.isActionViewExpanded) adapter.filter.filter(searchView.query.toString())
            } else {
                menuSearch!!.expandActionView()
                searchView.setQuery(search, true)
            }
        }
    }

    private fun checkDoze() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val doze = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (Util.batteryOptimizing(this) && packageManager.resolveActivity(doze, 0) != null) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                if (!prefs.getBoolean("nodoze", false)) {
                    val inflater: LayoutInflater = LayoutInflater.from(this)
                    val view: View = inflater.inflate(R.layout.doze, null, false)
                    val cbDontAsk: CheckBox = view.findViewById(R.id.cbDontAsk)
                    dialogDoze = AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(yes) { _, _ ->
                                prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked).apply()
                                startActivity(doze)
                            }
                            .setNegativeButton(android.R.string.no) { _, _ -> prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked).apply() }
                            .setOnDismissListener {
                                dialogDoze = null
                                checkDataSaving()
                            }
                            .create()
                    dialogDoze!!.show()
                } else checkDataSaving()
            } else checkDataSaving()
        }
    }

    private fun checkDataSaving() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val settings = Intent(
                    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                    Uri.parse("package:$packageName"))
            if (Util.dataSaving(this) && packageManager.resolveActivity(settings, 0) != null) try {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                if (!prefs.getBoolean("nodata", false)) {
                    val inflater: LayoutInflater = LayoutInflater.from(this)
                    val view: View = inflater.inflate(R.layout.datasaving, null, false)
                    val cbDontAsk: CheckBox = view.findViewById(R.id.cbDontAsk)
                    dialogDoze = AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(yes) { _, _ ->
                                prefs.edit().putBoolean("nodata", cbDontAsk.isChecked).apply()
                                startActivity(settings)
                            }
                            .setNegativeButton(android.R.string.no) { _, _ -> prefs.edit().putBoolean("nodata", cbDontAsk.isChecked).apply() }
                            .setOnDismissListener { dialogDoze = null }
                            .create()
                    dialogDoze!!.show()
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + ex.stackTrace)
            }
        }
    }

    private fun menu_legend() {
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOn, tv, true)
        tv.data
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        tv.data

        // Create view
        val inflater: LayoutInflater = LayoutInflater.from(this)
        val view: View = inflater.inflate(R.layout.legend, null, false)


        // Show dialog
        dialogLegend = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener { dialogLegend = null }
                .create()
        dialogLegend!!.show()
    }

    private fun menu_lockdown(item: MenuItem) {
        item.isChecked = !item.isChecked
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("lockdown", item.isChecked).apply()
        ServiceSinkhole.reload("lockdown", this, false)
        WidgetLockdown.updateWidgets(this)
    }

    private fun menu_about() {
        // Create view
        val inflater: LayoutInflater = LayoutInflater.from(this)
        val view: View = inflater.inflate(R.layout.about, null, false)
        val tvVersionName: TextView = view.findViewById(R.id.tvVersionName)
        val tvVersionCode: TextView = view.findViewById(R.id.tvVersionCode)
        val btnRate: Button = view.findViewById(R.id.btnRate)
        val tvEula: TextView = view.findViewById(R.id.tvEula)
        val tvPrivacy: TextView = view.findViewById(R.id.tvPrivacy)

        // Show version
        tvVersionName.text = Util.getSelfVersionName(this)
        if (!Util.hasValidFingerprint(this)) tvVersionName.setTextColor(Color.GRAY)
        tvVersionCode.text = (Util.getSelfVersionCode(this)).toString()

        // Handle license
        tvEula.movementMethod = LinkMovementMethod.getInstance()
        tvPrivacy.movementMethod = LinkMovementMethod.getInstance()

        // Handle logcat
        view.setOnClickListener(object : View.OnClickListener {
            private var tap: Short = 0
            private val toast: Toast = Toast.makeText(this@ActivityMain, "", Toast.LENGTH_SHORT)
            override fun onClick(view: View) {
                tap++
                if (tap.toInt() == 7) {
                    tap = 0
                    toast.cancel()
                    val intent: Intent = intentLogcat
                    if (intent.resolveActivity(packageManager) != null) startActivityForResult(intent, REQUEST_LOGCAT)
                } else if (tap > 3) {
                    toast.setText((7 - tap).toString())
                    toast.show()
                }
            }
        })

        // Handle rate
        btnRate.visibility = if (getIntentRate(this).resolveActivity(packageManager) == null) View.GONE else View.VISIBLE
        btnRate.setOnClickListener { startActivity(getIntentRate(this@ActivityMain)) }

        // Show dialog
        dialogAbout = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener { dialogAbout = null }
                .create()
        dialogAbout!!.show()
    }

    private fun menu_apps() {
        startActivity(getIntentApps(this))
    }

    private val intentLogcat: Intent
        get() {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TITLE, "logcat.txt")
            return intent
        }

    companion object {
        private const val TAG: String = "NetGuard.Main"
        private const val REQUEST_VPN: Int = 1
        private const val REQUEST_INVITE: Int = 2
        private const val REQUEST_LOGCAT: Int = 3
        const val REQUEST_ROAMING: Int = 4
        private const val MIN_SDK: Int = Build.VERSION_CODES.LOLLIPOP_MR1
        const val ACTION_RULES_CHANGED: String = "eu.faircode.netguard.ACTION_RULES_CHANGED"
        const val ACTION_QUEUE_CHANGED: String = "eu.faircode.netguard.ACTION_QUEUE_CHANGED"
        const val EXTRA_REFRESH: String = "Refresh"
        const val EXTRA_SEARCH: String = "Search"
        const val EXTRA_RELATED: String = "Related"
        const val EXTRA_APPROVE: String = "Approve"
        const val EXTRA_LOGCAT: String = "Logcat"
        const val EXTRA_CONNECTED: String = "Connected"
        const val EXTRA_METERED: String = "Metered"
        const val EXTRA_SIZE: String = "Size"
        private fun getIntentPro(context: Context): Intent {
            return if (Util.isPlayStoreInstall(context)) Intent(context, ActivityPro::class.java) else {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://contact.faircode.eu/?product=netguardstandalone")
                intent
            }
        }

        private fun getIntentInvite(context: Context): Intent {
            val intent = Intent(Intent.ACTION_SEND)
            intent.setType("text/plain")
            intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
            intent.putExtra(Intent.EXTRA_TEXT, context.getString(R.string.msg_try) + "\n\nhttps://www.netguard.me/\n\n")
            return intent
        }

        private fun getIntentApps(context: Context): Intent {
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/dev?id=8420080860664580239"))
        }

        private fun getIntentRate(context: Context): Intent {
            var intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.packageName))
            if (intent.resolveActivity(context.packageManager) == null) intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + context.packageName))
            return intent
        }

        private val intentSupport: Intent
            get() {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://github.com/M66B/NetGuard/blob/master/FAQ.md")
                return intent
            }
    }
}