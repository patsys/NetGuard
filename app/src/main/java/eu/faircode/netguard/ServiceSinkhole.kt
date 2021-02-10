package eu.faircode.netguard

import android.annotation.TargetApi
import android.app.*
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.net.*
import android.net.ConnectivityManager.NetworkCallback
import android.os.*
import android.os.PowerManager.WakeLock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.Pair
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.faircode.netguard.IPUtil.CIDR
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.net.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.net.ssl.HttpsURLConnection

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
*/   class ServiceSinkhole : VpnService(), OnSharedPreferenceChangeListener {
    private var registeredUser: Boolean = false
    private var registeredIdleState: Boolean = false
    private var registeredConnectivityChanged: Boolean = false
    private var registeredPackageChanged: Boolean = false
    private var phone_state: Boolean = false
    private var networkCallback: Any? = null
    private var registeredInteractiveState: Boolean = false
    private var callStateListener: PhoneStateListener? = null
    private var state: State = State.none
    private var user_foreground: Boolean = true
    private var last_connected: Boolean = false
    private var last_metered: Boolean = true
    private var last_interactive: Boolean = false
    private var last_allowed: Int = -1
    private var last_blocked: Int = -1
    private var last_hosts: Int = -1
    private var tunnelThread: Thread? = null
    private var last_builder: Builder? = null
    private var vpn: ParcelFileDescriptor? = null
    private var temporarilyStopped: Boolean = false
    private var last_hosts_modified: Long = 0
    private val mapHostsBlocked: MutableMap<String, Boolean> = HashMap()
    private val mapUidAllowed: MutableMap<Int, Boolean> = HashMap()
    private val mapUidKnown: MutableMap<Int, Int> = HashMap()
    private val mapUidIPFilters: MutableMap<IPKey, MutableMap<InetAddress?, IPRule?>> = HashMap()
    private val mapForward: MutableMap<Int, Forward> = HashMap()
    private val mapNotify: MutableMap<Int, Boolean> = HashMap()
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock(true)

    @Volatile
    private var commandLooper: Looper? = null

    @Volatile
    private var logLooper: Looper? = null

    @Volatile
    private var statsLooper: Looper? = null

    @Volatile
    private var commandHandler: CommandHandler? = null

    @Volatile
    private var logHandler: LogHandler? = null

    @Volatile
    private var statsHandler: StatsHandler? = null

    private enum class State {
        none, waiting, enforcing, stats
    }

    enum class Command {
        run, start, reload, stop, stats, set, householding, watchdog
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private external fun jni_init(sdk: Int): Long
    private external fun jni_start(context: Long, loglevel: Int)
    private external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jni_stop(context: Long)
    private external fun jni_clear(context: Long)
    private external fun jni_get_mtu(): Int
    private external fun jni_get_stats(context: Long): IntArray
    private external fun jni_socks5(addr: String?, port: Int, username: String?, password: String?)
    private external fun jni_done(context: Long)
    private inner class CommandHandler constructor(looper: Looper?) : Handler((looper)!!) {
        var queue: Int = 0
        private fun reportQueueSize() {
            val ruleset = Intent(ActivityMain.ACTION_QUEUE_CHANGED)
            ruleset.putExtra(ActivityMain.EXTRA_SIZE, queue)
            LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
        }

        fun queue(intent: Intent) {
            synchronized(this) {
                queue++
                reportQueueSize()
            }
            val cmd: Command? = intent.getSerializableExtra(EXTRA_COMMAND) as Command?
            val msg: Message = commandHandler!!.obtainMessage()
            msg.obj = intent
            msg.what = cmd!!.ordinal
            commandHandler!!.sendMessage(msg)
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun handleMessage(msg: Message) {
            try {
                synchronized(this@ServiceSinkhole) { handleIntent(msg.obj as Intent) }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                synchronized(
                        this,
                ) {
                    queue--
                    reportQueueSize()
                }
                try {
                    val wl: WakeLock? = getLock(this@ServiceSinkhole)
                    if (wl!!.isHeld) wl.release() else Log.w(TAG, "Wakelock under-locked")
                    Log.i(TAG, "Messages=" + hasMessages(0) + " wakelock=" + wlInstance!!.isHeld)
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private fun handleIntent(intent: Intent) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val cmd: Command? = intent.getSerializableExtra(EXTRA_COMMAND) as Command?
            val reason: String? = intent.getStringExtra(EXTRA_REASON)
            Log.i(TAG, ("Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000)))

            // Check if foreground
            if (cmd != Command.stop) if (!user_foreground) {
                Log.i(TAG, "Command $cmd ignored for background user")
                return
            }

            // Handle temporary stop
            if (cmd == Command.stop) temporarilyStopped = intent.getBooleanExtra(EXTRA_TEMPORARY, false) else if (cmd == Command.start) temporarilyStopped = false else if (cmd == Command.reload && temporarilyStopped) {
                // Prevent network/interactive changes from restarting the VPN
                Log.i(TAG, "Command $cmd ignored because of temporary stop")
                return
            }

            // Optionally listen for interactive state changes
            if (prefs.getBoolean("screen_on", true)) {
                if (!registeredInteractiveState) {
                    Log.i(TAG, "Starting listening for interactive state changes")
                    last_interactive = Util.isInteractive(this@ServiceSinkhole)
                    val ifInteractive = IntentFilter()
                    ifInteractive.addAction(Intent.ACTION_SCREEN_ON)
                    ifInteractive.addAction(Intent.ACTION_SCREEN_OFF)
                    ifInteractive.addAction(ACTION_SCREEN_OFF_DELAYED)
                    registerReceiver(interactiveStateReceiver, ifInteractive)
                    registeredInteractiveState = true
                }
            } else {
                if (registeredInteractiveState) {
                    Log.i(TAG, "Stopping listening for interactive state changes")
                    unregisterReceiver(interactiveStateReceiver)
                    registeredInteractiveState = false
                    last_interactive = false
                }
            }

            // Optionally listen for call state changes
            val tm: TelephonyManager? = getSystemService(TELEPHONY_SERVICE) as TelephonyManager?
            if (prefs.getBoolean("disable_on_call", false)) {
                if ((tm != null) && (callStateListener == null) && Util.hasPhoneStatePermission(this@ServiceSinkhole)) {
                    Log.i(TAG, "Starting listening for call states")
                    val listener: PhoneStateListener = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, incomingNumber: String) {
                            Log.i(TAG, "New call state=$state")
                            if (prefs.getBoolean("enabled", false)) if (state == TelephonyManager.CALL_STATE_IDLE) start("call state", this@ServiceSinkhole) else stop("call state", this@ServiceSinkhole, true)
                        }
                    }
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    callStateListener = listener
                }
            } else {
                if (tm != null && callStateListener != null) {
                    Log.i(TAG, "Stopping listening for call states")
                    tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE)
                    callStateListener = null
                }
            }

            // Watchdog
            if ((cmd == Command.start) || (cmd == Command.reload) || (cmd == Command.stop)) {
                val watchdogIntent = Intent(this@ServiceSinkhole, ServiceSinkhole::class.java)
                watchdogIntent.action = ACTION_WATCHDOG
                val pi: PendingIntent
                pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT) else PendingIntent.getService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                val am: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                am.cancel(pi)
                if (cmd != Command.stop) {
                    val watchdog: Int = prefs.getString("watchdog", "0")!!.toInt()
                    if (watchdog > 0) {
                        Log.i(TAG, "Watchdog $watchdog minutes")
                        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + watchdog * 60 * 1000, (watchdog * 60 * 1000).toLong(), pi)
                    }
                }
            }
            try {
                when (cmd) {
                    Command.run -> {
                    }
                    Command.start -> start()
                    Command.reload -> reload(intent.getBooleanExtra(EXTRA_INTERACTIVE, false))
                    Command.stop -> stop(temporarilyStopped)
                    Command.stats -> {
                        statsHandler!!.sendEmptyMessage(MSG_STATS_STOP)
                        statsHandler!!.sendEmptyMessage(MSG_STATS_START)
                    }
                    Command.householding -> householding()
                    Command.watchdog -> watchdog()
                    else -> Log.e(TAG, "Unknown command=$cmd")
                }
                if ((cmd == Command.start) || (cmd == Command.reload) || (cmd == Command.stop)) {
                    // Update main view
                    val ruleset = Intent(ActivityMain.ACTION_RULES_CHANGED)
                    ruleset.putExtra(ActivityMain.EXTRA_CONNECTED, if (cmd == Command.stop) false else last_connected)
                    ruleset.putExtra(ActivityMain.EXTRA_METERED, if (cmd == Command.stop) false else last_metered)
                    LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)

                    // Update widgets
                    WidgetMain.updateWidgets(this@ServiceSinkhole)
                }

                // Stop service if needed
                if ((!commandHandler!!.hasMessages(Command.start.ordinal) &&
                                !commandHandler!!.hasMessages(Command.reload.ordinal) &&
                                !prefs.getBoolean("enabled", false) &&
                                !prefs.getBoolean("show_stats", false))) stopForeground(true)

                // Request garbage collection
                System.gc()
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                if (cmd == Command.start || cmd == Command.reload) {
                    if (prepare(this@ServiceSinkhole) == null) {
                        Log.w(TAG, "VPN prepared connected=$last_connected")
                        if (last_connected && ex !is StartFailedException) {
                            //showAutoStartNotification();
                            if (!Util.isPlayStoreInstall(this@ServiceSinkhole)) showErrorNotification(ex.toString())
                        }
                        // Retried on connectivity change
                    } else {
                        showErrorNotification(ex.toString())

                        // Disable firewall
                        if (ex !is StartFailedException) {
                            prefs.edit().putBoolean("enabled", false).apply()
                            WidgetMain.updateWidgets(this@ServiceSinkhole)
                        }
                    }
                } else showErrorNotification(ex.toString())
            }
        }

        @RequiresApi(Build.VERSION_CODES.N)
        private fun start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=$state")
                val listRule: List<Rule?> = Rule.getRules(true, this@ServiceSinkhole)
                val listAllowed: List<Rule?> = getAllowedRules(listRule)
                last_builder = getBuilder(listAllowed, listRule)
                vpn = startVPN(last_builder)
                if (vpn == null) throw StartFailedException(getString((R.string.msg_start_failed)))
                startNative(vpn!!, listAllowed, listRule)
                removeWarningNotifications()
                updateEnforcingNotification(listAllowed.size, listRule.size)
            }
        }

        private fun reload(interactive: Boolean) {
            val listRule: List<Rule?> = Rule.getRules(true, this@ServiceSinkhole)

            // Check if rules needs to be reloaded
            if (interactive) {
                var process = false
                for (rule: Rule? in listRule) {
                    val blocked: Boolean = (if (last_metered) rule!!.other_blocked else rule!!.wifi_blocked)
                    val screen: Boolean = (if (last_metered) rule.screen_other else rule.screen_wifi)
                    if (blocked && screen) {
                        process = true
                        break
                    }
                }
                if (!process) {
                    Log.i(TAG, "No changed rules on interactive state change")
                    return
                }
            }
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            if (state != State.enforcing) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=$state")
            }
            val listAllowed: List<Rule?> = getAllowedRules(listRule)
            val builder: Builder = getBuilder(listAllowed, listRule)
            if ((vpn != null) && prefs.getBoolean("filter", false) && (builder == last_builder)) {
                Log.i(TAG, "Native restart")
                stopNative()
            } else {
                last_builder = builder
                var handover: Boolean = prefs.getBoolean("handover", false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handover = false
                Log.i(TAG, "VPN restart handover=$handover")
                if (handover) {
                    // Attempt seamless handover
                    var prev: ParcelFileDescriptor? = vpn
                    vpn = startVPN(builder)
                    if (prev != null && vpn == null) {
                        Log.w(TAG, "Handover failed")
                        stopNative()
                        stopVPN(prev)
                        prev = null
                        try {
                            Thread.sleep(3000)
                        } catch (ignored: InterruptedException) {
                        }
                        vpn = startVPN(last_builder)
                        if (vpn == null) throw IllegalStateException("Handover failed")
                    }
                    if (prev != null) {
                        stopNative()
                        stopVPN(prev)
                    }
                } else {
                    if (vpn != null) {
                        stopNative()
                        stopVPN(vpn!!)
                    }
                    vpn = startVPN(builder)
                }
            }
            if (vpn == null) throw StartFailedException(getString((R.string.msg_start_failed)))
            startNative(vpn!!, listAllowed, listRule)
            removeWarningNotifications()
            updateEnforcingNotification(listAllowed.size, listRule.size)
        }

        private fun stop(temporary: Boolean) {
            if (vpn != null) {
                stopNative()
                stopVPN(vpn!!)
                vpn = null
                unprepare()
            }
            if (state == State.enforcing && !temporary) {
                Log.d(TAG, "Stop foreground state=$state")
                last_allowed = -1
                last_blocked = -1
                last_hosts = -1
                stopForeground(true)
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, waitingNotification)
                    state = State.waiting
                    Log.d(TAG, "Start foreground state=$state")
                } else {
                    state = State.none
                    stopSelf()
                }
            }
        }

        private fun householding() {
            // Keep log records for three days
            DatabaseHelper.getInstance(this@ServiceSinkhole).cleanupLog(Date().time - 3 * 24 * 3600 * 1000L)

            // Clear expired DNS records
            DatabaseHelper.getInstance(this@ServiceSinkhole).cleanupDns()

            // Check for update
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            if ((!Util.isPlayStoreInstall(this@ServiceSinkhole) &&
                            Util.hasValidFingerprint(this@ServiceSinkhole) &&
                            prefs.getBoolean("update_check", true))) checkUpdate()
        }

        private fun watchdog() {
            if (vpn == null) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("enabled", false)) {
                    Log.e(TAG, "Service was killed")
                    start()
                }
            }
        }

        private fun checkUpdate() {
            val json: StringBuilder = StringBuilder()
            var urlConnection: HttpsURLConnection? = null
            try {
                val url = URL(BuildConfig.GITHUB_LATEST_API)
                urlConnection = url.openConnection() as HttpsURLConnection?
                val br = BufferedReader(InputStreamReader(urlConnection!!.inputStream))
                var line: String?
                while ((br.readLine().also { line = it }) != null) json.append(line)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                urlConnection?.disconnect()
            }
            try {
                val jroot = JSONObject(json.toString())
                if (jroot.has("tag_name") && jroot.has("html_url") && jroot.has("assets")) {
                    val url: String = jroot.getString("html_url")
                    val jassets: JSONArray = jroot.getJSONArray("assets")
                    if (jassets.length() > 0) {
                        val jasset: JSONObject = jassets.getJSONObject(0)
                        if (jasset.has("name")) {
                            val version: String = jroot.getString("tag_name")
                            val name: String = jasset.getString("name")
                            Log.i(TAG, "Tag $version name $name url $url")
                            val current = Version(Util.getSelfVersionName(this@ServiceSinkhole))
                            val available = Version(version)
                            if (current < available) {
                                Log.i(TAG, "Update available from $current to $available")
                                showUpdateNotification(name, url)
                            } else Log.i(TAG, "Up-to-date current version $current")
                        }
                    }
                }
            } catch (ex: JSONException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private inner class StartFailedException constructor(msg: String?) : IllegalStateException(msg)
    }

    private inner class LogHandler constructor(looper: Looper?) : Handler((looper)!!) {
        var queue: Int = 0
        fun queue(packet: Packet?) {
            val msg: Message = obtainMessage()
            msg.obj = packet
            msg.what = MSG_PACKET
            msg.arg1 = (if (last_connected) (if (last_metered) 2 else 1) else 0)
            msg.arg2 = (if (last_interactive) 1 else 0)
            synchronized(this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            }
        }

        fun account(usage: Usage?) {
            val msg: Message = obtainMessage()
            msg.obj = usage
            msg.what = MSG_USAGE
            synchronized(this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            }
        }

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_PACKET -> log(msg.obj as Packet, msg.arg1, msg.arg2 > 0)
                    MSG_USAGE -> usage(msg.obj as Usage)
                    else -> Log.e(TAG, "Unknown log message=" + msg.what)
                }
                synchronized(this) { queue-- }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private fun log(packet: Packet, connection: Int, interactive: Boolean) {
            // Get settings
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val log: Boolean = prefs.getBoolean("log", false)
            val log_app: Boolean = prefs.getBoolean("log_app", false)
            val dh: DatabaseHelper = DatabaseHelper.getInstance(this@ServiceSinkhole)

            // Get real name
            val dname: String? = dh.getQName(packet.daddr)

            // Traffic log
            if (log) dh.insertLog(packet, dname, connection, interactive)

            // Application log
            if ((log_app && (packet.uid >= 0) &&
                            !((packet.uid == 0) && (packet.protocol == 6 || packet.protocol == 17) && (packet.dport == 53)))) {
                if (!(packet.protocol == 6 /* TCP */ || packet.protocol == 17 /* UDP */)) packet.dport = 0
                if (dh.updateAccess(packet, dname, -1)) {
                    lock.readLock().lock()
                    if (!mapNotify.containsKey(packet.uid) || (mapNotify[packet.uid])!!) showAccessNotification(packet.uid)
                    lock.readLock().unlock()
                }
            }
        }

        private fun usage(usage: Usage) {
            if (usage.Uid >= 0 && !((usage.Uid == 0) && (usage.Protocol == 17) && (usage.DPort == 53))) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                val filter: Boolean = prefs.getBoolean("filter", false)
                val log_app: Boolean = prefs.getBoolean("log_app", false)
                val track_usage: Boolean = prefs.getBoolean("track_usage", false)
                if (filter && log_app && track_usage) {
                    val dh: DatabaseHelper = DatabaseHelper.getInstance(this@ServiceSinkhole)
                    val dname: String? = dh.getQName(usage.DAddr)
                    Log.i(TAG, "Usage account $usage dname=$dname")
                    dh.updateUsage(usage, dname)
                }
            }
        }

    }

    private inner class StatsHandler constructor(looper: Looper?) : Handler((looper)!!) {
        private var stats: Boolean = false
        private var `when`: Long = 0
        private var t: Long = -1
        private var tx: Long = -1
        private var rx: Long = -1
        private val gt: MutableList<Long> = ArrayList()
        private val gtx: MutableList<Float> = ArrayList()
        private val grx: MutableList<Float> = ArrayList()
        private val mapUidBytes: HashMap<Int, Long> = HashMap()
        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_STATS_START -> startStats()
                    MSG_STATS_STOP -> stopStats()
                    MSG_STATS_UPDATE -> updateStats()
                    else -> Log.e(TAG, "Unknown stats message=" + msg.what)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private fun startStats() {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val enabled: Boolean = (!stats && prefs.getBoolean("show_stats", false))
            Log.i(TAG, "Stats start enabled=$enabled")
            if (enabled) {
                `when` = Date().time
                t = -1
                tx = -1
                rx = -1
                gt.clear()
                gtx.clear()
                grx.clear()
                mapUidBytes.clear()
                stats = true
                updateStats()
            }
        }

        private fun stopStats() {
            Log.i(TAG, "Stats stop")
            stats = false
            this.removeMessages(MSG_STATS_UPDATE)
            if (state == State.stats) {
                Log.d(TAG, "Stop foreground state=$state")
                stopForeground(true)
                state = State.none
            } else NotificationManagerCompat.from(this@ServiceSinkhole).cancel(NOTIFY_TRAFFIC)
        }

        private fun updateStats() {
            val remoteViews = RemoteViews(packageName, R.layout.traffic)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val frequency: Long = prefs.getString("stats_frequency", "1000")!!.toLong()
            val samples: Long = prefs.getString("stats_samples", "90")!!.toLong()
            val filter: Boolean = prefs.getBoolean("filter", false)
            val show_top: Boolean = prefs.getBoolean("show_top", false)
            val loglevel: Int = prefs.getString("loglevel", Log.WARN.toString())!!.toInt()

            // Schedule next update
            sendEmptyMessageDelayed(MSG_STATS_UPDATE, frequency)
            val ct: Long = SystemClock.elapsedRealtime()

            // Cleanup
            while (gt.size > 0 && ct - gt[0] > samples * 1000) {
                gt.removeAt(0)
                gtx.removeAt(0)
                grx.removeAt(0)
            }

            // Calculate network speed
            var txsec = 0f
            var rxsec = 0f
            var ttx: Long = TrafficStats.getTotalTxBytes()
            var trx: Long = TrafficStats.getTotalRxBytes()
            if (filter) {
                ttx -= TrafficStats.getUidTxBytes(Process.myUid())
                trx -= TrafficStats.getUidRxBytes(Process.myUid())
                if (ttx < 0) ttx = 0
                if (trx < 0) trx = 0
            }
            if ((t > 0) && (tx > 0) && (rx > 0)) {
                val dt: Float = (ct - t) / 1000f
                txsec = (ttx - tx) / dt
                rxsec = (trx - rx) / dt
                gt.add(ct)
                gtx.add(txsec)
                grx.add(rxsec)
            }

            // Calculate application speeds
            if (show_top) {
                if (mapUidBytes.size == 0) {
                    for (ainfo: ApplicationInfo in packageManager.getInstalledApplications(0)) if (ainfo.uid != Process.myUid()) mapUidBytes[ainfo.uid] = TrafficStats.getUidTxBytes(ainfo.uid) + TrafficStats.getUidRxBytes(ainfo.uid)
                } else if (t > 0) {
                    val mapSpeedUid: TreeMap<Float, Int> = TreeMap { value, other -> -value.compareTo(other) }
                    val dt: Float = (ct - t) / 1000f
                    for (uid: Int in mapUidBytes.keys) {
                        val bytes: Long = TrafficStats.getUidTxBytes(uid) + TrafficStats.getUidRxBytes(uid)
                        val speed: Float = (bytes - (mapUidBytes[uid])!!) / dt
                        if (speed > 0) {
                            mapSpeedUid[speed] = uid
                            mapUidBytes[uid] = bytes
                        }
                    }
                    val sb: StringBuilder = StringBuilder()
                    for ((i, speed: Float) in mapSpeedUid.keys.withIndex()) {
                        if (i >= 3) break
                        if (speed < 1000 * 1000) sb.append(getString(R.string.msg_kbsec, speed / 1000)) else sb.append(getString(R.string.msg_mbsec, speed / 1000 / 1000))
                        sb.append(' ')
                        val apps: List<String?> = Util.getApplicationNames((mapSpeedUid[speed])!!, this@ServiceSinkhole)
                        sb.append(if (apps.isNotEmpty()) apps[0] else "?")
                        sb.append("\r\n")
                    }
                    if (sb.isNotEmpty()) sb.setLength(sb.length - 2)
                    remoteViews.setTextViewText(R.id.tvTop, sb.toString())
                }
            }
            t = ct
            tx = ttx
            rx = trx

            // Create bitmap
            val height: Int = Util.dips2pixels(96, this@ServiceSinkhole)
            val width: Int = Util.dips2pixels(96 * 5, this@ServiceSinkhole)
            val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Create canvas
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            // Determine max
            var max = 0f
            var xmax: Long = 0
            var ymax = 0f
            for (i in gt.indices) {
                val t: Long = gt[i]
                val tx: Float = gtx[i]
                val rx: Float = grx[i]
                if (t > xmax) xmax = t
                if (tx > max) max = tx
                if (rx > max) max = rx
                if (tx > ymax) ymax = tx
                if (rx > ymax) ymax = rx
            }

            // Build paths
            val ptx = Path()
            val prx = Path()
            for (i in gtx.indices) {
                val x: Float = width - (width * (xmax - gt[i])) / 1000f / samples
                val ytx: Float = height - height * gtx[i] / ymax
                val yrx: Float = height - height * grx[i] / ymax
                if (i == 0) {
                    ptx.moveTo(x, ytx)
                    prx.moveTo(x, yrx)
                } else {
                    ptx.lineTo(x, ytx)
                    prx.lineTo(x, yrx)
                }
            }

            // Build paint
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE

            // Draw scale line
            paint.strokeWidth = Util.dips2pixels(1, this@ServiceSinkhole).toFloat()
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorGrayed)
            val y: Float = (height / 2).toFloat()
            canvas.drawLine(0f, y, width.toFloat(), y, paint)

            // Draw paths
            paint.strokeWidth = Util.dips2pixels(2, this@ServiceSinkhole).toFloat()
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorSend)
            canvas.drawPath(ptx, paint)
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorReceive)
            canvas.drawPath(prx, paint)

            // Update remote view
            remoteViews.setImageViewBitmap(R.id.ivTraffic, bitmap)
            if (txsec < 1000 * 1000) remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_kbsec, txsec / 1000)) else remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_mbsec, txsec / 1000 / 1000))
            if (rxsec < 1000 * 1000) remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_kbsec, rxsec / 1000)) else remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_mbsec, rxsec / 1000 / 1000))
            if (max < 1000 * 1000) remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_kbsec, max / 2 / 1000)) else remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_mbsec, max / 2 / 1000 / 1000))

            // Show session/file count
            if (filter && loglevel <= Log.WARN) {
                val count: IntArray = jni_get_stats(jni_context)
                remoteViews.setTextViewText(R.id.tvSessions, count[0].toString() + "/" + count[1] + "/" + count[2])
                remoteViews.setTextViewText(R.id.tvFiles, count[3].toString() + "/" + count[4])
            } else {
                remoteViews.setTextViewText(R.id.tvSessions, "")
                remoteViews.setTextViewText(R.id.tvFiles, "")
            }

            // Show notification
            val main = Intent(this@ServiceSinkhole, ActivityMain::class.java)
            val pi: PendingIntent = PendingIntent.getActivity(this@ServiceSinkhole, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this@ServiceSinkhole, "notify")
            builder.setWhen(`when`)
                    .setSmallIcon(R.drawable.ic_equalizer_white_24dp)
                    .setContent(remoteViews)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setOngoing(true)
                    .setAutoCancel(false)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            if (state == State.none || state == State.waiting) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_TRAFFIC, builder.build())
                state = State.stats
                Log.d(TAG, "Start foreground state=$state")
            } else NotificationManagerCompat.from(this@ServiceSinkhole).notify(NOTIFY_TRAFFIC, builder.build())
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Throws(SecurityException::class)
    private fun startVPN(builder: Builder?): ParcelFileDescriptor? {
        try {
            val pfd: ParcelFileDescriptor? = builder!!.establish()

            // Set underlying network
            val cm: ConnectivityManager? = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            val active: Network? = (cm?.activeNetwork)
            if (active != null) {
                Log.i(TAG, "Setting underlying network=" + cm.getNetworkInfo(active))
                setUnderlyingNetworks(arrayOf(active))
            }
            return pfd
        } catch (ex: SecurityException) {
            throw ex
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            return null
        }
    }

    private fun getBuilder(listAllowed: List<Rule?>, listRule: List<Rule?>): Builder {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val subnet: Boolean = prefs.getBoolean("subnet", false)
        val tethering: Boolean = prefs.getBoolean("tethering", false)
        val lan: Boolean = prefs.getBoolean("lan", false)
        val ip6: Boolean = prefs.getBoolean("ip6", true)
        val filter: Boolean = prefs.getBoolean("filter", false)
        val system: Boolean = prefs.getBoolean("manage_system", false)

        // Build VPN service
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(Util.isMeteredNetwork(this))

        // VPN address
        val vpn4: String? = prefs.getString("vpn4", "10.1.10.1")
        Log.i(TAG, "Using VPN4=$vpn4")
        builder.addAddress((vpn4)!!, 32)
        if (ip6) {
            val vpn6: String? = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1")
            Log.i(TAG, "Using VPN6=$vpn6")
            builder.addAddress((vpn6)!!, 128)
        }

        // DNS address
        if (filter) for (dns: InetAddress? in getDns(this@ServiceSinkhole)) {
            if (ip6 || dns is Inet4Address) {
                Log.i(TAG, "Using DNS=$dns")
                builder.addDnsServer((dns)!!)
            }
        }

        // Subnet routing
        if (subnet) {
            // Exclude IP ranges
            val listExclude: MutableList<CIDR> = ArrayList()
            listExclude.add(CIDR("127.0.0.0", 8)) // localhost
            if (tethering && !lan) {
                // USB tethering 192.168.42.x
                // Wi-Fi tethering 192.168.43.x
                listExclude.add(CIDR("192.168.42.0", 23))
                // Bluetooth tethering 192.168.44.x
                listExclude.add(CIDR("192.168.44.0", 24))
                // Wi-Fi direct 192.168.49.x
                listExclude.add(CIDR("192.168.49.0", 24))
            }
            if (lan) {
                // https://tools.ietf.org/html/rfc1918
                listExclude.add(CIDR("10.0.0.0", 8))
                listExclude.add(CIDR("172.16.0.0", 12))
                listExclude.add(CIDR("192.168.0.0", 16))
            }

            // https://en.wikipedia.org/wiki/Mobile_country_code
            val config: Configuration = resources.configuration

            // T-Mobile Wi-Fi calling
            if (config.mcc == 310 && ((config.mnc == 160) || (
                            config.mnc == 200) || (
                            config.mnc == 210) || (
                            config.mnc == 220) || (
                            config.mnc == 230) || (
                            config.mnc == 240) || (
                            config.mnc == 250) || (
                            config.mnc == 260) || (
                            config.mnc == 270) || (
                            config.mnc == 310) || (
                            config.mnc == 490) || (
                            config.mnc == 660) || (
                            config.mnc == 800))) {
                listExclude.add(CIDR("66.94.2.0", 24))
                listExclude.add(CIDR("66.94.6.0", 23))
                listExclude.add(CIDR("66.94.8.0", 22))
                listExclude.add(CIDR("208.54.0.0", 16))
            }

            // Verizon wireless calling
            if (((config.mcc == 310 &&
                            ((config.mnc == 4) || (
                                    config.mnc == 5) || (
                                    config.mnc == 6) || (
                                    config.mnc == 10) || (
                                    config.mnc == 12) || (
                                    config.mnc == 13) || (
                                    config.mnc == 350) || (
                                    config.mnc == 590) || (
                                    config.mnc == 820) || (
                                    config.mnc == 890) || (
                                    config.mnc == 910))) ||
                            (config.mcc == 311 && (((config.mnc == 12) || (
                                    config.mnc == 110) ||
                                    (config.mnc in 270..289) || (
                                    config.mnc == 390) ||
                                    (config.mnc in 480..489) || (
                                    config.mnc == 590)))) ||
                            (config.mcc == 312 && (config.mnc == 770)))) {
                listExclude.add(CIDR("66.174.0.0", 16)) // 66.174.0.0 - 66.174.255.255
                listExclude.add(CIDR("66.82.0.0", 15)) // 69.82.0.0 - 69.83.255.255
                listExclude.add(CIDR("69.96.0.0", 13)) // 69.96.0.0 - 69.103.255.255
                listExclude.add(CIDR("70.192.0.0", 11)) // 70.192.0.0 - 70.223.255.255
                listExclude.add(CIDR("97.128.0.0", 9)) // 97.128.0.0 - 97.255.255.255
                listExclude.add(CIDR("174.192.0.0", 9)) // 174.192.0.0 - 174.255.255.255
                listExclude.add(CIDR("72.96.0.0", 9)) // 72.96.0.0 - 72.127.255.255
                listExclude.add(CIDR("75.192.0.0", 9)) // 75.192.0.0 - 75.255.255.255
                listExclude.add(CIDR("97.0.0.0", 10)) // 97.0.0.0 - 97.63.255.255
            }

            // Broadcast
            listExclude.add(CIDR("224.0.0.0", 3))
            listExclude.sort()
            try {
                var start: InetAddress? = InetAddress.getByName("0.0.0.0")
                for (exclude: CIDR in listExclude) {
                    Log.i(TAG, "Exclude " + exclude.start!!.hostAddress + "..." + exclude.end!!.hostAddress)
                    for (include: CIDR? in IPUtil.toCIDR(start, IPUtil.minus1(exclude.start))) try {
                        builder.addRoute((include!!.address)!!, include.prefix)
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    start = IPUtil.plus1(exclude.end)
                }
                val end: String = (if (lan) "255.255.255.254" else "255.255.255.255")
                for (include: CIDR? in IPUtil.toCIDR("224.0.0.0", end)) try {
                    builder.addRoute((include!!.address)!!, include.prefix)
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            } catch (ex: UnknownHostException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        } else builder.addRoute("0.0.0.0", 0)
        Log.i(TAG, "IPv6=$ip6")
        if (ip6) builder.addRoute("2000::", 3) // unicast

        // MTU
        val mtu: Int = jni_get_mtu()
        Log.i(TAG, "MTU=$mtu")
        builder.setMtu(mtu)

        // Add list of allowed applications
        try {
            builder.addDisallowedApplication(getPackageName())
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        if (last_connected && !filter) for (rule: Rule? in listAllowed) try {
            builder.addDisallowedApplication(rule!!.packageName)
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        } else if (filter) for (rule: Rule? in listRule) if (!rule!!.apply || (!system && rule.system)) try {
            Log.i(TAG, "Not routing " + rule.packageName)
            builder.addDisallowedApplication(rule.packageName)
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }

        // Build configure intent
        val configure = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, configure, PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setConfigureIntent(pi)
        return builder
    }

    private fun startNative(vpn: ParcelFileDescriptor, listAllowed: List<Rule?>, listRule: List<Rule?>) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        val log: Boolean = prefs.getBoolean("log", false)
        val log_app: Boolean = prefs.getBoolean("log_app", false)
        val filter: Boolean = prefs.getBoolean("filter", false)
        Log.i(TAG, "Start native log=$log/$log_app filter=$filter")

        // Prepare rules
        if (filter) {
            prepareUidAllowed(listAllowed, listRule)
            prepareHostsBlocked()
            prepareUidIPFilters(null)
            prepareForwarding()
        } else {
            lock.writeLock().lock()
            mapUidAllowed.clear()
            mapUidKnown.clear()
            mapHostsBlocked.clear()
            mapUidIPFilters.clear()
            mapForward.clear()
            lock.writeLock().unlock()
        }
        if (log_app) prepareNotify(listRule) else {
            lock.writeLock().lock()
            mapNotify.clear()
            lock.writeLock().unlock()
        }
        if (log || log_app || filter) {
            val prio: Int = prefs.getString("loglevel", Log.WARN.toString())!!.toInt()
            val rcode: Int = prefs.getString("rcode", "3")!!.toInt()
            if (prefs.getBoolean("socks5_enabled", false)) jni_socks5(
                    prefs.getString("socks5_addr", ""), prefs.getString("socks5_port", "0")!!.toInt(),
                    prefs.getString("socks5_username", ""),
                    prefs.getString("socks5_password", "")) else jni_socks5("", 0, "", "")
            if (tunnelThread == null) {
                Log.i(TAG, "Starting tunnel thread context=$jni_context")
                jni_start(jni_context, prio)
                tunnelThread = Thread {
                    Log.i(TAG, "Running tunnel context=$jni_context")
                    jni_run(jni_context, vpn.fd, mapForward.containsKey(53), rcode)
                    Log.i(TAG, "Tunnel exited")
                    tunnelThread = null
                }
                //tunnelThread.setPriority(Thread.MAX_PRIORITY);
                tunnelThread!!.start()
                Log.i(TAG, "Started tunnel thread")
            }
        }
    }

    private fun stopNative() {
        Log.i(TAG, "Stop native")
        if (tunnelThread != null) {
            Log.i(TAG, "Stopping tunnel thread")
            jni_stop(jni_context)
            var thread: Thread? = tunnelThread
            while (thread != null && thread.isAlive) {
                try {
                    Log.i(TAG, "Joining tunnel thread context=$jni_context")
                    thread.join()
                } catch (ignored: InterruptedException) {
                    Log.i(TAG, "Joined tunnel interrupted")
                }
                thread = tunnelThread
            }
            tunnelThread = null
            jni_clear(jni_context)
            Log.i(TAG, "Stopped tunnel thread")
        }
    }

    private fun unprepare() {
        lock.writeLock().lock()
        mapUidAllowed.clear()
        mapUidKnown.clear()
        mapHostsBlocked.clear()
        mapUidIPFilters.clear()
        mapForward.clear()
        mapNotify.clear()
        lock.writeLock().unlock()
    }

    private fun prepareUidAllowed(listAllowed: List<Rule?>, listRule: List<Rule?>) {
        lock.writeLock().lock()
        mapUidAllowed.clear()
        for (rule: Rule? in listAllowed) mapUidAllowed[rule!!.uid] = true
        mapUidKnown.clear()
        for (rule: Rule? in listRule) mapUidKnown[rule!!.uid] = rule.uid
        lock.writeLock().unlock()
    }

    private fun prepareHostsBlocked() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        val use_hosts: Boolean = prefs.getBoolean("filter", false) && prefs.getBoolean("use_hosts", false)
        val hosts = File(filesDir, "hosts.txt")
        if (!use_hosts || !hosts.exists() || !hosts.canRead()) {
            Log.i(TAG, "Hosts file use=" + use_hosts + " exists=" + hosts.exists())
            lock.writeLock().lock()
            mapHostsBlocked.clear()
            lock.writeLock().unlock()
            return
        }
        val changed: Boolean = (hosts.lastModified() != last_hosts_modified)
        if (!changed && mapHostsBlocked.isNotEmpty()) {
            Log.i(TAG, "Hosts file unchanged")
            return
        }
        last_hosts_modified = hosts.lastModified()
        lock.writeLock().lock()
        mapHostsBlocked.clear()
        var count = 0
        var br: BufferedReader? = null
        try {
            br = BufferedReader(FileReader(hosts))
            var line: String
            while ((br.readLine().also { line = it }) != null) {
                val hash: Int = line.indexOf('#')
                if (hash >= 0) line = line.substring(0, hash)
                line = line.trim { it <= ' ' }
                if (line.isNotEmpty()) {
                    val words: Array<String> = line.split("\\s+").toTypedArray()
                    if (words.size == 2) {
                        count++
                        mapHostsBlocked[words[1]] = true
                    } else Log.i(TAG, "Invalid hosts file line: $line")
                }
            }
            mapHostsBlocked["test.netguard.me"] = true
            Log.i(TAG, "$count hosts read")
        } catch (ex: IOException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        } finally {
            if (br != null) try {
                br.close()
            } catch (exex: IOException) {
                Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex))
            }
        }
        lock.writeLock().unlock()
    }

    private fun prepareUidIPFilters(dname: String?) {
        val lockdown: SharedPreferences = getSharedPreferences("lockdown", MODE_PRIVATE)
        lock.writeLock().lock()
        if (dname == null) {
            mapUidIPFilters.clear()
            if (!IAB.isPurchased(ActivityPro.SKU_FILTER, this@ServiceSinkhole)) {
                lock.writeLock().unlock()
                return
            }
        }
        DatabaseHelper.getInstance(this@ServiceSinkhole).getAccessDns(dname).use { cursor ->
            val colUid: Int = cursor.getColumnIndex("uid")
            val colVersion: Int = cursor.getColumnIndex("version")
            val colProtocol: Int = cursor.getColumnIndex("protocol")
            val colDAddr: Int = cursor.getColumnIndex("daddr")
            val colResource: Int = cursor.getColumnIndex("resource")
            val colDPort: Int = cursor.getColumnIndex("dport")
            val colBlock: Int = cursor.getColumnIndex("block")
            val colTime: Int = cursor.getColumnIndex("time")
            val colTTL: Int = cursor.getColumnIndex("ttl")
            while (cursor.moveToNext()) {
                val uid: Int = cursor.getInt(colUid)
                val version: Int = cursor.getInt(colVersion)
                val protocol: Int = cursor.getInt(colProtocol)
                val daddr: String = cursor.getString(colDAddr)
                val dresource: String? = (if (cursor.isNull(colResource)) null else cursor.getString(colResource))
                val dport: Int = cursor.getInt(colDPort)
                val block: Boolean = (cursor.getInt(colBlock) > 0)
                val time: Long = (if (cursor.isNull(colTime)) Date().time else cursor.getLong(colTime))
                val ttl: Long = (if (cursor.isNull(colTTL)) 7 * 24 * 3600 * 1000L else cursor.getLong(colTTL))
                if (isLockedDown(last_metered)) {
                    val pkg: Array<String>? = packageManager.getPackagesForUid(uid)
                    if (pkg != null && pkg.isNotEmpty()) {
                        if (!lockdown.getBoolean(pkg[0], false)) continue
                    }
                }
                val key = IPKey(version, protocol, dport, uid)
                synchronized(mapUidIPFilters) {
                    if (!mapUidIPFilters.containsKey(key)) mapUidIPFilters[key] = HashMap<InetAddress?, ServiceSinkhole.IPRule?>()
                    try {
                        val name: String = (dresource ?: daddr)
                        if (Util.isNumericAddress(name)) {
                            val iname: InetAddress = InetAddress.getByName(name)
                            if (version == 4 && iname !is Inet4Address) continue
                            if (version == 6 && iname !is Inet6Address) continue
                            val exists: Boolean = mapUidIPFilters[key]!!.containsKey(iname)
                            if (!exists || !mapUidIPFilters[key]!![iname]!!.isBlocked) {
                                val rule = IPRule(key, "$name/$iname", block, time + ttl)
                                mapUidIPFilters[key]!![iname] = rule
                                if (exists) Log.w(TAG, "Address conflict $key $daddr/$dresource")
                            } else if (exists) {
                                mapUidIPFilters[key]!![iname]!!.updateExpires(time + ttl)
                                if (dname != null && ttl > 60 * 1000L) Log.w(TAG, "Address updated $key $daddr/$dresource")
                            } else {
                                if (dname != null) Log.i(TAG, "Ignored $key $daddr/$dresource=$block")
                            }
                        } else Log.w(TAG, "Address not numeric $name")
                    } catch (ex: UnknownHostException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }
        }
        lock.writeLock().unlock()
    }

    private fun prepareForwarding() {
        lock.writeLock().lock()
        mapForward.clear()
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("filter", false)) {
            DatabaseHelper.getInstance(this@ServiceSinkhole).forwarding.use { cursor ->
                val colProtocol: Int = cursor.getColumnIndex("protocol")
                val colDPort: Int = cursor.getColumnIndex("dport")
                val colRAddr: Int = cursor.getColumnIndex("raddr")
                val colRPort: Int = cursor.getColumnIndex("rport")
                val colRUid: Int = cursor.getColumnIndex("ruid")
                while (cursor.moveToNext()) {
                    val fwd = Forward()
                    fwd.protocol = cursor.getInt(colProtocol)
                    fwd.dport = cursor.getInt(colDPort)
                    fwd.raddr = cursor.getString(colRAddr)
                    fwd.rport = cursor.getInt(colRPort)
                    fwd.ruid = cursor.getInt(colRUid)
                    mapForward[fwd.dport] = fwd
                    Log.i(TAG, "Forward $fwd")
                }
            }
        }
        lock.writeLock().unlock()
    }

    private fun prepareNotify(listRule: List<Rule?>) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val notify: Boolean = prefs.getBoolean("notify_access", false)
        val system: Boolean = prefs.getBoolean("manage_system", false)
        lock.writeLock().lock()
        mapNotify.clear()
        for (rule: Rule? in listRule) mapNotify[rule!!.uid] = notify && rule.notify && (system || !rule.system)
        lock.writeLock().unlock()
    }

    private fun isLockedDown(metered: Boolean): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        var lockdown: Boolean = prefs.getBoolean("lockdown", false)
        val lockdown_wifi: Boolean = prefs.getBoolean("lockdown_wifi", true)
        val lockdown_other: Boolean = prefs.getBoolean("lockdown_other", true)
        if (if (metered) !lockdown_other else !lockdown_wifi) lockdown = false
        return lockdown
    }

    private fun getAllowedRules(listRule: List<Rule?>): List<Rule?> {
        val listAllowed: MutableList<Rule?> = ArrayList()
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // Check state
        val wifi: Boolean = Util.isWifiActive(this)
        var metered: Boolean = Util.isMeteredNetwork(this)
        val useMetered: Boolean = prefs.getBoolean("use_metered", false)
        val ssidHomes: MutableSet<String?>? = prefs.getStringSet("wifi_homes", HashSet())
        val ssidNetwork: String = Util.getWifiSSID(this)
        val generation: String? = Util.getNetworkGeneration(this)
        val unmetered_2g: Boolean = prefs.getBoolean("unmetered_2g", false)
        val unmetered_3g: Boolean = prefs.getBoolean("unmetered_3g", false)
        val unmetered_4g: Boolean = prefs.getBoolean("unmetered_4g", false)
        var roaming: Boolean = Util.isRoaming(this@ServiceSinkhole)
        val national: Boolean = prefs.getBoolean("national_roaming", false)
        val eu: Boolean = prefs.getBoolean("eu_roaming", false)
        val tethering: Boolean = prefs.getBoolean("tethering", false)
        val filter: Boolean = prefs.getBoolean("filter", false)

        // Update connected state
        last_connected = Util.isConnected(this@ServiceSinkhole)
        val org_metered: Boolean = metered
        val org_roaming: Boolean = roaming

        // https://issuetracker.google.com/issues/70633700
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ssidHomes!!.clear()

        // Update metered state
        if (wifi && !useMetered) metered = false
        if ((wifi && (ssidHomes!!.size > 0) &&
                        !(ssidHomes.contains(ssidNetwork) || ssidHomes.contains('"'.toString() + ssidNetwork + '"')))) {
            metered = true
            Log.i(TAG, "!@home=" + ssidNetwork + " homes=" + TextUtils.join(",", (ssidHomes)))
        }
        if (unmetered_2g && ("2G" == generation)) metered = false
        if (unmetered_3g && ("3G" == generation)) metered = false
        if (unmetered_4g && ("4G" == generation)) metered = false
        last_metered = metered
        val lockdown: Boolean = isLockedDown(last_metered)

        // Update roaming state
        if (roaming && eu) roaming = !Util.isEU(this)
        if (roaming && national) roaming = !Util.isNational(this)
        Log.i(TAG, ("Get allowed" +
                " connected=" + last_connected +
                " wifi=" + wifi +
                " home=" + TextUtils.join(",", (ssidHomes)!!) +
                " network=" + ssidNetwork +
                " metered=" + metered + "/" + org_metered +
                " generation=" + generation +
                " roaming=" + roaming + "/" + org_roaming +
                " interactive=" + last_interactive +
                " tethering=" + tethering +
                " filter=" + filter +
                " lockdown=" + lockdown))
        if (last_connected) for (rule: Rule? in listRule) {
            val blocked: Boolean = (if (metered) rule!!.other_blocked else rule!!.wifi_blocked)
            val screen: Boolean = (if (metered) rule.screen_other else rule.screen_wifi)
            if (((!blocked || (screen && last_interactive)) &&
                            (!metered || !(rule.roaming && roaming)) &&
                            (!lockdown || rule.lockdown))) listAllowed.add(rule)
        }
        Log.i(TAG, "Allowed " + listAllowed.size + " of " + listRule.size)
        return listAllowed
    }

    private fun stopVPN(pfd: ParcelFileDescriptor) {
        Log.i(TAG, "Stopping")
        try {
            pfd.close()
        } catch (ex: IOException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }

    // Called from native code
    private fun nativeExit(reason: String?) {
        Log.w(TAG, "Native exit reason=$reason")
        if (reason != null) {
            showErrorNotification(reason)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit().putBoolean("enabled", false).apply()
            WidgetMain.updateWidgets(this)
        }
    }

    // Called from native code
    private fun nativeError(error: Int, message: String) {
        Log.w(TAG, "Native error $error: $message")
        showErrorNotification(message)
    }

    // Called from native code
    private fun logPacket(packet: Packet) {
        logHandler!!.queue(packet)
    }

    // Called from native code
    private fun dnsResolved(rr: ResourceRecord) {
        if (DatabaseHelper.getInstance(this@ServiceSinkhole).insertDns(rr)) {
            Log.i(TAG, "New IP $rr")
            prepareUidIPFilters(rr.QName)
        }
    }

    // Called from native code
    private fun isDomainBlocked(name: String): Boolean {
        lock.readLock().lock()
        val blocked: Boolean = (mapHostsBlocked.containsKey(name) && (mapHostsBlocked.get(name))!!)
        lock.readLock().unlock()
        return blocked
    }

    // Called from native code
    @TargetApi(Build.VERSION_CODES.Q)
    private fun getUidQ(protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) return Process.INVALID_UID
        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
                ?: return Process.INVALID_UID
        val local = InetSocketAddress(saddr, sport)
        val remote = InetSocketAddress(daddr, dport)
        Log.i(TAG, "Get uid local=$local remote=$remote")
        val uid: Int = cm.getConnectionOwnerUid(protocol, local, remote)
        Log.i(TAG, "Get uid=$uid")
        return uid
    }

    private fun isSupported(protocol: Int): Boolean {
        return ((protocol == 1 /* ICMPv4 */) || (
                protocol == 58 /* ICMPv6 */) || (
                protocol == 6 /* TCP */) || (
                protocol == 17) /* UDP */)
    }

    // Called from native code
    private fun isAddressAllowed(packet: Packet): Allowed? {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        lock.readLock().lock()
        if (packet.protocol == 6 && (packet.flags == "A")) {
            packet.allowed = true
            ParseNetwork.parse(packet)
        } else packet.allowed = packet.protocol == 6 && !packet.flags!!.contains("S")
        //packet.allowed = false;
        if (!packet.allowed) {
            if (prefs.getBoolean("filter", false)) {
                // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
                if (packet.protocol == 17 /* UDP */ && !prefs.getBoolean("filter_udp", false)) {
                    // Allow unfiltered UDP
                    packet.allowed = true
                    Log.i(TAG, "Allowing UDP $packet")
                } else if ((false)) {
                    // Allow system applications in disconnected state
                    packet.allowed = true
                    Log.w(TAG, "Allowing disconnected system $packet")
                } else if (((packet.uid < 2000) &&
                                !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol))) {
                    // Allow unknown system traffic
                    packet.allowed = true
                    Log.w(TAG, "Allowing unknown system $packet")
                } else if (packet.uid == Process.myUid()) {
                    // Allow self
                    packet.allowed = true
                    Log.w(TAG, "Allowing self $packet")
                } else {
                    var filtered = false
                    val key = IPKey(packet.version, packet.protocol, packet.dport, packet.uid)
                    if (mapUidIPFilters.containsKey(key)) try {
                        val iaddr: InetAddress = InetAddress.getByName(packet.daddr)
                        val map: Map<InetAddress?, IPRule?>? = mapUidIPFilters[key]
                        if (map != null && map.containsKey(iaddr)) {
                            val rule: IPRule? = map[iaddr]
                            if (rule!!.isExpired) Log.i(TAG, "DNS expired $packet rule $rule") else {
                                filtered = true
                                packet.allowed = !rule.isBlocked
                                Log.i(TAG, ("Filtering " + packet +
                                        " allowed=" + packet.allowed + " rule " + rule))
                            }
                        }
                    } catch (ex: UnknownHostException) {
                        Log.w(TAG, "Allowed " + ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    if (!filtered) if (mapUidAllowed.containsKey(packet.uid)) packet.allowed = (mapUidAllowed[packet.uid])!! else Log.w(TAG, "No rules for $packet")
                }
            }
        }
        var allowed: Allowed? = null
        if (packet.allowed) {
            if (mapForward.containsKey(packet.dport)) {
                val fwd: Forward? = mapForward[packet.dport]
                if (fwd!!.ruid == packet.uid) {
                    allowed = Allowed()
                } else {
                    allowed = Allowed(fwd.raddr, fwd.rport)
                    packet.data = "> " + fwd.raddr + "/" + fwd.rport
                }
            } else allowed = Allowed()
        }
        lock.readLock().unlock()
        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", false)) if (packet.protocol != 6 /* TCP */ || "" != packet.flags) if (packet.uid != Process.myUid()) logPacket(packet)
        return allowed
    }

    // Called from native code
    private fun accountUsage(usage: Usage) {
        logHandler!!.account(usage)
    }

    private val interactiveStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            executor.submit {
                val am: AlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
                val i = Intent(ACTION_SCREEN_OFF_DELAYED)
                i.setPackage(context.packageName)
                val pi: PendingIntent = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT)
                am.cancel(pi)
                try {
                    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    val delay: Int
                    delay = try {
                        prefs.getString("screen_delay", "0")!!.toInt()
                    } catch (ignored: NumberFormatException) {
                        0
                    }
                    val interactive: Boolean = (Intent.ACTION_SCREEN_ON == intent.action)
                    if (interactive || delay == 0) {
                        last_interactive = interactive
                        reload("interactive state changed", this@ServiceSinkhole, true)
                    } else {
                        if ((ACTION_SCREEN_OFF_DELAYED == intent.action)) {
                            last_interactive = interactive
                            reload("interactive state changed", this@ServiceSinkhole, true)
                        } else {
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().time + delay * 60 * 1000L, pi)
                        }
                    }

                    // Start/stop stats
                    statsHandler!!.sendEmptyMessage(
                            if (Util.isInteractive(this@ServiceSinkhole)) MSG_STATS_START else MSG_STATS_STOP)
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().time + 15 * 1000L, pi)
                }
            }
        }
    }
    private val userReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            user_foreground = (Intent.ACTION_USER_FOREGROUND == intent.action)
            Log.i(TAG, "User foreground=" + user_foreground + " user=" + (Process.myUid() / 100000))
            if (user_foreground) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("enabled", false)) {
                    // Allow service of background user to stop
                    try {
                        Thread.sleep(3000)
                    } catch (ignored: InterruptedException) {
                    }
                    start("foreground", this@ServiceSinkhole)
                }
            } else stop("background", this@ServiceSinkhole, true)
        }
    }
    private val idleStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            val pm: PowerManager = context.getSystemService(POWER_SERVICE) as PowerManager
            Log.i(TAG, "device idle=" + pm.isDeviceIdleMode)

            // Reload rules when coming from idle mode
            if (!pm.isDeviceIdleMode) reload("idle state changed", this@ServiceSinkhole, false)
        }
    }
    private val connectivityChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Filter VPN connectivity changes
            val networkType: Int = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY)
            if (networkType == ConnectivityManager.TYPE_VPN) return

            // Reload rules
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            reload("connectivity changed", this@ServiceSinkhole, false)
        }
    }
    private var networkMonitorCallback: NetworkCallback = object : NetworkCallback() {
        private val TAG: String = "NetGuard.Monitor"
        private val validated: MutableMap<Network, Long> = HashMap()

        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java
        override fun onAvailable(network: Network) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            val capabilities: NetworkCapabilities? = cm.getNetworkCapabilities(network)
            Log.i(TAG, "Available network $network $ni")
            Log.i(TAG, "Capabilities=$capabilities")
            checkConnectivity(network, ni, capabilities)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "New capabilities network $network $ni")
            Log.i(TAG, "Capabilities=$capabilities")
            checkConnectivity(network, ni, capabilities)
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "Losing network $network within $maxMsToLive ms $ni")
        }

        override fun onLost(network: Network) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "Lost network $network $ni")
            synchronized(validated) { validated.remove(network) }
        }

        override fun onUnavailable() {
            Log.i(TAG, "No networks available")
        }

        private fun checkConnectivity(network: Network, ni: NetworkInfo?, capabilities: NetworkCapabilities?) {
            if (((ni != null) && (capabilities != null) && (
                            ni.detailedState != NetworkInfo.DetailedState.SUSPENDED) && (
                            ni.detailedState != NetworkInfo.DetailedState.BLOCKED) && (
                            ni.detailedState != NetworkInfo.DetailedState.DISCONNECTED) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) {
                synchronized(validated) {
                    if (validated.containsKey(network) &&
                            validated[network]!! + 20 * 1000 > Date().time) {
                        Log.i(TAG, "Already validated $network $ni")
                        return
                    }
                }
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                val host: String? = prefs.getString("validate", "www.google.com")
                Log.i(TAG, "Validating $network $ni host=$host")
                var socket: Socket? = null
                try {
                    socket = network.socketFactory.createSocket()
                    socket.connect(InetSocketAddress(host, 443), 10000)
                    Log.i(TAG, "Validated $network $ni host=$host")
                    synchronized(validated) { validated.put(network, Date().time) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        cm.reportNetworkConnectivity(network, true)
                        Log.i(TAG, "Reported $network $ni")
                    }
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString())
                    Log.i(TAG, "No connectivity $network $ni")
                } finally {
                    if (socket != null) try {
                        socket.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }
        }
    }
    private val phoneStateListener: PhoneStateListener = object : PhoneStateListener() {
        private var last_generation: String? = null
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                val current_generation: String? = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, "Data connected generation=$current_generation")
                if (last_generation == null || last_generation != current_generation) {
                    Log.i(TAG, "New network generation=$current_generation")
                    last_generation = current_generation
                    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    if ((prefs.getBoolean("unmetered_2g", false) ||
                                    prefs.getBoolean("unmetered_3g", false) ||
                                    prefs.getBoolean("unmetered_4g", false))) reload("data connection state changed", this@ServiceSinkhole, false)
                }
            }
        }
    }
    private val packageChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            try {
                if ((Intent.ACTION_PACKAGE_ADDED == intent.action)) {
                    // Application added
                    Rule.clearCache(context)
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // Show notification
                        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                        if (IAB.isPurchased(ActivityPro.SKU_NOTIFY, context) && prefs.getBoolean("install", true)) {
                            val uid: Int = intent.getIntExtra(Intent.EXTRA_UID, -1)
                            notifyNewApplication(uid)
                        }
                    }
                    reload("package added", context, false)
                } else if ((Intent.ACTION_PACKAGE_REMOVED == intent.action)) {
                    // Application removed
                    Rule.clearCache(context)
                    if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                        // Remove settings
                        val packageName: String = intent.data!!.schemeSpecificPart
                        Log.i(TAG, "Deleting settings package=$packageName")
                        context.getSharedPreferences("wifi", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("other", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("screen_wifi", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("screen_other", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("roaming", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("lockdown", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("apply", MODE_PRIVATE).edit().remove(packageName).apply()
                        context.getSharedPreferences("notify", MODE_PRIVATE).edit().remove(packageName).apply()
                        val uid: Int = intent.getIntExtra(Intent.EXTRA_UID, 0)
                        if (uid > 0) {
                            val dh: DatabaseHelper = DatabaseHelper.getInstance(context)
                            dh.clearLog(uid)
                            dh.clearAccess(uid, false)
                            NotificationManagerCompat.from(context).cancel(uid) // installed notification
                            NotificationManagerCompat.from(context).cancel(uid + 10000) // access notification
                        }
                    }
                    reload("package deleted", context, false)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }
    }

    fun notifyNewApplication(uid: Int) {
        if (uid < 0) return
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        try {
            // Get application name
            val name: String = TextUtils.join(", ", Util.getApplicationNames(uid, this))

            // Get application info
            val pm: PackageManager = packageManager
            val packages: Array<String>? = pm.getPackagesForUid(uid)
            if (packages == null || packages.isEmpty()) throw PackageManager.NameNotFoundException(uid.toString())
            val internet: Boolean = Util.hasInternet(uid, this)

            // Build notification
            val main = Intent(this, ActivityMain::class.java)
            main.putExtra(ActivityMain.EXTRA_REFRESH, true)
            main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
            val pi: PendingIntent = PendingIntent.getActivity(this, uid, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
            builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(name)
                    .setContentText(getString(R.string.msg_installed_n)) else builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_installed, name))
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)

            // Get defaults
            val prefs_wifi: SharedPreferences = getSharedPreferences("wifi", MODE_PRIVATE)
            val prefs_other: SharedPreferences = getSharedPreferences("other", MODE_PRIVATE)
            val wifi: Boolean = prefs_wifi.getBoolean(packages[0], prefs.getBoolean("whitelist_wifi", true))
            val other: Boolean = prefs_other.getBoolean(packages[0], prefs.getBoolean("whitelist_other", true))

            // Build Wi-Fi action
            val riWifi = Intent(this, ServiceSinkhole::class.java)
            riWifi.putExtra(EXTRA_COMMAND, Command.set)
            riWifi.putExtra(EXTRA_NETWORK, "wifi")
            riWifi.putExtra(EXTRA_UID, uid)
            riWifi.putExtra(EXTRA_PACKAGE, packages[0])
            riWifi.putExtra(EXTRA_BLOCKED, !wifi)
            val piWifi: PendingIntent = PendingIntent.getService(this, uid, riWifi, PendingIntent.FLAG_UPDATE_CURRENT)
            val wAction: NotificationCompat.Action = NotificationCompat.Action.Builder(
                    if (wifi) R.drawable.wifi_on else R.drawable.wifi_off,
                    getString(if (wifi) R.string.title_allow_wifi else R.string.title_block_wifi),
                    piWifi
            ).build()
            builder.addAction(wAction)

            // Build mobile action
            val riOther = Intent(this, ServiceSinkhole::class.java)
            riOther.putExtra(EXTRA_COMMAND, Command.set)
            riOther.putExtra(EXTRA_NETWORK, "other")
            riOther.putExtra(EXTRA_UID, uid)
            riOther.putExtra(EXTRA_PACKAGE, packages[0])
            riOther.putExtra(EXTRA_BLOCKED, !other)
            val piOther: PendingIntent = PendingIntent.getService(this, uid + 10000, riOther, PendingIntent.FLAG_UPDATE_CURRENT)
            val oAction: NotificationCompat.Action = NotificationCompat.Action.Builder(
                    if (other) R.drawable.other_on else R.drawable.other_off,
                    getString(if (other) R.string.title_allow_other else R.string.title_block_other),
                    piOther
            ).build()
            builder.addAction(oAction)

            // Show notification
            if (internet) NotificationManagerCompat.from(this).notify(uid, builder.build()) else {
                val expanded: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) expanded.bigText(getString(R.string.msg_installed_n)) else expanded.bigText(getString(R.string.msg_installed, name))
                expanded.setSummaryText(getString(R.string.title_internet))
                NotificationManagerCompat.from(this).notify(uid, (expanded.build())!!)
            }
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }

    override fun onCreate() {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this))
        startForeground(NOTIFY_WAITING, waitingNotification)
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (jni_context != 0L) {
            Log.w(TAG, "Create with context=$jni_context")
            jni_stop(jni_context)
            synchronized(jni_lock) {
                jni_done(jni_context)
                jni_context = 0
            }
        }

        // Native init
        jni_context = jni_init(Build.VERSION.SDK_INT)
        Log.i(TAG, "Created context=$jni_context")
        val pcap: Boolean = prefs.getBoolean("pcap", false)
        setPcap(pcap, this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        Util.setTheme(this)
        super.onCreate()
        val commandThread = HandlerThread(getString(R.string.app_name) + " command", Process.THREAD_PRIORITY_FOREGROUND)
        val logThread = HandlerThread(getString(R.string.app_name) + " log", Process.THREAD_PRIORITY_BACKGROUND)
        val statsThread = HandlerThread(getString(R.string.app_name) + " stats", Process.THREAD_PRIORITY_BACKGROUND)
        commandThread.start()
        logThread.start()
        statsThread.start()
        commandLooper = commandThread.looper
        logLooper = logThread.looper
        statsLooper = statsThread.looper
        commandHandler = CommandHandler(commandLooper)
        logHandler = LogHandler(logLooper)
        statsHandler = StatsHandler(statsLooper)

        // Listen for user switches
        val ifUser = IntentFilter()
        ifUser.addAction(Intent.ACTION_USER_BACKGROUND)
        ifUser.addAction(Intent.ACTION_USER_FOREGROUND)
        registerReceiver(userReceiver, ifUser)
        registeredUser = true

        // Listen for idle mode state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ifIdle = IntentFilter()
            ifIdle.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            registerReceiver(idleStateReceiver, ifIdle)
            registeredIdleState = true
        }

        // Listen for added/removed applications
        val ifPackage = IntentFilter()
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED)
        ifPackage.addAction(Intent.ACTION_PACKAGE_REMOVED)
        ifPackage.addDataScheme("package")
        registerReceiver(packageChangedReceiver, ifPackage)
        registeredPackageChanged = true
        try {
            listenNetworkChanges()
        } catch (ex: Throwable) {
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            listenConnectivityChanges()
        }

        // Monitor networks
        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
                NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkMonitorCallback)

        // Setup house holding
        val alarmIntent = Intent(this, ServiceSinkhole::class.java)
        alarmIntent.action = ACTION_HOUSE_HOLDING
        val pi: PendingIntent
        pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT) else PendingIntent.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val am: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun listenNetworkChanges() {
        // Listen for network changes
        Log.i(TAG, "Starting listening to network changes")
        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder: NetworkRequest.Builder = NetworkRequest.Builder()
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val nc: NetworkCallback = object : NetworkCallback() {
            private var last_connected: Boolean? = null
            private var last_unmetered: Boolean? = null
            private var last_generation: String? = null
            private var last_dns: List<InetAddress?>? = null
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Available network=$network")
                last_connected = Util.isConnected(this@ServiceSinkhole)
                reload("network available", this@ServiceSinkhole, false)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.i(TAG, "Changed properties=$network props=$linkProperties")

                // Make sure the right DNS servers are being used
                val dns: List<InetAddress?> = linkProperties.dnsServers
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !same(last_dns, dns) else prefs.getBoolean("reload_onconnectivity", false)) {
                    Log.i(TAG, ("Changed link properties=" + linkProperties +
                            "DNS cur=" + TextUtils.join(",", dns) +
                            "DNS prv=" + (if (last_dns == null) null else TextUtils.join(",", last_dns!!))))
                    last_dns = dns
                    reload("link properties changed", this@ServiceSinkhole, false)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.i(TAG, "Changed capabilities=$network caps=$networkCapabilities")
                val connected: Boolean = Util.isConnected(this@ServiceSinkhole)
                val unmetered: Boolean = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val generation: String? = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, ("Connected=" + connected + "/" + last_connected +
                        " unmetered=" + unmetered + "/" + last_unmetered +
                        " generation=" + generation + "/" + last_generation))
                if (last_connected != null && last_connected != connected) reload("Connected state changed", this@ServiceSinkhole, false)
                if (last_unmetered != null && last_unmetered != unmetered) reload("Unmetered state changed", this@ServiceSinkhole, false)
                if (last_generation != null && last_generation != generation) {
                    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    if ((prefs.getBoolean("unmetered_2g", false) ||
                                    prefs.getBoolean("unmetered_3g", false) ||
                                    prefs.getBoolean("unmetered_4g", false))) reload("Generation changed", this@ServiceSinkhole, false)
                }
                last_connected = connected
                last_unmetered = unmetered
                last_generation = generation
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Lost network=$network")
                last_connected = Util.isConnected(this@ServiceSinkhole)
                reload("network lost", this@ServiceSinkhole, false)
            }

            fun same(last: List<InetAddress?>?, current: List<InetAddress?>?): Boolean {
                if (last == null || current == null) return false
                if (last.size != current.size) return false
                for (i in current.indices) if (last[i] != current[i]) return false
                return true
            }
        }
        cm.registerNetworkCallback(builder.build(), nc)
        networkCallback = nc
    }

    private fun listenConnectivityChanges() {
        // Listen for connectivity updates
        Log.i(TAG, "Starting listening to connectivity changes")
        val ifConnectivity = IntentFilter()
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityChangedReceiver, ifConnectivity)
        registeredConnectivityChanged = true

        // Listen for phone state changes
        Log.i(TAG, "Starting listening to service state changes")
        val tm: TelephonyManager? = getSystemService(TELEPHONY_SERVICE) as TelephonyManager?
        if (tm != null) {
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
            phone_state = true
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        if (("theme" == name)) {
            Log.i(TAG, "Theme changed")
            Util.setTheme(this)
            if (state != State.none) {
                Log.d(TAG, "Stop foreground state=$state")
                stopForeground(true)
            }
            if (state == State.enforcing) startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1)) else if (state != State.none) startForeground(NOTIFY_WAITING, waitingNotification)
            Log.d(TAG, "Start foreground state=$state")
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        var intent: Intent? = intent
        if (state == State.enforcing) startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1)) else startForeground(NOTIFY_WAITING, waitingNotification)
        Log.i(TAG, "Received $intent")
        Util.logExtras(intent)

        // Check for set command
        if ((intent != null) && intent.hasExtra(EXTRA_COMMAND) && (
                        intent.getSerializableExtra(EXTRA_COMMAND) === Command.set)) {
            set(intent)
            return START_STICKY
        }

        // Keep awake
        getLock(this)!!.acquire(10*60*1000L /*10 minutes*/)

        // Get state
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled: Boolean = prefs.getBoolean("enabled", false)

        // Handle service restart
        if (intent == null) {
            Log.i(TAG, "Restart")

            // Recreate intent
            intent = Intent(this, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, if (enabled) Command.start else Command.stop)
        }
        if ((ACTION_HOUSE_HOLDING == intent.action)) intent.putExtra(EXTRA_COMMAND, Command.householding)
        if ((ACTION_WATCHDOG == intent.action)) intent.putExtra(EXTRA_COMMAND, Command.watchdog)
        val cmd: Command? = intent.getSerializableExtra(EXTRA_COMMAND) as Command?
        if (cmd == null) intent.putExtra(EXTRA_COMMAND, if (enabled) Command.start else Command.stop)
        val reason: String? = intent.getStringExtra(EXTRA_REASON)
        Log.i(TAG, ("Start intent=" + intent + " command=" + cmd + " reason=" + reason +
                " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000)))
        commandHandler!!.queue(intent)
        return START_STICKY
    }

    private fun set(intent: Intent) {
        // Get arguments
        val uid: Int = intent.getIntExtra(EXTRA_UID, 0)
        val network: String? = intent.getStringExtra(EXTRA_NETWORK)
        val pkg: String? = intent.getStringExtra(EXTRA_PACKAGE)
        val blocked: Boolean = intent.getBooleanExtra(EXTRA_BLOCKED, false)
        Log.i(TAG, "Set $pkg $network=$blocked")

        // Get defaults
        val settings: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        val default_wifi: Boolean = settings.getBoolean("whitelist_wifi", true)
        val default_other: Boolean = settings.getBoolean("whitelist_other", true)

        // Update setting
        val prefs: SharedPreferences = getSharedPreferences(network, MODE_PRIVATE)
        if (blocked == (if (("wifi" == network)) default_wifi else default_other)) prefs.edit().remove(pkg).apply() else prefs.edit().putBoolean(pkg, blocked).apply()

        // Apply rules
        reload("notification", this@ServiceSinkhole, false)

        // Update notification
        notifyNewApplication(uid)

        // Update UI
        val ruleset: Intent = Intent(ActivityMain.ACTION_RULES_CHANGED)
        LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
    }

    override fun onRevoke() {
        Log.i(TAG, "Revoke")

        // Disable firewall (will result in stop command)
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("enabled", false).apply()

        // Feedback
        showDisabledNotification()
        WidgetMain.updateWidgets(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        synchronized(this) {
            Log.i(TAG, "Destroy")
            commandLooper!!.quit()
            logLooper!!.quit()
            statsLooper!!.quit()
            for (command: Command in Command.values()) commandHandler!!.removeMessages(command.ordinal)
            releaseLock()

            // Registered in command loop
            if (registeredInteractiveState) {
                unregisterReceiver(interactiveStateReceiver)
                registeredInteractiveState = false
            }
            if (callStateListener != null) {
                val tm: TelephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE)
                callStateListener = null
            }

            // Register in onCreate
            if (registeredUser) {
                unregisterReceiver(userReceiver)
                registeredUser = false
            }
            if (registeredIdleState) {
                unregisterReceiver(idleStateReceiver)
                registeredIdleState = false
            }
            if (registeredPackageChanged) {
                unregisterReceiver(packageChangedReceiver)
                registeredPackageChanged = false
            }
            if (networkCallback != null) {
                unlistenNetworkChanges()
                networkCallback = null
            }
            if (registeredConnectivityChanged) {
                unregisterReceiver(connectivityChangedReceiver)
                registeredConnectivityChanged = false
            }
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkMonitorCallback)
            if (phone_state) {
                val tm: TelephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                phone_state = false
            }
            try {
                if (vpn != null) {
                    stopNative()
                    stopVPN(vpn!!)
                    vpn = null
                    unprepare()
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            Log.i(TAG, "Destroy context=$jni_context")
            synchronized(jni_lock) {
                jni_done(jni_context)
                jni_context = 0
            }
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
        super.onDestroy()
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun unlistenNetworkChanges() {
        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback((networkCallback as NetworkCallback?)!!)
    }

    private fun getEnforcingNotification(allowed: Int, blocked: Int, hosts: Int): Notification? {
        var allowed: Int = allowed
        var blocked: Int = blocked
        var hosts: Int = hosts
        val main = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "foreground")
        builder.setSmallIcon(if (isLockedDown(last_metered)) R.drawable.ic_lock_outline_white_24dp else R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(getString(R.string.msg_started)) else builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_started))
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).priority = NotificationCompat.PRIORITY_MIN
        if (allowed >= 0) last_allowed = allowed else allowed = last_allowed
        if (blocked >= 0) last_blocked = blocked else blocked = last_blocked
        if (hosts >= 0) last_hosts = hosts else hosts = last_hosts
        return return if ((allowed >= 0) || (blocked >= 0) || (hosts >= 0)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (Util.isPlayStoreInstall(this)) builder.setContentText(getString(R.string.msg_packages, allowed, blocked)) else builder.setContentText(getString(R.string.msg_hosts, allowed, blocked, hosts))
                builder.build()
            } else {
                val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
                notification.bigText(getString(R.string.msg_started))
                if (Util.isPlayStoreInstall(this)) notification.setSummaryText(getString(R.string.msg_packages, allowed, blocked)) else notification.setSummaryText(getString(R.string.msg_hosts, allowed, blocked, hosts))
                notification.build()
            }
        } else builder.build()
    }

    private fun updateEnforcingNotification(allowed: Int, total: Int) {
        // Update notification
        val notification: Notification? = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size)
        val nm: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ENFORCING, notification)
    }

    private val waitingNotification: Notification
        get() {
            val main = Intent(this, ActivityMain::class.java)
            val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "foreground")
            builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setOngoing(true)
                    .setAutoCancel(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(getString(R.string.msg_waiting)) else builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_waiting))
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET).priority = NotificationCompat.PRIORITY_MIN
            return builder.build()
        }

    private fun showDisabledNotification() {
        val main = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_revoked))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_revoked))
        NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, (notification.build())!!)
    }

    private fun showAutoStartNotification() {
        val main = Intent(this, ActivityMain::class.java)
        main.putExtra(ActivityMain.EXTRA_APPROVE, true)
        val pi: PendingIntent = PendingIntent.getActivity(this, NOTIFY_AUTOSTART, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_autostart))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_autostart))
        NotificationManagerCompat.from(this).notify(NOTIFY_AUTOSTART, (notification.build())!!)
    }

    private fun showErrorNotification(message: String) {
        val main = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_error, message))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_error, message))
        notification.setSummaryText(message)
        NotificationManagerCompat.from(this).notify(NOTIFY_ERROR, (notification.build())!!)
    }

    private fun showAccessNotification(uid: Int) {
        val name: String = TextUtils.join(", ", Util.getApplicationNames(uid, this@ServiceSinkhole))
        val main = Intent(this@ServiceSinkhole, ActivityMain::class.java)
        main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
        val pi: PendingIntent = PendingIntent.getActivity(this@ServiceSinkhole, uid + 10000, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOn, tv, true)
        val colorOn: Int = tv.data
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val colorOff: Int = tv.data
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "access")
        builder.setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
                .setGroup("AccessAttempt")
                .setContentIntent(pi)
                .setColor(colorOff)
                .setOngoing(false)
                .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(name)
                .setContentText(getString(R.string.msg_access_n)) else builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_access, name))
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val df: DateFormat = SimpleDateFormat("dd HH:mm")
        val notification: NotificationCompat.InboxStyle = NotificationCompat.InboxStyle(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) notification.addLine(getString(R.string.msg_access_n)) else {
            val sname: String = getString(R.string.msg_access, name)
            val pos: Int = sname.indexOf(name)
            val sp: Spannable = SpannableString(sname)
            sp.setSpan(StyleSpan(Typeface.BOLD), pos, pos + name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            notification.addLine(sp)
        }
        var since: Long = 0
        val pm: PackageManager = packageManager
        val packages: Array<String>? = pm.getPackagesForUid(uid)
        if (packages != null && packages.isNotEmpty()) try {
            since = pm.getPackageInfo(packages[0], 0).firstInstallTime
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        DatabaseHelper.getInstance(this@ServiceSinkhole)!!.getAccessUnset(uid, 7, since).use { cursor ->
            val colDAddr: Int = cursor.getColumnIndex("daddr")
            val colTime: Int = cursor.getColumnIndex("time")
            val colAllowed: Int = cursor.getColumnIndex("allowed")
            while (cursor.moveToNext()) {
                val sb: StringBuilder = StringBuilder()
                sb.append(df.format(cursor.getLong(colTime))).append(' ')
                var daddr: String = cursor.getString(colDAddr)
                if (Util.isNumericAddress(daddr)) try {
                    daddr = InetAddress.getByName(daddr).hostName
                } catch (ignored: UnknownHostException) {
                }
                sb.append(daddr)
                val allowed: Int = cursor.getInt(colAllowed)
                if (allowed >= 0) {
                    val pos: Int = sb.indexOf(daddr)
                    val sp: Spannable = SpannableString(sb)
                    val fgsp = ForegroundColorSpan(if (allowed > 0) colorOn else colorOff)
                    sp.setSpan(fgsp, pos, pos + daddr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    notification.addLine(sp)
                } else notification.addLine(sb)
            }
        }
        NotificationManagerCompat.from(this).notify(uid + 10000, (notification.build())!!)
    }

    private fun showUpdateNotification(name: String, url: String) {
        val download = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(name)
                .setContentText(getString(R.string.msg_update))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, builder.build())
    }

    private fun removeWarningNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_DISABLED)
        NotificationManagerCompat.from(this).cancel(NOTIFY_AUTOSTART)
        NotificationManagerCompat.from(this).cancel(NOTIFY_ERROR)
    }

    private inner class Builder : VpnService.Builder() {
        private val networkInfo: NetworkInfo?
        private var mtu: Int = 0
        private val listAddress: MutableList<String> = ArrayList()
        private val listRoute: MutableList<String> = ArrayList()
        private val listDns: MutableList<InetAddress> = ArrayList()
        private val listDisallowed: MutableList<String> = ArrayList()
        override fun setMtu(mtu: Int): VpnService.Builder {
            this.mtu = mtu
            super.setMtu(mtu)
            return this
        }

        override fun addAddress(address: String, prefixLength: Int): Builder {
            listAddress.add("$address/$prefixLength")
            super.addAddress(address, prefixLength)
            return this
        }

        override fun addRoute(address: String, prefixLength: Int): Builder {
            listRoute.add("$address/$prefixLength")
            super.addRoute(address, prefixLength)
            return this
        }

        override fun addRoute(address: InetAddress, prefixLength: Int): Builder {
            listRoute.add(address.hostAddress + "/" + prefixLength)
            super.addRoute(address, prefixLength)
            return this
        }

        override fun addDnsServer(address: InetAddress): Builder {
            listDns.add(address)
            super.addDnsServer(address)
            return this
        }

        @Throws(PackageManager.NameNotFoundException::class)
        override fun addDisallowedApplication(packageName: String): Builder {
            listDisallowed.add(packageName)
            super.addDisallowedApplication(packageName)
            return this
        }

        override fun equals(obj: Any?): Boolean {
            val other: Builder = obj as Builder? ?: return false
            if ((networkInfo == null) || (other.networkInfo == null) || (
                            networkInfo.type != other.networkInfo.type)) return false
            if (mtu != other.mtu) return false
            if (listAddress.size != other.listAddress.size) return false
            if (listRoute.size != other.listRoute.size) return false
            if (listDns.size != other.listDns.size) return false
            if (listDisallowed.size != other.listDisallowed.size) return false
            for (address: String in listAddress) if (!other.listAddress.contains(address)) return false
            for (route: String in listRoute) if (!other.listRoute.contains(route)) return false
            for (dns: InetAddress in listDns) if (!other.listDns.contains(dns)) return false
            for (pkg: String in listDisallowed) if (!other.listDisallowed.contains(pkg)) return false
            return true
        }

        init {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkInfo = cm.activeNetworkInfo
        }
    }

    private inner class IPKey constructor(var version: Int, var protocol: Int, dport: Int,
                                          var uid: Int
    ) {
        var dport: Int = (if (protocol == 6 || protocol == 17) dport else 0)
        override fun equals(obj: Any?): Boolean {
            if (obj !is IPKey) return false
            val other: IPKey = obj
            return ((version == other.version) && (
                    protocol == other.protocol) && (
                    dport == other.dport) && (
                    uid == other.uid))
        }

        override fun hashCode(): Int {
            return (version shl 40) or (protocol shl 32) or (dport shl 16) or uid
        }

        override fun toString(): String {
            return "v$version p$protocol port=$dport uid=$uid"
        }

        init {
            // Only TCP (6) and UDP (17) have port numbers
        }
    }

    private inner class IPRule constructor(private val key: IPKey, private val name: String, val isBlocked: Boolean, private var expires: Long) {
        val isExpired: Boolean
            get() {
                return System.currentTimeMillis() > expires
            }

        fun updateExpires(expires: Long) {
            this.expires = this.expires.coerceAtLeast(expires)
        }

        override fun equals(obj: Any?): Boolean {
            val other: IPRule? = obj as IPRule?
            return (isBlocked == other!!.isBlocked && expires == other.expires)
        }

        override fun toString(): String {
            return "$key $name"
        }
    }

    companion object {
        private const val MAX_QUEUE: Int = 250
        private const val TAG: String = "NetGuard.Service"
        private val jni_lock: Any = Any()
        private var jni_context: Long = 0
        private const val NOTIFY_ENFORCING: Int = 1
        private const val NOTIFY_WAITING: Int = 2
        private const val NOTIFY_DISABLED: Int = 3
        private const val NOTIFY_AUTOSTART: Int = 4
        private const val NOTIFY_ERROR: Int = 5
        private const val NOTIFY_TRAFFIC: Int = 6
        private const val NOTIFY_UPDATE: Int = 7
        const val NOTIFY_EXTERNAL: Int = 8
        const val NOTIFY_DOWNLOAD: Int = 9
        const val EXTRA_COMMAND: String = "Command"
        private const val EXTRA_REASON: String = "Reason"
        const val EXTRA_NETWORK: String = "Network"
        const val EXTRA_UID: String = "UID"
        const val EXTRA_PACKAGE: String = "Package"
        const val EXTRA_BLOCKED: String = "Blocked"
        const val EXTRA_INTERACTIVE: String = "Interactive"
        const val EXTRA_TEMPORARY: String = "Temporary"
        private const val MSG_STATS_START: Int = 1
        private const val MSG_STATS_STOP: Int = 2
        private const val MSG_STATS_UPDATE: Int = 3
        private const val MSG_PACKET: Int = 4
        private const val MSG_USAGE: Int = 5

        @Volatile
        private var wlInstance: WakeLock? = null
        private const val ACTION_HOUSE_HOLDING: String = "eu.faircode.netguard.HOUSE_HOLDING"
        private const val ACTION_SCREEN_OFF_DELAYED: String = "eu.faircode.netguard.SCREEN_OFF_DELAYED"
        private const val ACTION_WATCHDOG: String = "eu.faircode.netguard.WATCHDOG"
        private external fun jni_pcap(name: String?, record_size: Int, file_size: Int)
        fun setPcap(enabled: Boolean, context: Context) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            var record_size = 64
            try {
                var r: String? = prefs.getString("pcap_record_size", null)
                if (TextUtils.isEmpty(r)) r = "64"
                record_size = r!!.toInt()
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            var file_size: Int = 2 * 1024 * 1024
            try {
                var f: String? = prefs.getString("pcap_file_size", null)
                if (TextUtils.isEmpty(f)) f = "2"
                file_size = f!!.toInt() * 1024 * 1024
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            jni_pcap((if (enabled) File(context.getDir("data", MODE_PRIVATE), "netguard.pcap") else null)?.absolutePath, record_size, file_size)
        }

        @Synchronized
        private fun getLock(context: Context): WakeLock? {
            if (wlInstance == null) {
                val pm: PowerManager = context.getSystemService(POWER_SERVICE) as PowerManager
                wlInstance = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getString(R.string.app_name) + " wakelock")
                wlInstance.setReferenceCounted(true)
            }
            return wlInstance
        }

        @Synchronized
        private fun releaseLock() {
            if (wlInstance != null) {
                while (wlInstance!!.isHeld) wlInstance!!.release()
                wlInstance = null
            }
        }

        fun getDns(context: Context): List<InetAddress?> {
            val listDns: MutableList<InetAddress?> = ArrayList()
            val sysDns: List<String> = Util.getDefaultDNS(context)

            // Get custom DNS servers
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val ip6: Boolean = prefs.getBoolean("ip6", true)
            val filter: Boolean = prefs.getBoolean("filter", false)
            val vpnDns1: String? = prefs.getString("dns", null)
            val vpnDns2: String? = prefs.getString("dns2", null)
            Log.i(TAG, "DNS system=" + TextUtils.join(",", (sysDns)) + " config=" + vpnDns1 + "," + vpnDns2)
            if (vpnDns1 != null) try {
                val dns: InetAddress = InetAddress.getByName(vpnDns1)
                if (!(dns.isLoopbackAddress || dns.isAnyLocalAddress) &&
                        (ip6 || dns is Inet4Address)) listDns.add(dns)
            } catch (ignored: Throwable) {
            }
            if (vpnDns2 != null) try {
                val dns: InetAddress = InetAddress.getByName(vpnDns2)
                if (!(dns.isLoopbackAddress || dns.isAnyLocalAddress) &&
                        (ip6 || dns is Inet4Address)) listDns.add(dns)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            if (listDns.size == 2) return listDns
            for (def_dns: String? in sysDns) try {
                val ddns: InetAddress = InetAddress.getByName(def_dns)
                if ((!listDns.contains(ddns) &&
                                !(ddns.isLoopbackAddress || ddns.isAnyLocalAddress) &&
                                (ip6 || ddns is Inet4Address))) listDns.add(ddns)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }

            // Remove local DNS servers when not routing LAN
            val count: Int = listDns.size
            val lan: Boolean = prefs.getBoolean("lan", false)
            val use_hosts: Boolean = prefs.getBoolean("use_hosts", false)
            if (lan && use_hosts && filter) try {
                val subnets: MutableList<Pair<InetAddress, Int>> = ArrayList()
                subnets.add(Pair(InetAddress.getByName("10.0.0.0"), 8))
                subnets.add(Pair(InetAddress.getByName("172.16.0.0"), 12))
                subnets.add(Pair(InetAddress.getByName("192.168.0.0"), 16))
                for (subnet: Pair<InetAddress, Int> in subnets) {
                    val hostAddress: InetAddress = subnet.first
                    val host = BigInteger(1, hostAddress.address)
                    val prefix: Int = subnet.second
                    val mask: BigInteger = BigInteger.valueOf(-1).shiftLeft(hostAddress.address.size * 8 - prefix)
                    for (dns: InetAddress? in ArrayList(listDns)) if (hostAddress.address.size == dns!!.address.size) {
                        val ip = BigInteger(1, dns.address)
                        if ((host.and(mask) == ip.and(mask))) {
                            Log.i(TAG, "Local DNS server host=$hostAddress/$prefix dns=$dns")
                            listDns.remove(dns)
                        }
                    }
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }

            // Always set DNS servers
            if (listDns.size == 0 || listDns.size < count) try {
                listDns.add(InetAddress.getByName("8.8.8.8"))
                listDns.add(InetAddress.getByName("8.8.4.4"))
                if (ip6) {
                    listDns.add(InetAddress.getByName("2001:4860:4860::8888"))
                    listDns.add(InetAddress.getByName("2001:4860:4860::8844"))
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            Log.i(TAG, "Get DNS=" + TextUtils.join(",", listDns))
            return listDns
        }

        fun run(reason: String?, context: Context?) {
            val intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.run)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun start(reason: String?, context: Context?) {
            val intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.start)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun reload(reason: String?, context: Context?, interactive: Boolean) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean("enabled", false)) {
                val intent = Intent(context, ServiceSinkhole::class.java)
                intent.putExtra(EXTRA_COMMAND, Command.reload)
                intent.putExtra(EXTRA_REASON, reason)
                intent.putExtra(EXTRA_INTERACTIVE, interactive)
                ContextCompat.startForegroundService((context)!!, intent)
            }
        }

        fun stop(reason: String?, context: Context?, vpnonly: Boolean) {
            val intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.stop)
            intent.putExtra(EXTRA_REASON, reason)
            intent.putExtra(EXTRA_TEMPORARY, vpnonly)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun reloadStats(reason: String?, context: Context?) {
            val intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.stats)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }
    }
}