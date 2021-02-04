package eu.faircode.netguard

import android.annotation.TargetApi
import android.content.*
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.database.Cursor
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.*
import android.os.AsyncTask
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
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import eu.faircode.netguard.Util.DoubtListener
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
    private val messaging: List<String?> = Arrays.asList(
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
    private val download: List<String?> = Arrays.asList(
            "com.google.android.youtube"
    )

    class ViewHolder constructor(var view: View) : RecyclerView.ViewHolder(view) {
        var llApplication: LinearLayout
        var ivIcon: ImageView
        var ivExpander: ImageView
        var tvName: TextView
        var tvHosts: TextView
        var rlLockdown: RelativeLayout
        var ivLockdown: ImageView
        var cbWifi: CheckBox
        var ivScreenWifi: ImageView
        var cbOther: CheckBox
        var ivScreenOther: ImageView
        var tvRoaming: TextView
        var tvRemarkMessaging: TextView
        var tvRemarkDownload: TextView
        var llConfiguration: LinearLayout
        var tvUid: TextView
        var tvPackage: TextView
        var tvVersion: TextView
        var tvInternet: TextView
        var tvDisabled: TextView
        var btnRelated: Button
        var ibSettings: ImageButton
        var ibLaunch: ImageButton
        var cbApply: CheckBox
        var llScreenWifi: LinearLayout
        var ivWifiLegend: ImageView
        var cbScreenWifi: CheckBox
        var llScreenOther: LinearLayout
        var ivOtherLegend: ImageView
        var cbScreenOther: CheckBox
        var cbRoaming: CheckBox
        var cbLockdown: CheckBox
        var ivLockdownLegend: ImageView
        var btnClear: ImageButton
        var llFilter: LinearLayout
        var ivLive: ImageView
        var tvLogging: TextView
        var btnLogging: Button
        var lvAccess: ListView
        var btnClearAccess: ImageButton
        var cbNotify: CheckBox

        init {
            llApplication = itemView.findViewById(R.id.llApplication)
            ivIcon = itemView.findViewById(R.id.ivIcon)
            ivExpander = itemView.findViewById(R.id.ivExpander)
            tvName = itemView.findViewById(R.id.tvName)
            tvHosts = itemView.findViewById(R.id.tvHosts)
            rlLockdown = itemView.findViewById(R.id.rlLockdown)
            ivLockdown = itemView.findViewById(R.id.ivLockdown)
            cbWifi = itemView.findViewById(R.id.cbWifi)
            ivScreenWifi = itemView.findViewById(R.id.ivScreenWifi)
            cbOther = itemView.findViewById(R.id.cbOther)
            ivScreenOther = itemView.findViewById(R.id.ivScreenOther)
            tvRoaming = itemView.findViewById(R.id.tvRoaming)
            tvRemarkMessaging = itemView.findViewById(R.id.tvRemarkMessaging)
            tvRemarkDownload = itemView.findViewById(R.id.tvRemarkDownload)
            llConfiguration = itemView.findViewById(R.id.llConfiguration)
            tvUid = itemView.findViewById(R.id.tvUid)
            tvPackage = itemView.findViewById(R.id.tvPackage)
            tvVersion = itemView.findViewById(R.id.tvVersion)
            tvInternet = itemView.findViewById(R.id.tvInternet)
            tvDisabled = itemView.findViewById(R.id.tvDisabled)
            btnRelated = itemView.findViewById(R.id.btnRelated)
            ibSettings = itemView.findViewById(R.id.ibSettings)
            ibLaunch = itemView.findViewById(R.id.ibLaunch)
            cbApply = itemView.findViewById(R.id.cbApply)
            llScreenWifi = itemView.findViewById(R.id.llScreenWifi)
            ivWifiLegend = itemView.findViewById(R.id.ivWifiLegend)
            cbScreenWifi = itemView.findViewById(R.id.cbScreenWifi)
            llScreenOther = itemView.findViewById(R.id.llScreenOther)
            ivOtherLegend = itemView.findViewById(R.id.ivOtherLegend)
            cbScreenOther = itemView.findViewById(R.id.cbScreenOther)
            cbRoaming = itemView.findViewById(R.id.cbRoaming)
            cbLockdown = itemView.findViewById(R.id.cbLockdown)
            ivLockdownLegend = itemView.findViewById(R.id.ivLockdownLegend)
            btnClear = itemView.findViewById(R.id.btnClear)
            llFilter = itemView.findViewById(R.id.llFilter)
            ivLive = itemView.findViewById(R.id.ivLive)
            tvLogging = itemView.findViewById(R.id.tvLogging)
            btnLogging = itemView.findViewById(R.id.btnLogging)
            lvAccess = itemView.findViewById(R.id.lvAccess)
            btnClearAccess = itemView.findViewById(R.id.btnClearAccess)
            cbNotify = itemView.findViewById(R.id.cbNotify)
            val wifiParent: View = cbWifi.getParent() as View
            wifiParent.post(object : Runnable {
                public override fun run() {
                    val rect: Rect = Rect()
                    cbWifi.getHitRect(rect)
                    rect.bottom += rect.top
                    rect.right += rect.left
                    rect.top = 0
                    rect.left = 0
                    wifiParent.setTouchDelegate(TouchDelegate(rect, cbWifi))
                }
            })
            val otherParent: View = cbOther.getParent() as View
            otherParent.post(object : Runnable {
                public override fun run() {
                    val rect: Rect = Rect()
                    cbOther.getHitRect(rect)
                    rect.bottom += rect.top
                    rect.right += rect.left
                    rect.top = 0
                    rect.left = 0
                    otherParent.setTouchDelegate(TouchDelegate(rect, cbOther))
                }
            })
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

    public override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    public override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rv = null
    }

    public override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context: Context = holder.itemView.getContext()
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val log_app: Boolean = prefs.getBoolean("log_app", false)
        val filter: Boolean = prefs.getBoolean("filter", false)
        val notify_access: Boolean = prefs.getBoolean("notify_access", false)

        // Get rule
        val rule: Rule? = listFiltered.get(position)

        // Handle expanding/collapsing
        holder.llApplication.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(view: View) {
                rule!!.expanded = !rule.expanded
                notifyItemChanged(holder.getAdapterPosition())
            }
        })

        // Show if non default rules
        holder.itemView.setBackgroundColor(if (rule!!.changed) colorChanged else Color.TRANSPARENT)

        // Show expand/collapse indicator
        holder.ivExpander.setImageLevel(if (rule.expanded) 1 else 0)

        // Show application icon
        if (rule.icon <= 0) holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon) else {
            val uri: Uri = Uri.parse("android.resource://" + rule.packageName + "/" + rule.icon)
            GlideApp.with(holder.itemView.getContext())
                    .applyDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(uri) //.diskCacheStrategy(DiskCacheStrategy.NONE)
                    //.skipMemoryCache(true)
                    .override(iconSize, iconSize)
                    .into(holder.ivIcon)
        }

        // Show application label
        holder.tvName.setText(rule.name)

        // Show application state
        var color: Int = if (rule.system) colorOff else colorText
        if (!rule.internet || !rule.enabled) color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        holder.tvName.setTextColor(color)
        holder.tvHosts.setVisibility(if (rule.hosts > 0) View.VISIBLE else View.GONE)
        holder.tvHosts.setText(java.lang.Long.toString(rule.hosts))

        // Lockdown settings
        var lockdown: Boolean = prefs.getBoolean("lockdown", false)
        val lockdown_wifi: Boolean = prefs.getBoolean("lockdown_wifi", true)
        val lockdown_other: Boolean = prefs.getBoolean("lockdown_other", true)
        if ((otherActive && !lockdown_other) || (wifiActive && !lockdown_wifi)) lockdown = false
        holder.rlLockdown.setVisibility(if (lockdown && !rule.lockdown) View.VISIBLE else View.GONE)
        holder.ivLockdown.setEnabled(rule.apply)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivLockdown.getDrawable())
            DrawableCompat.setTint(wrap, if (rule.apply) colorOff else colorGrayed)
        }
        val screen_on: Boolean = prefs.getBoolean("screen_on", true)

        // Wi-Fi settings
        holder.cbWifi.setEnabled(rule.apply)
        holder.cbWifi.setAlpha(if (wifiActive) 1 else 0.5f)
        holder.cbWifi.setOnCheckedChangeListener(null)
        holder.cbWifi.setChecked(rule.wifi_blocked)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap((CompoundButtonCompat.getButtonDrawable(holder.cbWifi))!!)
            DrawableCompat.setTint(wrap, if (rule.apply) (if (rule.wifi_blocked) colorOff else colorOn) else colorGrayed)
        }
        holder.cbWifi.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(compoundButton: CompoundButton, isChecked: Boolean) {
                rule.wifi_blocked = isChecked
                updateRule(context, rule, true, listAll)
            }
        })
        holder.ivScreenWifi.setEnabled(rule.apply)
        holder.ivScreenWifi.setAlpha(if (wifiActive) 1 else 0.5f)
        holder.ivScreenWifi.setVisibility(if (rule.screen_wifi && rule.wifi_blocked) View.VISIBLE else View.INVISIBLE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivScreenWifi.getDrawable())
            DrawableCompat.setTint(wrap, if (rule.apply) colorOn else colorGrayed)
        }

        // Mobile settings
        holder.cbOther.setEnabled(rule.apply)
        holder.cbOther.setAlpha(if (otherActive) 1 else 0.5f)
        holder.cbOther.setOnCheckedChangeListener(null)
        holder.cbOther.setChecked(rule.other_blocked)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap((CompoundButtonCompat.getButtonDrawable(holder.cbOther))!!)
            DrawableCompat.setTint(wrap, if (rule.apply) (if (rule.other_blocked) colorOff else colorOn) else colorGrayed)
        }
        holder.cbOther.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(compoundButton: CompoundButton, isChecked: Boolean) {
                rule.other_blocked = isChecked
                updateRule(context, rule, true, listAll)
            }
        })
        holder.ivScreenOther.setEnabled(rule.apply)
        holder.ivScreenOther.setAlpha(if (otherActive) 1 else 0.5f)
        holder.ivScreenOther.setVisibility(if (rule.screen_other && rule.other_blocked) View.VISIBLE else View.INVISIBLE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivScreenOther.getDrawable())
            DrawableCompat.setTint(wrap, if (rule.apply) colorOn else colorGrayed)
        }
        holder.tvRoaming.setTextColor(if (rule.apply) colorOff else colorGrayed)
        holder.tvRoaming.setAlpha(if (otherActive) 1 else 0.5f)
        holder.tvRoaming.setVisibility(if (rule.roaming && (!rule.other_blocked || rule.screen_other)) View.VISIBLE else View.INVISIBLE)
        holder.tvRemarkMessaging.setVisibility(if (messaging.contains(rule.packageName)) View.VISIBLE else View.GONE)
        holder.tvRemarkDownload.setVisibility(if (download.contains(rule.packageName)) View.VISIBLE else View.GONE)

        // Expanded configuration section
        holder.llConfiguration.setVisibility(if (rule.expanded) View.VISIBLE else View.GONE)

        // Show application details
        holder.tvUid.setText(Integer.toString(rule.uid))
        holder.tvPackage.setText(rule.packageName)
        holder.tvVersion.setText(rule.version)

        // Show application state
        holder.tvInternet.setVisibility(if (rule.internet) View.GONE else View.VISIBLE)
        holder.tvDisabled.setVisibility(if (rule.enabled) View.GONE else View.VISIBLE)

        // Show related
        holder.btnRelated.setVisibility(if (rule.relateduids) View.VISIBLE else View.GONE)
        holder.btnRelated.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(view: View) {
                val main: Intent = Intent(context, ActivityMain::class.java)
                main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, Integer.toString(rule.uid))
                main.putExtra(ActivityMain.Companion.EXTRA_RELATED, true)
                context.startActivity(main)
            }
        })

        // Launch application settings
        if (rule.expanded) {
            val intent: Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData(Uri.parse("package:" + rule.packageName))
            val settings: Intent? = (if (intent.resolveActivity(context.getPackageManager()) == null) null else intent)
            holder.ibSettings.setVisibility(if (settings == null) View.GONE else View.VISIBLE)
            holder.ibSettings.setOnClickListener(object : View.OnClickListener {
                public override fun onClick(view: View) {
                    context.startActivity(settings)
                }
            })
        } else holder.ibSettings.setVisibility(View.GONE)

        // Launch application
        if (rule.expanded) {
            val intent: Intent? = context.getPackageManager().getLaunchIntentForPackage(rule.packageName)
            val launch: Intent? = (if (intent == null ||
                    intent.resolveActivity(context.getPackageManager()) == null) null else intent)
            holder.ibLaunch.setVisibility(if (launch == null) View.GONE else View.VISIBLE)
            holder.ibLaunch.setOnClickListener(object : View.OnClickListener {
                public override fun onClick(view: View) {
                    context.startActivity(launch)
                }
            })
        } else holder.ibLaunch.setVisibility(View.GONE)

        // Apply
        holder.cbApply.setEnabled(rule.pkg && filter)
        holder.cbApply.setOnCheckedChangeListener(null)
        holder.cbApply.setChecked(rule.apply)
        holder.cbApply.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(compoundButton: CompoundButton, isChecked: Boolean) {
                rule.apply = isChecked
                updateRule(context, rule, true, listAll)
            }
        })

        // Show Wi-Fi screen on condition
        holder.llScreenWifi.setVisibility(if (screen_on) View.VISIBLE else View.GONE)
        holder.cbScreenWifi.setEnabled(rule.wifi_blocked && rule.apply)
        holder.cbScreenWifi.setOnCheckedChangeListener(null)
        holder.cbScreenWifi.setChecked(rule.screen_wifi)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivWifiLegend.getDrawable())
            DrawableCompat.setTint(wrap, colorOn)
        }
        holder.cbScreenWifi.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                rule.screen_wifi = isChecked
                updateRule(context, rule, true, listAll)
            }
        })

        // Show mobile screen on condition
        holder.llScreenOther.setVisibility(if (screen_on) View.VISIBLE else View.GONE)
        holder.cbScreenOther.setEnabled(rule.other_blocked && rule.apply)
        holder.cbScreenOther.setOnCheckedChangeListener(null)
        holder.cbScreenOther.setChecked(rule.screen_other)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivOtherLegend.getDrawable())
            DrawableCompat.setTint(wrap, colorOn)
        }
        holder.cbScreenOther.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                rule.screen_other = isChecked
                updateRule(context, rule, true, listAll)
            }
        })

        // Show roaming condition
        holder.cbRoaming.setEnabled((!rule.other_blocked || rule.screen_other) && rule.apply)
        holder.cbRoaming.setOnCheckedChangeListener(null)
        holder.cbRoaming.setChecked(rule.roaming)
        holder.cbRoaming.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            @TargetApi(Build.VERSION_CODES.M)
            public override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                rule.roaming = isChecked
                updateRule(context, rule, true, listAll)
            }
        })

        // Show lockdown
        holder.cbLockdown.setEnabled(rule.apply)
        holder.cbLockdown.setOnCheckedChangeListener(null)
        holder.cbLockdown.setChecked(rule.lockdown)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val wrap: Drawable = DrawableCompat.wrap(holder.ivLockdownLegend.getDrawable())
            DrawableCompat.setTint(wrap, colorOn)
        }
        holder.cbLockdown.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            @TargetApi(Build.VERSION_CODES.M)
            public override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                rule.lockdown = isChecked
                updateRule(context, rule, true, listAll)
            }
        })

        // Reset rule
        holder.btnClear.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(view: View) {
                Util.areYouSure(view.getContext(), R.string.msg_clear_rules, object : DoubtListener {
                    public override fun onSure() {
                        holder.cbApply.setChecked(true)
                        holder.cbWifi.setChecked(rule.wifi_default)
                        holder.cbOther.setChecked(rule.other_default)
                        holder.cbScreenWifi.setChecked(rule.screen_wifi_default)
                        holder.cbScreenOther.setChecked(rule.screen_other_default)
                        holder.cbRoaming.setChecked(rule.roaming_default)
                        holder.cbLockdown.setChecked(false)
                    }
                })
            }
        })
        holder.llFilter.setVisibility(if (Util.canFilter(context)) View.VISIBLE else View.GONE)

        // Live
        holder.ivLive.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(view: View) {
                isLive = !isLive
                val tv: TypedValue = TypedValue()
                view.getContext().getTheme().resolveAttribute(if (isLive) R.attr.iconPause else R.attr.iconPlay, tv, true)
                holder.ivLive.setImageResource(tv.resourceId)
                if (isLive) notifyDataSetChanged()
            }
        })

        // Show logging/filtering is disabled
        holder.tvLogging.setText(if (log_app && filter) R.string.title_logging_enabled else R.string.title_logging_disabled)
        holder.btnLogging.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(v: View) {
                val inflater: LayoutInflater = LayoutInflater.from(context)
                val view: View = inflater.inflate(R.layout.enable, null, false)
                val cbLogging: CheckBox = view.findViewById(R.id.cbLogging)
                val cbFiltering: CheckBox = view.findViewById(R.id.cbFiltering)
                val cbNotify: CheckBox = view.findViewById(R.id.cbNotify)
                val tvFilter4: TextView = view.findViewById(R.id.tvFilter4)
                cbLogging.setChecked(log_app)
                cbFiltering.setChecked(filter)
                cbFiltering.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                tvFilter4.setVisibility(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) View.GONE else View.VISIBLE)
                cbNotify.setChecked(notify_access)
                cbNotify.setEnabled(log_app)
                cbLogging.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                    public override fun onCheckedChanged(compoundButton: CompoundButton, checked: Boolean) {
                        prefs.edit().putBoolean("log_app", checked).apply()
                        cbNotify.setEnabled(checked)
                        if (!checked) {
                            cbNotify.setChecked(false)
                            prefs.edit().putBoolean("notify_access", false).apply()
                            ServiceSinkhole.Companion.reload("changed notify", context, false)
                        }
                        notifyDataSetChanged()
                    }
                })
                cbFiltering.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                    public override fun onCheckedChanged(compoundButton: CompoundButton, checked: Boolean) {
                        if (checked) cbLogging.setChecked(true)
                        prefs.edit().putBoolean("filter", checked).apply()
                        ServiceSinkhole.Companion.reload("changed filter", context, false)
                        notifyDataSetChanged()
                    }
                })
                cbNotify.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                    public override fun onCheckedChanged(compoundButton: CompoundButton, checked: Boolean) {
                        prefs.edit().putBoolean("notify_access", checked).apply()
                        ServiceSinkhole.Companion.reload("changed notify", context, false)
                        notifyDataSetChanged()
                    }
                })
                val dialog: AlertDialog = AlertDialog.Builder(context)
                        .setView(view)
                        .setCancelable(true)
                        .create()
                dialog.show()
            }
        })

        // Show access rules
        if (rule.expanded) {
            // Access the database when expanded only
            val badapter: AdapterAccess = AdapterAccess(context,
                    DatabaseHelper.Companion.getInstance(context)!!.getAccess(rule.uid))
            holder.lvAccess.setOnItemClickListener(object : OnItemClickListener {
                public override fun onItemClick(parent: AdapterView<*>?, view: View, bposition: Int, bid: Long) {
                    val pm: PackageManager = context.getPackageManager()
                    val cursor: Cursor = badapter.getItem(bposition) as Cursor
                    val id: Long = cursor.getLong(cursor.getColumnIndex("ID"))
                    val version: Int = cursor.getInt(cursor.getColumnIndex("version"))
                    val protocol: Int = cursor.getInt(cursor.getColumnIndex("protocol"))
                    val daddr: String = cursor.getString(cursor.getColumnIndex("daddr"))
                    val dport: Int = cursor.getInt(cursor.getColumnIndex("dport"))
                    val time: Long = cursor.getLong(cursor.getColumnIndex("time"))
                    val block: Int = cursor.getInt(cursor.getColumnIndex("block"))
                    val popup: PopupMenu = PopupMenu(context, anchor)
                    popup.inflate(R.menu.access)
                    popup.getMenu().findItem(R.id.menu_host).setTitle(
                            (Util.getProtocolName(protocol, version, false) + " " +
                                    daddr + (if (dport > 0) "/" + dport else "")))
                    val sub: SubMenu = popup.getMenu().findItem(R.id.menu_host).getSubMenu()
                    var multiple: Boolean = false
                    var alt: Cursor? = null
                    try {
                        alt = DatabaseHelper.Companion.getInstance(context)!!.getAlternateQNames(daddr)
                        while (alt.moveToNext()) {
                            multiple = true
                            sub.add(Menu.NONE, Menu.NONE, 0, alt.getString(0)).setEnabled(false)
                        }
                    } finally {
                        if (alt != null) alt.close()
                    }
                    popup.getMenu().findItem(R.id.menu_host).setEnabled(multiple)
                    markPro(context, popup.getMenu().findItem(R.id.menu_allow), ActivityPro.Companion.SKU_FILTER)
                    markPro(context, popup.getMenu().findItem(R.id.menu_block), ActivityPro.Companion.SKU_FILTER)

                    // Whois
                    val lookupIP: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dnslytics.com/whois-lookup/" + daddr))
                    if (pm.resolveActivity(lookupIP, 0) == null) popup.getMenu().removeItem(R.id.menu_whois) else popup.getMenu().findItem(R.id.menu_whois).setTitle(context.getString(R.string.title_log_whois, daddr))

                    // Lookup port
                    val lookupPort: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedguide.net/port.php?port=" + dport))
                    if (dport <= 0 || pm.resolveActivity(lookupPort, 0) == null) popup.getMenu().removeItem(R.id.menu_port) else popup.getMenu().findItem(R.id.menu_port).setTitle(context.getString(R.string.title_log_port, dport))
                    popup.getMenu().findItem(R.id.menu_time).setTitle(
                            SimpleDateFormat.getDateTimeInstance().format(time))
                    popup.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
                        public override fun onMenuItemClick(menuItem: MenuItem): Boolean {
                            val menu: Int = menuItem.getItemId()
                            var result: Boolean = false
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
                                    if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, context)) {
                                        DatabaseHelper.Companion.getInstance(context)!!.setAccess(id, 0)
                                        ServiceSinkhole.Companion.reload("allow host", context, false)
                                    } else context.startActivity(Intent(context, ActivityPro::class.java))
                                    result = true
                                }
                                R.id.menu_block -> {
                                    if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, context)) {
                                        DatabaseHelper.Companion.getInstance(context)!!.setAccess(id, 1)
                                        ServiceSinkhole.Companion.reload("block host", context, false)
                                    } else context.startActivity(Intent(context, ActivityPro::class.java))
                                    result = true
                                }
                                R.id.menu_reset -> {
                                    DatabaseHelper.Companion.getInstance(context)!!.setAccess(id, -1)
                                    ServiceSinkhole.Companion.reload("reset host", context, false)
                                    result = true
                                }
                                R.id.menu_copy -> {
                                    val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip: ClipData = ClipData.newPlainText("netguard", daddr)
                                    clipboard.setPrimaryClip(clip)
                                    return true
                                }
                            }
                            if ((menu == R.id.menu_allow) || (menu == R.id.menu_block) || (menu == R.id.menu_reset)) object : AsyncTask<Any?, Any?, Long>() {
                                protected override fun doInBackground(vararg objects: Any): Long {
                                    return DatabaseHelper.Companion.getInstance(context)!!.getHostCount(rule.uid, false)
                                }

                                override fun onPostExecute(hosts: Long) {
                                    rule.hosts = hosts
                                    notifyDataSetChanged()
                                }
                            }.execute()
                            return result
                        }
                    })
                    if (block == 0) popup.getMenu().removeItem(R.id.menu_allow) else if (block == 1) popup.getMenu().removeItem(R.id.menu_block)
                    popup.show()
                }
            })
            holder.lvAccess.setAdapter(badapter)
        } else {
            holder.lvAccess.setAdapter(null)
            holder.lvAccess.setOnItemClickListener(null)
        }

        // Clear access log
        holder.btnClearAccess.setOnClickListener(object : View.OnClickListener {
            public override fun onClick(view: View) {
                Util.areYouSure(view.getContext(), R.string.msg_reset_access, object : DoubtListener {
                    public override fun onSure() {
                        DatabaseHelper.Companion.getInstance(context)!!.clearAccess(rule.uid, true)
                        if (!isLive) notifyDataSetChanged()
                        if (rv != null) rv!!.scrollToPosition(holder.getAdapterPosition())
                    }
                })
            }
        })

        // Notify on access
        holder.cbNotify.setEnabled(prefs.getBoolean("notify_access", false) && rule.apply)
        holder.cbNotify.setOnCheckedChangeListener(null)
        holder.cbNotify.setChecked(rule.notify)
        holder.cbNotify.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            public override fun onCheckedChanged(compoundButton: CompoundButton, isChecked: Boolean) {
                rule.notify = isChecked
                updateRule(context, rule, true, listAll)
            }
        })
    }

    public override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        //Context context = holder.itemView.getContext();
        //GlideApp.with(context).clear(holder.ivIcon);
        val adapter: CursorAdapter? = holder.lvAccess.getAdapter() as CursorAdapter?
        if (adapter != null) {
            Log.i(TAG, "Closing access cursor")
            adapter.changeCursor(null)
            holder.lvAccess.setAdapter(null)
        }
    }

    private fun markPro(context: Context, menu: MenuItem, sku: String?) {
        if (sku == null || !IAB.Companion.isPurchased(sku, context)) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val dark: Boolean = prefs.getBoolean("dark_theme", false)
            val ssb: SpannableStringBuilder = SpannableStringBuilder("  " + menu.getTitle())
            ssb.setSpan(ImageSpan(context, if (dark) R.drawable.ic_shopping_cart_white_24dp else R.drawable.ic_shopping_cart_black_24dp), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            menu.setTitle(ssb)
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
        Log.i(TAG, "Updated " + rule)
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
        val listSearch: MutableList<Rule?> = (if (root) ArrayList(listAll) else listAll)
        listSearch.remove(rule)
        for (modified: Rule? in listModified) listSearch.remove(modified)
        for (modified: Rule? in listModified) updateRule(context, modified, false, listSearch)
        if (root) {
            notifyDataSetChanged()
            NotificationManagerCompat.from(context).cancel(rule.uid)
            ServiceSinkhole.Companion.reload("rule changed", context, false)
        }
    }

    public override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(query: CharSequence): FilterResults {
                var query: CharSequence? = query
                val listResult: MutableList<Rule?> = ArrayList()
                if (query == null) listResult.addAll(listAll) else {
                    query = query.toString().toLowerCase().trim({ it <= ' ' })
                    var uid: Int
                    try {
                        uid = query.toString().toInt()
                    } catch (ignore: NumberFormatException) {
                        uid = -1
                    }
                    for (rule: Rule? in listAll) if (((rule!!.uid == uid) ||
                                    rule.packageName.toLowerCase().contains(query) ||
                                    (rule.name != null && rule.name!!.toLowerCase().contains(query)))) listResult.add(rule)
                }
                val result: FilterResults = FilterResults()
                result.values = listResult
                result.count = listResult.size
                return result
            }

            override fun publishResults(query: CharSequence, result: FilterResults) {
                listFiltered.clear()
                if (result == null) listFiltered.addAll(listAll) else {
                    listFiltered.addAll((result.values as List<Rule?>?)!!)
                    if (listFiltered.size == 1) listFiltered.get(0)!!.expanded = true
                }
                notifyDataSetChanged()
            }
        }
    }

    public override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(inflater.inflate(R.layout.rule, parent, false))
    }

    public override fun getItemId(position: Int): Long {
        val rule: Rule? = listFiltered.get(position)
        return rule!!.packageName.hashCode() * 100000L + rule.uid
    }

    public override fun getItemCount(): Int {
        return listFiltered.size
    }

    companion object {
        private val TAG: String = "NetGuard.Adapter"
    }

    init {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        this.anchor = anchor
        inflater = LayoutInflater.from(context)
        if (prefs.getBoolean("dark_theme", false)) colorChanged = Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY), Color.blue(Color.DKGRAY)) else colorChanged = Color.argb(128, Color.red(Color.LTGRAY), Color.green(Color.LTGRAY), Color.blue(Color.LTGRAY))
        val ta: TypedArray = context.getTheme().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
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
        colorGrayed = ContextCompat.getColor(context, R.color.colorGrayed)
        val typedValue: TypedValue = TypedValue()
        context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true)
        val height: Int = TypedValue.complexToDimensionPixelSize(typedValue.data, context.getResources().getDisplayMetrics())
        iconSize = Math.round(height * context.getResources().getDisplayMetrics().density + 0.5f)
        setHasStableIds(true)
    }
}