package eu.faircode.netguardimport

import android.content.*
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import eu.faircode.netguard.*

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
import android.view.LayoutInflater
import android.widget.TextView
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
import androidx.core.app.NavUtils
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
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
*/   class ReceiverPackageRemoved constructor() : BroadcastReceiver() {
    public override fun onReceive(context: Context, intent: Intent) {
        Log.i(ReceiverPackageRemoved.Companion.TAG, "Received " + intent)
        Util.logExtras(intent)
        val action: String? = (if (intent == null) null else intent.getAction())
        if ((Intent.ACTION_PACKAGE_FULLY_REMOVED == action)) {
            val uid: Int = intent.getIntExtra(Intent.EXTRA_UID, 0)
            if (uid > 0) {
                val dh: DatabaseHelper? = DatabaseHelper.Companion.getInstance(context)
                dh!!.clearLog(uid)
                dh.clearAccess(uid, false)
                NotificationManagerCompat.from(context).cancel(uid) // installed notification
                NotificationManagerCompat.from(context).cancel(uid + 10000) // access notification
            }
        }
    }

    companion object {
        private val TAG: String = "NetGuard.Receiver"
    }
}