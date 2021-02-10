package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.database.Cursor
import android.graphics.*
import android.net.*
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import eu.faircode.netguard.Util.DoubtListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
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
*/   class AdapterRule constructor(context: Context, anchor: View) : RecyclerView.Adapter<AdapterRule.ViewHolder>(), Filterable {
    private val anchor: View
    private val inflater: LayoutInflater
    private var rv: RecyclerView? = null
    private var colorText: Int = 0
    private var colorChanged: Int = 0
    private val colorOn: Int
    private val colorOff: Int
    private val colorGrayed: Int
    private val iconSize: Int
    private var wifiActive: Boolean = true
    private var otherActive: Boolean = true
    var isLive: Boolean = true
        private set
    private var listAll: List<Rule?> = ArrayList()
    private var listFiltered: MutableList<Rule?> = ArrayList()
    private val messaging: List<String?> = listOf(
            "com.discord",
            "com.facebook.mlite",
            "com.facebook.orca",
            "com.instagram.android",
            "com.Slack",
            "com.skype.raider",
            "com.snapchat.android",
            "com.whatsapp",
            "com.whatsapp.w4b"
    )
    private val download: List<String?> = listOf(
            "com.google.android.youtube"
    )
    val uiScope = CoroutineScope(Dispatchers.Main)


    class ViewHolder constructor(var view: View) : RecyclerView.ViewHolder(view) {
        var llApplication: LinearLayout = itemView.findViewById(R.id.llApplication)
        var ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        var ivExpander: ImageView = itemView.findViewById(R.id.ivExpander)
        var tvName: TextView = itemView.findViewById(R.id.tvName)
        var tvHosts: TextView = itemView.findViewById(R.id.tvHosts)
        var rlLockdown: RelativeLayout = itemView.findViewById(R.id.rlLockdown)
        var ivLockdown: ImageView = itemView.findViewById(R.id.ivLockdown)
        var cbWifi: CheckBox = itemView.findViewById(R.id.cbWifi)
        var ivScreenWifi: ImageView = itemView.findViewById(R.id.ivScreenWifi)
        var cbOther: CheckBox = itemView.findViewById(R.id.cbOther)
        var ivScreenOther: ImageView = itemView.findViewById(R.id.ivScreenOther)
        var tvRoaming: TextView = itemView.findViewById(R.id.tvRoaming)
        var tvRemarkMessaging: TextView = itemView.findViewById(R.id.tvRemarkMessaging)
        var tvRemarkDownload: TextView = itemView.findViewById(R.id.tvRemarkDownload)
        var llConfiguration: LinearLayout = itemView.findViewById(R.id.llConfiguration)
        var tvUid: TextView = itemView.findViewById(R.id.tvUid)
        var tvPackage: TextView = itemView.findViewById(R.id.tvPackage)
        var tvVersion: TextView = itemView.findViewById(R.id.tvVersion)
        var tvInternet: TextView = itemView.findViewById(R.id.tvInternet)
        var tvDisabled: TextView = itemView.findViewById(R.id.tvDisabled)
        var btnRelated: Button = itemView.findViewById(R.id.btnRelated)
        var ibSettings: ImageButton = itemView.findViewById(R.id.ibSettings)
        var ibLaunch: ImageButton = itemView.findViewById(R.id.ibLaunch)
        var cbApply: CheckBox = itemView.findViewById(R.id.cbApply)
        var llScreenWifi: LinearLayout = itemView.findViewById(R.id.llScreenWifi)
        var ivWifiLegend: ImageView = itemView.findViewById(R.id.ivWifiLegend)
        var cbScreenWifi: CheckBox = itemView.findViewById(R.id.cbScreenWifi)
        var llScreenOther: LinearLayout = itemView.findViewById(R.id.llScreenOther)
        var cbScreenOther: CheckBox = itemView.findViewById(R.id.cbScreenOther)
        var cbRoaming: CheckBox = itemView.findViewById(R.id.cbRoaming)
        var cbLockdown: CheckBox = itemView.findViewById(R.id.cbLockdown)
        var ivLockdownLegend: ImageView = itemView.findViewById(R.id.ivLockdownLegend)
        var btnClear: ImageButton = itemView.findViewById(R.id.btnClear)
        var llFilter: LinearLayout = itemView.findViewById(R.id.llFilter)
        var ivLive: ImageView = itemView.findViewById(R.id.ivLive)
        var tvLogging: TextView = itemView.findViewById(R.id.tvLogging)
        var btnLogging: Button = itemView.findViewById(R.id.btnLogging)
        var lvAccess: ListView = itemView.findViewById(R.id.lvAccess)
        var btnClearAccess: ImageButton = itemView.findViewById(R.id.btnClearAccess)
        var cbNotify: CheckBox = itemView.findViewById(R.id.cbNotify)
        val uiScope = CoroutineScope(Dispatchers.Main)

        init {
            val wifiParent: View = cbWifi.parent as View
            wifiParent.post {
                val rect = Rect()
                cbWifi.getHitRect(rect)
                rect.bottom += rect.top
                rect.right += rect.left
                rect.top = 0
                rect.left = 0
                wifiParent.touchDelegate = TouchDelegate(rect, cbWifi)
            }
            val otherParent: View = cbOther.parent as View
            otherParent.post {
                val rect = Rect()
                cbOther.getHitRect(rect)
                rect.bottom += rect.top
                rect.right += rect.left
                rect.top = 0
                rect.left = 0
                otherParent.touchDelegate = TouchDelegate(rect, cbOther)
            }
        }
    }

    fun set(listRule: List<Rule?>) {
        listAll = listRule
        listFiltered = ArrayList()
        listFiltered.addAll(listRule)
        notifyDataSetChanged()
    }

    fun setWifiActive() {
        wifiActive = true
        otherActive = false
        notifyDataSetChanged()
    }

    fun setMobileActive() {
        wifiActive = false
        otherActive = true
        notifyDataSetChanged()
    }

    fun setDisconnected() {
        wifiActive = false
        otherActive = false
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rv = null
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context: Context = holder.itemView.context
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val log_app: Boolean = prefs.getBoolean("log_app", false)
        val filter: Boolean = prefs.getBoolean("filter", false)
        val notify_access: Boolean = prefs.getBoolean("notify_access", false)

        // Get rule
        val rule: Rule? = listFiltered[position]

        // Handle expanding/collapsing
        holder.llApplication.setOnClickListener {
            rule!!.expanded = !rule.expanded
            notifyItemChanged(holder.adapterPosition)
        }

        // Show if non default rules
        holder.itemView.setBackgroundColor(if (rule!!.changed) colorChanged else Color.TRANSPARENT)

        // Show expand/collapse indicator
        holder.ivExpander.setImageLevel(if (rule.expanded) 1 else 0)

        // Show application icon
        if (rule.icon <= 0) holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon) else {
            val uri: Uri = Uri.parse("android.resource://" + rule.packageName + "/" + rule.icon)
            GlideApp.with(holder.itemView.context)
                    .applyDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(uri) //.diskCacheStrategy(DiskCacheStrategy.NONE)
                    //.skipMemoryCache(true)
                    .override(iconSize, iconSize)
                    .into(holder.ivIcon)
        }

        // Show application label
        holder.tvName.text = rule.name

        // Show application state
        var color: Int = if (rule.system) colorOff else colorText
        if (!rule.internet || !rule.enabled) color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        holder.tvName.setTextColor(color)
        holder.tvHosts.visibility = if (rule.hosts > 0) View.VISIBLE else View.GONE
        holder.tvHosts.text = rule.hosts.toString()

        // Lockdown settings
        var lockdown: Boolean = prefs.getBoolean("lockdown", false)
        val lockdown_wifi: Boolean = prefs.getBoolean("lockdown_wifi", true)
        val lockdown_other: Boolean = prefs.getBoolean("lockdown_other", true)
        if ((otherActive && !lockdown_other) || (wifiActive && !lockdown_wifi)) lockdown = false
        holder.rlLockdown.visibility = if (lockdown && !rule.lockdown) View.VISIBLE else View.GONE
        holder.ivLockdown.isEnabled = rule.apply
        val screen_on: Boolean = prefs.getBoolean("screen_on", true)

        // Wi-Fi settings
        holder.cbWifi.isEnabled = rule.apply
        holder.cbWifi.alpha = if (wifiActive) 1f else 0.5f
        holder.cbWifi.setOnCheckedChangeListener(null)
        holder.cbWifi.isChecked = rule.wifi_blocked
        holder.cbWifi.setOnCheckedChangeListener { _, isChecked ->
            rule.wifi_blocked = isChecked
            updateRule(context, rule, true, listAll)
        }
        holder.ivScreenWifi.isEnabled = rule.apply
        holder.ivScreenWifi.alpha = if (wifiActive) 1f else 0.5f
        holder.ivScreenWifi.visibility = if (rule.screen_wifi && rule.wifi_blocked) View.VISIBLE else View.INVISIBLE

        // Mobile settings
        holder.cbOther.isEnabled = rule.apply
        holder.cbOther.alpha = if (otherActive) 1f else 0.5f
        holder.cbOther.setOnCheckedChangeListener(null)
        holder.cbOther.isChecked = rule.other_blocked
        holder.cbOther.setOnCheckedChangeListener { _, isChecked ->
            rule.other_blocked = isChecked
            updateRule(context, rule, true, listAll)
        }
        holder.ivScreenOther.isEnabled = rule.apply
        holder.ivScreenOther.alpha = if (otherActive) 1f else 0.5f
        holder.ivScreenOther.visibility = if (rule.screen_other && rule.other_blocked) View.VISIBLE else View.INVISIBLE
        holder.tvRoaming.setTextColor(if (rule.apply) colorOff else colorGrayed)
        holder.tvRoaming.alpha = if (otherActive) 1f else 0.5f
        holder.tvRoaming.visibility = if (rule.roaming && (!rule.other_blocked || rule.screen_other)) View.VISIBLE else View.INVISIBLE
        holder.tvRemarkMessaging.visibility = if (messaging.contains(rule.packageName)) View.VISIBLE else View.GONE
        holder.tvRemarkDownload.visibility = if (download.contains(rule.packageName)) View.VISIBLE else View.GONE

        // Expanded configuration section
        holder.llConfiguration.visibility = if (rule.expanded) View.VISIBLE else View.GONE

        // Show application details
        holder.tvUid.text = rule.uid.toString()
        holder.tvPackage.text = rule.packageName
        holder.tvVersion.text = rule.version

        // Show application state
        holder.tvInternet.visibility = if (rule.internet) View.GONE else View.VISIBLE
        holder.tvDisabled.visibility = if (rule.enabled) View.GONE else View.VISIBLE

        // Show related
        holder.btnRelated.visibility = if (rule.relateduids) View.VISIBLE else View.GONE
        holder.btnRelated.setOnClickListener {
            val main = Intent(context, ActivityMain::class.java)
            main.putExtra(ActivityMain.EXTRA_SEARCH, rule.uid.toString())
            main.putExtra(ActivityMain.EXTRA_RELATED, true)
            context.startActivity(main)
        }

        // Launch application settings
        if (rule.expanded) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + rule.packageName)
            val settings: Intent? = (if (intent.resolveActivity(context.packageManager) == null) null else intent)
            holder.ibSettings.visibility = if (settings == null) View.GONE else View.VISIBLE
            holder.ibSettings.setOnClickListener { context.startActivity(settings) }
        } else holder.ibSettings.visibility = View.GONE

        // Launch application
        if (rule.expanded) {
            val intent: Intent? = context.packageManager.getLaunchIntentForPackage(rule.packageName)
            val launch: Intent? = (if (intent?.resolveActivity(context.packageManager) == null) null else intent)
            holder.ibLaunch.visibility = if (launch == null) View.GONE else View.VISIBLE
            holder.ibLaunch.setOnClickListener { context.startActivity(launch) }
        } else holder.ibLaunch.visibility = View.GONE

        // Apply
        holder.cbApply.isEnabled = rule.pkg && filter
        holder.cbApply.setOnCheckedChangeListener(null)
        holder.cbApply.isChecked = rule.apply
        holder.cbApply.setOnCheckedChangeListener { _, isChecked ->
            rule.apply = isChecked
            updateRule(context, rule, true, listAll)
        }

        // Show Wi-Fi screen on condition
        holder.llScreenWifi.visibility = if (screen_on) View.VISIBLE else View.GONE
        holder.cbScreenWifi.isEnabled = rule.wifi_blocked && rule.apply
        holder.cbScreenWifi.setOnCheckedChangeListener(null)
        holder.cbScreenWifi.isChecked = rule.screen_wifi
        holder.cbScreenWifi.setOnCheckedChangeListener { _, isChecked ->
            rule.screen_wifi = isChecked
            updateRule(context, rule, true, listAll)
        }

        // Show mobile screen on condition
        holder.llScreenOther.visibility = if (screen_on) View.VISIBLE else View.GONE
        holder.cbScreenOther.isEnabled = rule.other_blocked && rule.apply
        holder.cbScreenOther.setOnCheckedChangeListener(null)
        holder.cbScreenOther.isChecked = rule.screen_other
        holder.cbScreenOther.setOnCheckedChangeListener { _, isChecked ->
            rule.screen_other = isChecked
            updateRule(context, rule, true, listAll)
        }

        // Show roaming condition
        holder.cbRoaming.isEnabled = (!rule.other_blocked || rule.screen_other) && rule.apply
        holder.cbRoaming.setOnCheckedChangeListener(null)
        holder.cbRoaming.isChecked = rule.roaming
        holder.cbRoaming.setOnCheckedChangeListener { _, isChecked ->
            rule.roaming = isChecked
            updateRule(context, rule, true, listAll)
        }

        // Show lockdown
        holder.cbLockdown.isEnabled = rule.apply
        holder.cbLockdown.setOnCheckedChangeListener(null)
        holder.cbLockdown.isChecked = rule.lockdown
        holder.cbLockdown.setOnCheckedChangeListener { _, isChecked ->
            rule.lockdown = isChecked
            updateRule(context, rule, true, listAll)
        }

        // Reset rule
        holder.btnClear.setOnClickListener { view ->
            Util.areYouSure(view.context, R.string.msg_clear_rules, object : DoubtListener {
                override fun onSure() {
                    holder.cbApply.isChecked = true
                    holder.cbWifi.isChecked = rule.wifi_default
                    holder.cbOther.isChecked = rule.other_default
                    holder.cbScreenWifi.isChecked = rule.screen_wifi_default
                    holder.cbScreenOther.isChecked = rule.screen_other_default
                    holder.cbRoaming.isChecked = rule.roaming_default
                    holder.cbLockdown.isChecked = false
                }
            })
        }
        holder.llFilter.visibility = if (Util.canFilter()) View.VISIBLE else View.GONE

        // Live
        holder.ivLive.setOnClickListener { view ->
            isLive = !isLive
            val tv = TypedValue()
            view.context.theme.resolveAttribute(if (isLive) R.attr.iconPause else R.attr.iconPlay, tv, true)
            holder.ivLive.setImageResource(tv.resourceId)
            if (isLive) notifyDataSetChanged()
        }

        // Show logging/filtering is disabled
        holder.tvLogging.setText(if (log_app && filter) R.string.title_logging_enabled else R.string.title_logging_disabled)
        holder.btnLogging.setOnClickListener {
            val inflater: LayoutInflater = LayoutInflater.from(context)
            val view: View = inflater.inflate(R.layout.enable, null, false)
            val cbLogging: CheckBox = view.findViewById(R.id.cbLogging)
            val cbFiltering: CheckBox = view.findViewById(R.id.cbFiltering)
            val cbNotify: CheckBox = view.findViewById(R.id.cbNotify)
            val tvFilter4: TextView = view.findViewById(R.id.tvFilter4)
            cbLogging.isChecked = log_app
            cbFiltering.isChecked = filter
            cbFiltering.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            tvFilter4.visibility = View.GONE
            cbNotify.isChecked = notify_access
            cbNotify.isEnabled = log_app
            cbLogging.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("log_app", checked).apply()
                cbNotify.isEnabled = checked
                if (!checked) {
                    cbNotify.isChecked = false
                    prefs.edit().putBoolean("notify_access", false).apply()
                    ServiceSinkhole.reload("changed notify", context, false)
                }
                notifyDataSetChanged()
            }
            cbFiltering.setOnCheckedChangeListener { _, checked ->
                if (checked) cbLogging.isChecked = true
                prefs.edit().putBoolean("filter", checked).apply()
                ServiceSinkhole.reload("changed filter", context, false)
                notifyDataSetChanged()
            }
            cbNotify.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("notify_access", checked).apply()
                ServiceSinkhole.reload("changed notify", context, false)
                notifyDataSetChanged()
            }
            val dialog: AlertDialog = AlertDialog.Builder(context)
                    .setView(view)
                    .setCancelable(true)
                    .create()
            dialog.show()
        }

        // Show access rules
        if (rule.expanded) {
            // Access the database when expanded only
            val badapter = AdapterAccess(context,
                    DatabaseHelper.getInstance(context).getAccess(rule.uid))
            holder.lvAccess.onItemClickListener = object : OnItemClickListener {
                override fun onItemClick(parent: AdapterView<*>?, view: View, bposition: Int, bid: Long) {
                    val pm: PackageManager = context.packageManager
                    val cursor: Cursor = badapter.getItem(bposition) as Cursor
                    val id: Long = cursor.getLong(cursor.getColumnIndex("ID"))
                    val version: Int = cursor.getInt(cursor.getColumnIndex("version"))
                    val protocol: Int = cursor.getInt(cursor.getColumnIndex("protocol"))
                    val daddr: String = cursor.getString(cursor.getColumnIndex("daddr"))
                    val dport: Int = cursor.getInt(cursor.getColumnIndex("dport"))
                    val time: Long = cursor.getLong(cursor.getColumnIndex("time"))
                    val block: Int = cursor.getInt(cursor.getColumnIndex("block"))
                    val popup = PopupMenu(context, anchor)
                    popup.inflate(R.menu.access)
                    popup.menu.findItem(R.id.menu_host).title = (Util.getProtocolName(protocol, version, false) + " " +
                            daddr + (if (dport > 0) "/$dport" else ""))
                    val sub: SubMenu = popup.menu.findItem(R.id.menu_host).subMenu
                    var multiple = false
                    var alt: Cursor? = null
                    try {
                        alt = DatabaseHelper.getInstance(context).getAlternateQNames(daddr)
                        while (alt.moveToNext()) {
                            multiple = true
                            sub.add(Menu.NONE, Menu.NONE, 0, alt.getString(0)).isEnabled = false
                        }
                    } finally {
                        alt?.close()
                    }
                    popup.menu.findItem(R.id.menu_host).isEnabled = multiple
                    markPro(context, popup.menu.findItem(R.id.menu_allow), ActivityPro.SKU_FILTER)
                    markPro(context, popup.menu.findItem(R.id.menu_block), ActivityPro.SKU_FILTER)

                    // Whois
                    val lookupIP = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dnslytics.com/whois-lookup/$daddr"))
                    if (pm.resolveActivity(lookupIP, 0) == null) popup.menu.removeItem(R.id.menu_whois) else popup.menu.findItem(R.id.menu_whois).title = context.getString(R.string.title_log_whois, daddr)

                    // Lookup port
                    val lookupPort = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedguide.net/port.php?port=$dport"))
                    if (dport <= 0 || pm.resolveActivity(lookupPort, 0) == null) popup.menu.removeItem(R.id.menu_port) else popup.menu.findItem(R.id.menu_port).title = context.getString(R.string.title_log_port, dport)
                    popup.menu.findItem(R.id.menu_time).title = SimpleDateFormat.getDateTimeInstance().format(time)
                    popup.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
                        override fun onMenuItemClick(menuItem: MenuItem): Boolean {
                            val menu: Int = menuItem.itemId
                            var result = false
                            when (menu) {
                                R.id.menu_whois -> {
                                    context.startActivity(lookupIP)
                                    result = true
                                }
                                R.id.menu_port -> {
                                    context.startActivity(lookupPort)
                                    result = true
                                }
                                R.id.menu_allow -> {
                                    if (IAB.isPurchased(ActivityPro.SKU_FILTER, context)) {
                                        DatabaseHelper.getInstance(context).setAccess(id, 0)
                                        ServiceSinkhole.reload("allow host", context, false)
                                    } else context.startActivity(Intent(context, ActivityPro::class.java))
                                    result = true
                                }
                                R.id.menu_block -> {
                                    if (IAB.isPurchased(ActivityPro.SKU_FILTER, context)) {
                                        DatabaseHelper.getInstance(context).setAccess(id, 1)
                                        ServiceSinkhole.reload("block host", context, false)
                                    } else context.startActivity(Intent(context, ActivityPro::class.java))
                                    result = true
                                }
                                R.id.menu_reset -> {
                                    DatabaseHelper.getInstance(context).setAccess(id, -1)
                                    ServiceSinkhole.reload("reset host", context, false)
                                    result = true
                                }
                                R.id.menu_copy -> {
                                    val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip: ClipData = ClipData.newPlainText("netguard", daddr)
                                    clipboard.setPrimaryClip(clip)
                                    return true
                                }
                            }
                            if ((menu == R.id.menu_allow) || (menu == R.id.menu_block) || (menu == R.id.menu_reset)) uiScope.launch {
                                withContext(Dispatchers.Default) {
                                    val hosts = DatabaseHelper.getInstance(context).getHostCount(rule.uid, false)
                                    withContext(Dispatchers.Main) {
                                        rule.hosts = hosts
                                        notifyDataSetChanged()
                                    }
                                }
                            }
                            return result
                        }
                    })
                    if (block == 0) popup.menu.removeItem(R.id.menu_allow) else if (block == 1) popup.menu.removeItem(R.id.menu_block)
                    popup.show()
                }
            }
            holder.lvAccess.adapter = badapter
        } else {
            holder.lvAccess.adapter = null
            holder.lvAccess.onItemClickListener = null
        }

        // Clear access log
        holder.btnClearAccess.setOnClickListener { view ->
            Util.areYouSure(view.context, R.string.msg_reset_access, object : DoubtListener {
                override fun onSure() {
                    DatabaseHelper.getInstance(context).clearAccess(rule.uid, true)
                    if (!isLive) notifyDataSetChanged()
                    if (rv != null) rv!!.scrollToPosition(holder.adapterPosition)
                }
            })
        }

        // Notify on access
        holder.cbNotify.isEnabled = prefs.getBoolean("notify_access", false) && rule.apply
        holder.cbNotify.setOnCheckedChangeListener(null)
        holder.cbNotify.isChecked = rule.notify
        holder.cbNotify.setOnCheckedChangeListener { _, isChecked ->
            rule.notify = isChecked
            updateRule(context, rule, true, listAll)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        //Context context = holder.itemView.getContext();
        //GlideApp.with(context).clear(holder.ivIcon);
        val adapter: CursorAdapter? = holder.lvAccess.adapter as CursorAdapter?
        if (adapter != null) {
            Log.i(TAG, "Closing access cursor")
            adapter.changeCursor(null)
            holder.lvAccess.adapter = null
        }
    }

    private fun markPro(context: Context, menu: MenuItem, sku: String?) {
        if (sku == null || !IAB.isPurchased(sku, context)) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val dark: Boolean = prefs.getBoolean("dark_theme", false)
            val ssb = SpannableStringBuilder("  " + menu.title)
            ssb.setSpan(ImageSpan(context, if (dark) R.drawable.ic_shopping_cart_white_24dp else R.drawable.ic_shopping_cart_black_24dp), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            menu.title = ssb
        }
    }

    private fun updateRule(context: Context, rule: Rule?, root: Boolean, listAll: List<Rule?>) {
        val wifi: SharedPreferences = context.getSharedPreferences("wifi", Context.MODE_PRIVATE)
        val other: SharedPreferences = context.getSharedPreferences("other", Context.MODE_PRIVATE)
        val apply: SharedPreferences = context.getSharedPreferences("apply", Context.MODE_PRIVATE)
        val screen_wifi: SharedPreferences = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE)
        val screen_other: SharedPreferences = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE)
        val roaming: SharedPreferences = context.getSharedPreferences("roaming", Context.MODE_PRIVATE)
        val lockdown: SharedPreferences = context.getSharedPreferences("lockdown", Context.MODE_PRIVATE)
        val notify: SharedPreferences = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
        if (rule!!.wifi_blocked == rule.wifi_default) wifi.edit().remove(rule.packageName).apply() else wifi.edit().putBoolean(rule.packageName, rule.wifi_blocked).apply()
        if (rule.other_blocked == rule.other_default) other.edit().remove(rule.packageName).apply() else other.edit().putBoolean(rule.packageName, rule.other_blocked).apply()
        if (rule.apply) apply.edit().remove(rule.packageName).apply() else apply.edit().putBoolean(rule.packageName, rule.apply).apply()
        if (rule.screen_wifi == rule.screen_wifi_default) screen_wifi.edit().remove(rule.packageName).apply() else screen_wifi.edit().putBoolean(rule.packageName, rule.screen_wifi).apply()
        if (rule.screen_other == rule.screen_other_default) screen_other.edit().remove(rule.packageName).apply() else screen_other.edit().putBoolean(rule.packageName, rule.screen_other).apply()
        if (rule.roaming == rule.roaming_default) roaming.edit().remove(rule.packageName).apply() else roaming.edit().putBoolean(rule.packageName, rule.roaming).apply()
        if (rule.lockdown) lockdown.edit().putBoolean(rule.packageName, rule.lockdown).apply() else lockdown.edit().remove(rule.packageName).apply()
        if (rule.notify) notify.edit().remove(rule.packageName).apply() else notify.edit().putBoolean(rule.packageName, rule.notify).apply()
        rule.updateChanged(context)
        Log.i(TAG, "Updated $rule")
        val listModified: MutableList<Rule?> = ArrayList()
        for (pkg: String? in rule.related!!) {
            for (related: Rule? in listAll) if ((related!!.packageName == pkg)) {
                related.wifi_blocked = rule.wifi_blocked
                related.other_blocked = rule.other_blocked
                related.apply = rule.apply
                related.screen_wifi = rule.screen_wifi
                related.screen_other = rule.screen_other
                related.roaming = rule.roaming
                related.lockdown = rule.lockdown
                related.notify = rule.notify
                listModified.add(related)
            }
        }
        val listSearch: MutableList<Rule?> = (if (root) ArrayList(listAll) else listAll as MutableList<Rule?>)
        listSearch.remove(rule)
        for (modified: Rule? in listModified) listSearch.remove(modified)
        for (modified: Rule? in listModified) updateRule(context, modified, false, listSearch)
        if (root) {
            notifyDataSetChanged()
            NotificationManagerCompat.from(context).cancel(rule.uid)
            ServiceSinkhole.reload("rule changed", context, false)
        }
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(query: CharSequence): FilterResults {
                var query: CharSequence? = query
                val listResult: MutableList<Rule?> = ArrayList()
                if (query == null) listResult.addAll(listAll) else {
                    query = query.toString().toLowerCase(Locale.ROOT).trim { it <= ' ' }
                    val uid: Int = try {
                        query.toString().toInt()
                    } catch (ignore: NumberFormatException) {
                        -1
                    }
                    for (rule: Rule? in listAll) if (((rule!!.uid == uid) ||
                                    rule.packageName.toLowerCase(Locale.ROOT).contains(query) ||
                                    (rule.name != null && rule.name!!.toLowerCase(Locale.ROOT).contains(query)))) listResult.add(rule)
                }
                val result = FilterResults()
                result.values = listResult
                result.count = listResult.size
                return result
            }

            override fun publishResults(query: CharSequence, result: FilterResults) {
                listFiltered.clear()
                listFiltered.addAll(result.values as Collection<Rule?>)
                if (listFiltered.size == 1) listFiltered[0]!!.expanded = true
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(inflater.inflate(R.layout.rule, parent, false))
    }

    override fun getItemId(position: Int): Long {
        val rule: Rule? = listFiltered[position]
        return rule!!.packageName.hashCode() * 100000L + rule.uid
    }

    override fun getItemCount(): Int {
        return listFiltered.size
    }

    companion object {
        private const val TAG: String = "NetGuard.Adapter"
    }

    init {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        this.anchor = anchor
        inflater = LayoutInflater.from(context)
        colorChanged = if (prefs.getBoolean("dark_theme", false)) Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY), Color.blue(Color.DKGRAY)) else Color.argb(128, Color.red(Color.LTGRAY), Color.green(Color.LTGRAY), Color.blue(Color.LTGRAY))
        val ta: TypedArray = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            colorText = ta.getColor(0, 0)
        } finally {
            ta.recycle()
        }
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOn, tv, true)
        colorOn = tv.data
        context.theme.resolveAttribute(R.attr.colorOff, tv, true)
        colorOff = tv.data
        colorGrayed = ContextCompat.getColor(context, R.color.colorGrayed)
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true)
        val height: Int = TypedValue.complexToDimensionPixelSize(typedValue.data, context.resources.displayMetrics)
        iconSize = (height * context.resources.displayMetrics.density + 0.5f).roundToInt()
        setHasStableIds(true)
    }
}

