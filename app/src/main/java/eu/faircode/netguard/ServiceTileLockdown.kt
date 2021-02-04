package eu.faircode.netguardimport

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import eu.faircode.netguard.R
import eu.faircode.netguard.ServiceSinkhole
import eu.faircode.netguard.ServiceTileLockdown
import eu.faircode.netguard.WidgetLockdown

android.content.ServiceConnection
import eu.faircode.netguard.IAB
import android.content.Intent
import android.content.ComponentName
import android.os.IBinder
import kotlin.Throws
import org.json.JSONException
import android.os.Bundle
import org.json.JSONObject
import android.content.SharedPreferences
import eu.faircode.netguard.ActivityPro
import android.app.PendingIntent
import eu.faircode.netguard.DatabaseHelper
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import eu.faircode.netguard.R
import org.xmlpull.v1.XmlPullParser
import android.os.Build
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Network
import androidx.core.net.ConnectivityManagerCompat
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.net.LinkProperties
import android.os.PowerManager
import android.app.Activity
import android.annotation.TargetApi
import android.util.TypedValue
import android.app.ActivityManager.TaskDescription
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import eu.faircode.netguard.Util.DoubtListener
import android.view.LayoutInflater
import android.widget.TextView
import android.content.DialogInterface
import android.app.ApplicationErrorReport
import android.app.ApplicationErrorReport.CrashInfo
import android.text.TextUtils
import android.os.AsyncTask
import android.net.VpnService
import eu.faircode.netguard.ServiceSinkhole
import eu.faircode.netguard.Usage
import eu.faircode.netguard.IPUtil.CIDR
import eu.faircode.netguard.IPUtil
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import eu.faircode.netguard.AdapterLog
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import eu.faircode.netguard.WidgetMain
import eu.faircode.netguard.WidgetAdmin
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import eu.faircode.netguard.AdapterDns
import android.view.MenuInflater
import eu.faircode.netguard.ActivityDns
import android.widget.Toast
import android.util.Xml
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import androidx.appcompat.widget.SwitchCompat
import android.widget.CompoundButton
import android.widget.FilterQueryProvider
import eu.faircode.netguard.ActivityLog
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView
import eu.faircode.netguard.ActivityMain
import android.content.ClipData
import androidx.core.app.NavUtils
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.content.ClipDescription
import androidx.recyclerview.widget.RecyclerView
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.CheckBox
import android.view.TouchDelegate
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.DecodeFormat
import androidx.core.widget.CompoundButtonCompat
import eu.faircode.netguard.AdapterAccess
import android.view.SubMenu
import eu.faircode.netguard.AdapterRule
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.text.Spanned
import androidx.core.app.NotificationManagerCompat
import android.widget.Filter.FilterResults
import android.content.res.TypedArray
import androidx.core.content.ContextCompat
import com.bumptech.glide.module.AppGlideModule
import eu.faircode.netguard.ReceiverAutostart
import android.app.AlarmManager
import android.os.Vibrator
import android.os.VibrationEffect
import eu.faircode.netguard.WidgetLockdown
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.View.OnLongClickListener
import android.view.Gravity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import android.text.method.LinkMovementMethod
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.text.SpannableString
import android.text.style.UnderlineSpan
import eu.faircode.netguard.DatabaseHelper.AccessChangedListener
import android.content.BroadcastReceiver
import eu.faircode.netguard.ActivitySettings
import android.os.PowerManager.WakeLock
import eu.faircode.netguard.DownloadTask
import androidx.core.app.NotificationCompat
import eu.faircode.netguard.ParseNetwork
import eu.faircode.netguard.ApplicationEx
import android.app.NotificationManager
import android.app.NotificationChannel
import android.database.sqlite.SQLiteOpenHelper
import eu.faircode.netguard.DatabaseHelper.ForwardChangedListener
import android.os.HandlerThread
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import android.database.sqlite.SQLiteDoneException
import android.app.IntentService
import eu.faircode.netguard.ServiceExternal
import android.telephony.PhoneStateListener
import android.os.ParcelFileDescriptor
import eu.faircode.netguard.ServiceSinkhole.IPKey
import eu.faircode.netguard.ServiceSinkhole.IPRule
import eu.faircode.netguard.Forward
import kotlin.jvm.Volatile
import android.os.Looper
import eu.faircode.netguard.ServiceSinkhole.StatsHandler
import eu.faircode.netguard.ServiceSinkhole.CommandHandler.StartFailedException
import org.json.JSONArray
import android.net.TrafficStats
import eu.faircode.netguard.Allowed
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.text.Spannable
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import kotlin.jvm.Synchronized
import android.service.quicksettings.TileService
import eu.faircode.netguard.ServiceTileMain
import eu.faircode.netguard.FragmentSettings
import android.net.wifi.WifiConfiguration
import eu.faircode.netguard.ActivityForwarding
import androidx.core.util.PatternsCompat
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import eu.faircode.netguard.ActivitySettings.XmlImportHandler
import android.view.View.MeasureSpec
import eu.faircode.netguard.ServiceTileGraph
import kotlin.jvm.JvmOverloads
import eu.faircode.netguard.ServiceTileFilter
import eu.faircode.netguard.AdapterForwarding
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.ArrayAdapter
import eu.faircode.netguard.ServiceTileLockdown
import eu.faircode.netguard.ReceiverPackageRemoved
import eu.faircode.netguard.ActivityForwardApproval

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
*/@TargetApi(Build.VERSION_CODES.N)
class ServiceTileLockdown constructor() : TileService(), OnSharedPreferenceChangeListener {
    public override fun onStartListening() {
        Log.i(ServiceTileLockdown.Companion.TAG, "Start listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        if (("lockdown" == key)) update()
    }

    private fun update() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val lockdown: Boolean = prefs.getBoolean("lockdown", false)
        val tile: Tile? = getQsTile()
        if (tile != null) {
            tile.setState(if (lockdown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
            tile.setIcon(Icon.createWithResource(this, if (lockdown) R.drawable.ic_lock_outline_white_24dp else R.drawable.ic_lock_outline_white_24dp_60))
            tile.updateTile()
        }
    }

    public override fun onStopListening() {
        Log.i(ServiceTileLockdown.Companion.TAG, "Stop listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    public override fun onClick() {
        Log.i(ServiceTileLockdown.Companion.TAG, "Click")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("lockdown", !prefs.getBoolean("lockdown", false)).apply()
        ServiceSinkhole.Companion.reload("tile", this, false)
        WidgetLockdown.Companion.updateWidgets(this)
    }

    companion object {
        private val TAG: String = "NetGuard.TileLockdown"
    }
}