package eu.faircode.netguardimport

import android.content.Context
import android.database.Cursor
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.TextView
import eu.faircode.netguard.R
import eu.faircode.netguard.Util

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
import android.app.ApplicationErrorReport
import android.app.ApplicationErrorReport.CrashInfo
import android.text.TextUtils
import android.os.AsyncTask
import android.net.VpnService
import eu.faircode.netguard.IPUtil.CIDR
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import androidx.appcompat.app.AppCompatActivity
import android.util.Xml
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import eu.faircode.netguard.DatabaseHelper.LogChangedListener
import androidx.appcompat.widget.SwitchCompat
import android.widget.AdapterView.OnItemClickListener
import androidx.core.app.NavUtils
import android.text.TextWatcher
import android.text.Editable
import androidx.recyclerview.widget.RecyclerView
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
import android.content.res.AssetFileDescriptor
import eu.faircode.netguard.ActivitySettings.XmlImportHandler
import android.view.View.MeasureSpec
import kotlin.jvm.JvmOverloads

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
*/   class AdapterForwarding constructor(context: Context?, cursor: Cursor) : CursorAdapter(context, cursor, 0) {
    private val colProtocol: Int
    private val colDPort: Int
    private val colRAddr: Int
    private val colRPort: Int
    private val colRUid: Int
    public override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.forward, parent, false)
    }

    public override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val protocol: Int = cursor.getInt(colProtocol)
        val dport: Int = cursor.getInt(colDPort)
        val raddr: String = cursor.getString(colRAddr)
        val rport: Int = cursor.getInt(colRPort)
        val ruid: Int = cursor.getInt(colRUid)

        // Get views
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvDPort: TextView = view.findViewById(R.id.tvDPort)
        val tvRAddr: TextView = view.findViewById(R.id.tvRAddr)
        val tvRPort: TextView = view.findViewById(R.id.tvRPort)
        val tvRUid: TextView = view.findViewById(R.id.tvRUid)
        tvProtocol.setText(Util.getProtocolName(protocol, 0, false))
        tvDPort.setText(Integer.toString(dport))
        tvRAddr.setText(raddr)
        tvRPort.setText(Integer.toString(rport))
        tvRUid.setText(TextUtils.join(", ", Util.getApplicationNames(ruid, context)))
    }

    init {
        colProtocol = cursor.getColumnIndex("protocol")
        colDPort = cursor.getColumnIndex("dport")
        colRAddr = cursor.getColumnIndex("raddr")
        colRPort = cursor.getColumnIndex("rport")
        colRUid = cursor.getColumnIndex("ruid")
    }
}