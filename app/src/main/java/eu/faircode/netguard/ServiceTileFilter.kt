package eu.faircode.netguardimport

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import eu.faircode.netguard.*

android.content.ServiceConnection
import android.content.Intent
import android.content.ComponentName
import android.os.IBinder
import kotlin.Throws
import org.json.JSONException
import android.os.Bundle
import org.json.JSONObject
import android.content.SharedPreferences
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
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
import eu.faircode.netguard.IPUtil.CIDR
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import android.view.MenuInflater
import android.widget.Toast
import android.util.Xml
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import androidx.appcompat.widget.SwitchCompat
import android.widget.CompoundButton
import android.widget.FilterQueryProvider
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView
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
import android.view.SubMenu
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
import android.os.PowerManager.WakeLock
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.NotificationChannel
import android.database.sqlite.SQLiteOpenHelper
import eu.faircode.netguard.DatabaseHelper.ForwardChangedListener
import android.os.HandlerThread
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
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
import android.net.TrafficStats
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.text.Spannable
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import kotlin.jvm.Synchronized
import android.service.quicksettings.TileService
import android.net.wifi.WifiConfiguration
import androidx.core.util.PatternsCompat
import android.content.ContentResolver
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
*/@TargetApi(Build.VERSION_CODES.N)
class ServiceTileFilter constructor() : TileService(), OnSharedPreferenceChangeListener {
    public override fun onStartListening() {
        Log.i(ServiceTileFilter.Companion.TAG, "Start listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        if (("filter" == key)) update()
    }

    private fun update() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val filter: Boolean = prefs.getBoolean("filter", false)
        val tile: Tile? = getQsTile()
        if (tile != null) {
            tile.setState(if (filter) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
            tile.setIcon(Icon.createWithResource(this, if (filter) R.drawable.ic_filter_list_white_24dp else R.drawable.ic_filter_list_white_24dp_60))
            tile.updateTile()
        }
    }

    public override fun onStopListening() {
        Log.i(ServiceTileFilter.Companion.TAG, "Stop listening")
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    public override fun onClick() {
        Log.i(ServiceTileFilter.Companion.TAG, "Click")
        if (Util.canFilter(this)) {
            if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, this)) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                prefs.edit().putBoolean("filter", !prefs.getBoolean("filter", false)).apply()
                ServiceSinkhole.Companion.reload("tile", this, false)
            } else Toast.makeText(this, R.string.title_pro_feature, Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, R.string.msg_unavailable, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val TAG: String = "NetGuard.TileFilter"
    }
}