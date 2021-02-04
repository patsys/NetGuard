package eu.faircode.netguardimport

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView

android.content.ServiceConnection
import eu.faircode.netguard.IAB
import android.os.IBinder
import kotlin.Throws
import org.json.JSONException
import android.os.Bundle
import org.json.JSONObject
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
import androidx.appcompat.app.AppCompatActivity
import eu.faircode.netguard.AdapterDns
import android.view.MenuInflater
import eu.faircode.netguard.ActivityDns
import android.util.Xml
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import androidx.appcompat.widget.SwitchCompat
import eu.faircode.netguard.ActivityLog
import android.widget.AdapterView.OnItemClickListener
import eu.faircode.netguard.ActivityMain
import androidx.core.app.NavUtils
import android.view.WindowManager
import android.text.TextWatcher
import android.text.Editable
import androidx.recyclerview.widget.RecyclerView
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.text.SpannableString
import android.text.style.UnderlineSpan
import eu.faircode.netguard.DatabaseHelper.AccessChangedListener
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
import android.content.res.AssetFileDescriptor
import eu.faircode.netguard.ActivitySettings.XmlImportHandler
import android.view.View.MeasureSpec
import eu.faircode.netguard.ServiceTileGraph
import kotlin.jvm.JvmOverloads
import eu.faircode.netguard.ServiceTileFilter
import eu.faircode.netguard.AdapterForwarding
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
*/ // This requires list view items with equal heights
class ExpandedListView : ListView {
    constructor(context: Context?) : super(context) {}
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {}
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 4, MeasureSpec.AT_MOST))
    }
}