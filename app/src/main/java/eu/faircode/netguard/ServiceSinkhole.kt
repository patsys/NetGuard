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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.faircode.netguard.IPUtil.CIDR
import eu.faircode.netguard.ServiceSinkhole
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
*/   class ServiceSinkhole constructor() : VpnService(), OnSharedPreferenceChangeListener {
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
            val ruleset: Intent = Intent(ActivityMain.Companion.ACTION_QUEUE_CHANGED)
            ruleset.putExtra(ActivityMain.Companion.EXTRA_SIZE, queue)
            LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
        }

        fun queue(intent: Intent) {
            synchronized(this, {
                queue++
                reportQueueSize()
            })
            val cmd: Command? = intent.getSerializableExtra(EXTRA_COMMAND) as Command?
            val msg: Message = commandHandler!!.obtainMessage()
            msg.obj = intent
            msg.what = cmd!!.ordinal
            commandHandler!!.sendMessage(msg)
        }

        public override fun handleMessage(msg: Message) {
            try {
                synchronized(this@ServiceSinkhole, { handleIntent(msg.obj as Intent) })
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                synchronized(this, {
                    queue--
                    reportQueueSize()
                })
                try {
                    val wl: WakeLock? = getLock(this@ServiceSinkhole)
                    if (wl!!.isHeld()) wl.release() else Log.w(TAG, "Wakelock under-locked")
                    Log.i(TAG, "Messages=" + hasMessages(0) + " wakelock=" + wlInstance!!.isHeld())
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }
        }

        private fun handleIntent(intent: Intent) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val cmd: Command? = intent.getSerializableExtra(EXTRA_COMMAND) as Command?
            val reason: String? = intent.getStringExtra(EXTRA_REASON)
            Log.i(TAG, ("Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000)))

            // Check if foreground
            if (cmd != Command.stop) if (!user_foreground) {
                Log.i(TAG, "Command " + cmd + " ignored for background user")
                return
            }

            // Handle temporary stop
            if (cmd == Command.stop) temporarilyStopped = intent.getBooleanExtra(EXTRA_TEMPORARY, false) else if (cmd == Command.start) temporarilyStopped = false else if (cmd == Command.reload && temporarilyStopped) {
                // Prevent network/interactive changes from restarting the VPN
                Log.i(TAG, "Command " + cmd + " ignored because of temporary stop")
                return
            }

            // Optionally listen for interactive state changes
            if (prefs.getBoolean("screen_on", true)) {
                if (!registeredInteractiveState) {
                    Log.i(TAG, "Starting listening for interactive state changes")
                    last_interactive = Util.isInteractive(this@ServiceSinkhole)
                    val ifInteractive: IntentFilter = IntentFilter()
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
                        public override fun onCallStateChanged(state: Int, incomingNumber: String) {
                            Log.i(TAG, "New call state=" + state)
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
                val watchdogIntent: Intent = Intent(this@ServiceSinkhole, ServiceSinkhole::class.java)
                watchdogIntent.setAction(ACTION_WATCHDOG)
                val pi: PendingIntent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pi = PendingIntent.getForegroundService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT) else pi = PendingIntent.getService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                val am: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                am.cancel(pi)
                if (cmd != Command.stop) {
                    val watchdog: Int = prefs.getString("watchdog", "0")!!.toInt()
                    if (watchdog > 0) {
                        Log.i(TAG, "Watchdog " + watchdog + " minutes")
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
                    Command.householding -> householding(intent)
                    Command.watchdog -> watchdog(intent)
                    else -> Log.e(TAG, "Unknown command=" + cmd)
                }
                if ((cmd == Command.start) || (cmd == Command.reload) || (cmd == Command.stop)) {
                    // Update main view
                    val ruleset: Intent = Intent(ActivityMain.Companion.ACTION_RULES_CHANGED)
                    ruleset.putExtra(ActivityMain.Companion.EXTRA_CONNECTED, if (cmd == Command.stop) false else last_connected)
                    ruleset.putExtra(ActivityMain.Companion.EXTRA_METERED, if (cmd == Command.stop) false else last_metered)
                    LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)

                    // Update widgets
                    WidgetMain.Companion.updateWidgets(this@ServiceSinkhole)
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
                        Log.w(TAG, "VPN prepared connected=" + last_connected)
                        if (last_connected && !(ex is StartFailedException)) {
                            //showAutoStartNotification();
                            if (!Util.isPlayStoreInstall(this@ServiceSinkhole)) showErrorNotification(ex.toString())
                        }
                        // Retried on connectivity change
                    } else {
                        showErrorNotification(ex.toString())

                        // Disable firewall
                        if (!(ex is StartFailedException)) {
                            prefs.edit().putBoolean("enabled", false).apply()
                            WidgetMain.Companion.updateWidgets(this@ServiceSinkhole)
                        }
                    }
                } else showErrorNotification(ex.toString())
            }
        }

        private fun start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString())
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=" + state.toString())
                val listRule: List<Rule?> = Rule.Companion.getRules(true, this@ServiceSinkhole)
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
            val listRule: List<Rule?> = Rule.Companion.getRules(true, this@ServiceSinkhole)

            // Check if rules needs to be reloaded
            if (interactive) {
                var process: Boolean = false
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
                    Log.d(TAG, "Stop foreground state=" + state.toString())
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=" + state.toString())
            }
            val listAllowed: List<Rule?> = getAllowedRules(listRule)
            val builder: Builder = getBuilder(listAllowed, listRule)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                last_builder = builder
                Log.i(TAG, "Legacy restart")
                if (vpn != null) {
                    stopNative(vpn!!)
                    stopVPN(vpn!!)
                    vpn = null
                    try {
                        Thread.sleep(500)
                    } catch (ignored: InterruptedException) {
                    }
                }
                vpn = startVPN(last_builder)
            } else {
                if ((vpn != null) && prefs.getBoolean("filter", false) && (builder == last_builder)) {
                    Log.i(TAG, "Native restart")
                    stopNative(vpn!!)
                } else {
                    last_builder = builder
                    var handover: Boolean = prefs.getBoolean("handover", false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handover = false
                    Log.i(TAG, "VPN restart handover=" + handover)
                    if (handover) {
                        // Attempt seamless handover
                        var prev: ParcelFileDescriptor? = vpn
                        vpn = startVPN(builder)
                        if (prev != null && vpn == null) {
                            Log.w(TAG, "Handover failed")
                            stopNative(prev)
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
                            stopNative(prev)
                            stopVPN(prev)
                        }
                    } else {
                        if (vpn != null) {
                            stopNative(vpn!!)
                            stopVPN(vpn!!)
                        }
                        vpn = startVPN(builder)
                    }
                }
            }
            if (vpn == null) throw StartFailedException(getString((R.string.msg_start_failed)))
            startNative(vpn!!, listAllowed, listRule)
            removeWarningNotifications()
            updateEnforcingNotification(listAllowed.size, listRule.size)
        }

        private fun stop(temporary: Boolean) {
            if (vpn != null) {
                stopNative(vpn!!)
                stopVPN(vpn!!)
                vpn = null
                unprepare()
            }
            if (state == State.enforcing && !temporary) {
                Log.d(TAG, "Stop foreground state=" + state.toString())
                last_allowed = -1
                last_blocked = -1
                last_hosts = -1
                stopForeground(true)
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, waitingNotification)
                    state = State.waiting
                    Log.d(TAG, "Start foreground state=" + state.toString())
                } else {
                    state = State.none
                    stopSelf()
                }
            }
        }

        private fun householding(intent: Intent) {
            // Keep log records for three days
            DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)!!.cleanupLog(Date().getTime() - 3 * 24 * 3600 * 1000L)

            // Clear expired DNS records
            DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)!!.cleanupDns()

            // Check for update
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            if ((!Util.isPlayStoreInstall(this@ServiceSinkhole) &&
                            Util.hasValidFingerprint(this@ServiceSinkhole) &&
                            prefs.getBoolean("update_check", true))) checkUpdate()
        }

        private fun watchdog(intent: Intent) {
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
                val url: URL = URL(BuildConfig.GITHUB_LATEST_API)
                urlConnection = url.openConnection() as HttpsURLConnection?
                val br: BufferedReader = BufferedReader(InputStreamReader(urlConnection!!.getInputStream()))
                var line: String?
                while ((br.readLine().also({ line = it })) != null) json.append(line)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                if (urlConnection != null) urlConnection.disconnect()
            }
            try {
                val jroot: JSONObject = JSONObject(json.toString())
                if (jroot.has("tag_name") && jroot.has("html_url") && jroot.has("assets")) {
                    val url: String = jroot.getString("html_url")
                    val jassets: JSONArray = jroot.getJSONArray("assets")
                    if (jassets.length() > 0) {
                        val jasset: JSONObject = jassets.getJSONObject(0)
                        if (jasset.has("name")) {
                            val version: String = jroot.getString("tag_name")
                            val name: String = jasset.getString("name")
                            Log.i(TAG, "Tag " + version + " name " + name + " url " + url)
                            val current: Version = Version(Util.getSelfVersionName(this@ServiceSinkhole))
                            val available: Version = Version(version)
                            if (current.compareTo(available) < 0) {
                                Log.i(TAG, "Update available from " + current + " to " + available)
                                showUpdateNotification(name, url)
                            } else Log.i(TAG, "Up-to-date current version " + current)
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
            synchronized(this, {
                if (queue > Companion.MAX_QUEUE) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            })
        }

        fun account(usage: Usage?) {
            val msg: Message = obtainMessage()
            msg.obj = usage
            msg.what = MSG_USAGE
            synchronized(this, {
                if (queue > Companion.MAX_QUEUE) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            })
        }

        public override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_PACKET -> log(msg.obj as Packet, msg.arg1, msg.arg2 > 0)
                    MSG_USAGE -> usage(msg.obj as Usage)
                    else -> Log.e(TAG, "Unknown log message=" + msg.what)
                }
                synchronized(this, { queue-- })
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private fun log(packet: Packet, connection: Int, interactive: Boolean) {
            // Get settings
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val log: Boolean = prefs.getBoolean("log", false)
            val log_app: Boolean = prefs.getBoolean("log_app", false)
            val dh: DatabaseHelper? = DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)

            // Get real name
            val dname: String? = dh!!.getQName(packet.uid, packet.daddr)

            // Traffic log
            if (log) dh.insertLog(packet, dname, connection, interactive)

            // Application log
            if ((log_app && (packet.uid >= 0) &&
                            !((packet.uid == 0) && (packet.protocol == 6 || packet.protocol == 17) && (packet.dport == 53)))) {
                if (!(packet.protocol == 6 /* TCP */ || packet.protocol == 17 /* UDP */)) packet.dport = 0
                if (dh.updateAccess(packet, dname, -1)) {
                    lock.readLock().lock()
                    if (!mapNotify.containsKey(packet.uid) || (mapNotify.get(packet.uid))!!) showAccessNotification(packet.uid)
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
                    val dh: DatabaseHelper? = DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)
                    val dname: String? = dh!!.getQName(usage.Uid, usage.DAddr)
                    Log.i(TAG, "Usage account " + usage + " dname=" + dname)
                    dh.updateUsage(usage, dname)
                }
            }
        }

        companion object {
            private val MAX_QUEUE: Int = 250
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
        public override fun handleMessage(msg: Message) {
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
            Log.i(TAG, "Stats start enabled=" + enabled)
            if (enabled) {
                `when` = Date().getTime()
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
                Log.d(TAG, "Stop foreground state=" + state.toString())
                stopForeground(true)
                state = State.none
            } else NotificationManagerCompat.from(this@ServiceSinkhole).cancel(NOTIFY_TRAFFIC)
        }

        private fun updateStats() {
            val remoteViews: RemoteViews = RemoteViews(getPackageName(), R.layout.traffic)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val frequency: Long = prefs.getString("stats_frequency", "1000")!!.toLong()
            val samples: Long = prefs.getString("stats_samples", "90")!!.toLong()
            val filter: Boolean = prefs.getBoolean("filter", false)
            val show_top: Boolean = prefs.getBoolean("show_top", false)
            val loglevel: Int = prefs.getString("loglevel", Integer.toString(Log.WARN))!!.toInt()

            // Schedule next update
            sendEmptyMessageDelayed(MSG_STATS_UPDATE, frequency)
            val ct: Long = SystemClock.elapsedRealtime()

            // Cleanup
            while (gt.size > 0 && ct - gt.get(0) > samples * 1000) {
                gt.removeAt(0)
                gtx.removeAt(0)
                grx.removeAt(0)
            }

            // Calculate network speed
            var txsec: Float = 0f
            var rxsec: Float = 0f
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
                    for (ainfo: ApplicationInfo in getPackageManager().getInstalledApplications(0)) if (ainfo.uid != Process.myUid()) mapUidBytes.put(ainfo.uid, TrafficStats.getUidTxBytes(ainfo.uid) + TrafficStats.getUidRxBytes(ainfo.uid))
                } else if (t > 0) {
                    val mapSpeedUid: TreeMap<Float, Int> = TreeMap(object : Comparator<Float> {
                        public override fun compare(value: Float, other: Float): Int {
                            return -value.compareTo(other)
                        }
                    })
                    val dt: Float = (ct - t) / 1000f
                    for (uid: Int in mapUidBytes.keys) {
                        val bytes: Long = TrafficStats.getUidTxBytes(uid) + TrafficStats.getUidRxBytes(uid)
                        val speed: Float = (bytes - (mapUidBytes.get(uid))!!) / dt
                        if (speed > 0) {
                            mapSpeedUid.put(speed, uid)
                            mapUidBytes.put(uid, bytes)
                        }
                    }
                    val sb: StringBuilder = StringBuilder()
                    var i: Int = 0
                    for (speed: Float in mapSpeedUid.keys) {
                        if (i++ >= 3) break
                        if (speed < 1000 * 1000) sb.append(getString(R.string.msg_kbsec, speed / 1000)) else sb.append(getString(R.string.msg_mbsec, speed / 1000 / 1000))
                        sb.append(' ')
                        val apps: List<String?>? = Util.getApplicationNames((mapSpeedUid.get(speed))!!, this@ServiceSinkhole)
                        sb.append(if (apps!!.size > 0) apps.get(0) else "?")
                        sb.append("\r\n")
                    }
                    if (sb.length > 0) sb.setLength(sb.length - 2)
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
            val canvas: Canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            // Determine max
            var max: Float = 0f
            var xmax: Long = 0
            var ymax: Float = 0f
            for (i in gt.indices) {
                val t: Long = gt.get(i)
                val tx: Float = gtx.get(i)
                val rx: Float = grx.get(i)
                if (t > xmax) xmax = t
                if (tx > max) max = tx
                if (rx > max) max = rx
                if (tx > ymax) ymax = tx
                if (rx > ymax) ymax = rx
            }

            // Build paths
            val ptx: Path = Path()
            val prx: Path = Path()
            for (i in gtx.indices) {
                val x: Float = width - (width * (xmax - gt.get(i))) / 1000f / samples
                val ytx: Float = height - height * gtx.get(i) / ymax
                val yrx: Float = height - height * grx.get(i) / ymax
                if (i == 0) {
                    ptx.moveTo(x, ytx)
                    prx.moveTo(x, yrx)
                } else {
                    ptx.lineTo(x, ytx)
                    prx.lineTo(x, yrx)
                }
            }

            // Build paint
            val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.setStyle(Paint.Style.STROKE)

            // Draw scale line
            paint.setStrokeWidth(Util.dips2pixels(1, this@ServiceSinkhole).toFloat())
            paint.setColor(ContextCompat.getColor(this@ServiceSinkhole, R.color.colorGrayed))
            val y: Float = (height / 2).toFloat()
            canvas.drawLine(0f, y, width.toFloat(), y, paint)

            // Draw paths
            paint.setStrokeWidth(Util.dips2pixels(2, this@ServiceSinkhole).toFloat())
            paint.setColor(ContextCompat.getColor(this@ServiceSinkhole, R.color.colorSend))
            canvas.drawPath(ptx, paint)
            paint.setColor(ContextCompat.getColor(this@ServiceSinkhole, R.color.colorReceive))
            canvas.drawPath(prx, paint)

            // Update remote view
            remoteViews.setImageViewBitmap(R.id.ivTraffic, bitmap)
            if (txsec < 1000 * 1000) remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_kbsec, txsec / 1000)) else remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_mbsec, txsec / 1000 / 1000))
            if (rxsec < 1000 * 1000) remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_kbsec, rxsec / 1000)) else remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_mbsec, rxsec / 1000 / 1000))
            if (max < 1000 * 1000) remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_kbsec, max / 2 / 1000)) else remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_mbsec, max / 2 / 1000 / 1000))

            // Show session/file count
            if (filter && loglevel <= Log.WARN) {
                val count: IntArray = jni_get_stats(jni_context)
                remoteViews.setTextViewText(R.id.tvSessions, count.get(0).toString() + "/" + count.get(1) + "/" + count.get(2))
                remoteViews.setTextViewText(R.id.tvFiles, count.get(3).toString() + "/" + count.get(4))
            } else {
                remoteViews.setTextViewText(R.id.tvSessions, "")
                remoteViews.setTextViewText(R.id.tvFiles, "")
            }

            // Show notification
            val main: Intent = Intent(this@ServiceSinkhole, ActivityMain::class.java)
            val pi: PendingIntent = PendingIntent.getActivity(this@ServiceSinkhole, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv: TypedValue = TypedValue()
            getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this@ServiceSinkhole, "notify")
            builder.setWhen(`when`)
                    .setSmallIcon(R.drawable.ic_equalizer_white_24dp)
                    .setContent(remoteViews)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setOngoing(true)
                    .setAutoCancel(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            if (state == State.none || state == State.waiting) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString())
                    stopForeground(true)
                }
                startForeground(NOTIFY_TRAFFIC, builder.build())
                state = State.stats
                Log.d(TAG, "Start foreground state=" + state.toString())
            } else NotificationManagerCompat.from(this@ServiceSinkhole).notify(NOTIFY_TRAFFIC, builder.build())
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Throws(SecurityException::class)
    private fun startVPN(builder: Builder?): ParcelFileDescriptor? {
        try {
            val pfd: ParcelFileDescriptor? = builder!!.establish()

            // Set underlying network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm: ConnectivityManager? = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
                val active: Network? = (if (cm == null) null else cm.getActiveNetwork())
                if (active != null) {
                    Log.i(TAG, "Setting underlying network=" + cm!!.getNetworkInfo(active))
                    setUnderlyingNetworks(arrayOf(active))
                }
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
        val builder: Builder = Builder()
        builder.setSession(getString(R.string.app_name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(Util.isMeteredNetwork(this))

        // VPN address
        val vpn4: String? = prefs.getString("vpn4", "10.1.10.1")
        Log.i(TAG, "Using VPN4=" + vpn4)
        builder.addAddress((vpn4)!!, 32)
        if (ip6) {
            val vpn6: String? = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1")
            Log.i(TAG, "Using VPN6=" + vpn6)
            builder.addAddress((vpn6)!!, 128)
        }

        // DNS address
        if (filter) for (dns: InetAddress? in getDns(this@ServiceSinkhole)) {
            if (ip6 || dns is Inet4Address) {
                Log.i(TAG, "Using DNS=" + dns)
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
            val config: Configuration = getResources().getConfiguration()

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
                                    (config.mnc >= 270 && config.mnc <= 289) || (
                                    config.mnc == 390) ||
                                    (config.mnc >= 480 && config.mnc <= 489) || (
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
            Collections.sort(listExclude)
            try {
                var start: InetAddress? = InetAddress.getByName("0.0.0.0")
                for (exclude: CIDR in listExclude) {
                    Log.i(TAG, "Exclude " + exclude.getStart().getHostAddress() + "..." + exclude.getEnd().getHostAddress())
                    for (include: CIDR? in IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart()))) try {
                        builder.addRoute((include!!.address)!!, include.prefix)
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    start = IPUtil.plus1(exclude.getEnd())
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
        Log.i(TAG, "IPv6=" + ip6)
        if (ip6) builder.addRoute("2000::", 3) // unicast

        // MTU
        val mtu: Int = jni_get_mtu()
        Log.i(TAG, "MTU=" + mtu)
        builder.setMtu(mtu)

        // Add list of allowed applications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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
        }

        // Build configure intent
        val configure: Intent = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, configure, PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setConfigureIntent(pi)
        return builder
    }

    private fun startNative(vpn: ParcelFileDescriptor, listAllowed: List<Rule?>, listRule: List<Rule?>) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        val log: Boolean = prefs.getBoolean("log", false)
        val log_app: Boolean = prefs.getBoolean("log_app", false)
        val filter: Boolean = prefs.getBoolean("filter", false)
        Log.i(TAG, "Start native log=" + log + "/" + log_app + " filter=" + filter)

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
            val prio: Int = prefs.getString("loglevel", Integer.toString(Log.WARN))!!.toInt()
            val rcode: Int = prefs.getString("rcode", "3")!!.toInt()
            if (prefs.getBoolean("socks5_enabled", false)) jni_socks5(
                    prefs.getString("socks5_addr", ""), prefs.getString("socks5_port", "0")!!.toInt(),
                    prefs.getString("socks5_username", ""),
                    prefs.getString("socks5_password", "")) else jni_socks5("", 0, "", "")
            if (tunnelThread == null) {
                Log.i(TAG, "Starting tunnel thread context=" + jni_context)
                jni_start(jni_context, prio)
                tunnelThread = Thread(object : Runnable {
                    public override fun run() {
                        Log.i(TAG, "Running tunnel context=" + jni_context)
                        jni_run(jni_context, vpn.getFd(), mapForward.containsKey(53), rcode)
                        Log.i(TAG, "Tunnel exited")
                        tunnelThread = null
                    }
                })
                //tunnelThread.setPriority(Thread.MAX_PRIORITY);
                tunnelThread!!.start()
                Log.i(TAG, "Started tunnel thread")
            }
        }
    }

    private fun stopNative(vpn: ParcelFileDescriptor) {
        Log.i(TAG, "Stop native")
        if (tunnelThread != null) {
            Log.i(TAG, "Stopping tunnel thread")
            jni_stop(jni_context)
            var thread: Thread? = tunnelThread
            while (thread != null && thread.isAlive()) {
                try {
                    Log.i(TAG, "Joining tunnel thread context=" + jni_context)
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
        for (rule: Rule? in listAllowed) mapUidAllowed.put(rule!!.uid, true)
        mapUidKnown.clear()
        for (rule: Rule? in listRule) mapUidKnown.put(rule!!.uid, rule.uid)
        lock.writeLock().unlock()
    }

    private fun prepareHostsBlocked() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
        val use_hosts: Boolean = prefs.getBoolean("filter", false) && prefs.getBoolean("use_hosts", false)
        val hosts: File = File(getFilesDir(), "hosts.txt")
        if (!use_hosts || !hosts.exists() || !hosts.canRead()) {
            Log.i(TAG, "Hosts file use=" + use_hosts + " exists=" + hosts.exists())
            lock.writeLock().lock()
            mapHostsBlocked.clear()
            lock.writeLock().unlock()
            return
        }
        val changed: Boolean = (hosts.lastModified() != last_hosts_modified)
        if (!changed && mapHostsBlocked.size > 0) {
            Log.i(TAG, "Hosts file unchanged")
            return
        }
        last_hosts_modified = hosts.lastModified()
        lock.writeLock().lock()
        mapHostsBlocked.clear()
        var count: Int = 0
        var br: BufferedReader? = null
        try {
            br = BufferedReader(FileReader(hosts))
            var line: String
            while ((br.readLine().also({ line = it })) != null) {
                val hash: Int = line.indexOf('#')
                if (hash >= 0) line = line.substring(0, hash)
                line = line.trim({ it <= ' ' })
                if (line.length > 0) {
                    val words: Array<String> = line.split("\\s+").toTypedArray()
                    if (words.size == 2) {
                        count++
                        mapHostsBlocked.put(words.get(1), true)
                    } else Log.i(TAG, "Invalid hosts file line: " + line)
                }
            }
            mapHostsBlocked.put("test.netguard.me", true)
            Log.i(TAG, count.toString() + " hosts read")
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
            if (!IAB.Companion.isPurchased(ActivityPro.Companion.SKU_FILTER, this@ServiceSinkhole)) {
                lock.writeLock().unlock()
                return
            }
        }
        DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)!!.getAccessDns(dname).use({ cursor ->
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
                val time: Long = (if (cursor.isNull(colTime)) Date().getTime() else cursor.getLong(colTime))
                val ttl: Long = (if (cursor.isNull(colTTL)) 7 * 24 * 3600 * 1000L else cursor.getLong(colTTL))
                if (isLockedDown(last_metered)) {
                    val pkg: Array<String>? = getPackageManager().getPackagesForUid(uid)
                    if (pkg != null && pkg.size > 0) {
                        if (!lockdown.getBoolean(pkg.get(0), false)) continue
                    }
                }
                val key: IPKey = IPKey(version, protocol, dport, uid)
                synchronized(mapUidIPFilters, {
                    if (!mapUidIPFilters.containsKey(key)) mapUidIPFilters.put(key, HashMap<Any?, Any?>())
                    try {
                        val name: String = (if (dresource == null) daddr else dresource)
                        if (Util.isNumericAddress(name)) {
                            val iname: InetAddress = InetAddress.getByName(name)
                            if (version == 4 && !(iname is Inet4Address)) continue
                            if (version == 6 && !(iname is Inet6Address)) continue
                            val exists: Boolean = mapUidIPFilters.get(key)!!.containsKey(iname)
                            if (!exists || !mapUidIPFilters.get(key)!!.get(iname)!!.isBlocked) {
                                val rule: IPRule = IPRule(key, name + "/" + iname, block, time + ttl)
                                mapUidIPFilters.get(key)!!.put(iname, rule)
                                if (exists) Log.w(TAG, "Address conflict " + key + " " + daddr + "/" + dresource)
                            } else if (exists) {
                                mapUidIPFilters.get(key)!!.get(iname)!!.updateExpires(time + ttl)
                                if (dname != null && ttl > 60 * 1000L) Log.w(TAG, "Address updated " + key + " " + daddr + "/" + dresource)
                            } else {
                                if (dname != null) Log.i(TAG, "Ignored " + key + " " + daddr + "/" + dresource + "=" + block)
                            }
                        } else Log.w(TAG, "Address not numeric " + name)
                    } catch (ex: UnknownHostException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                })
            }
        })
        lock.writeLock().unlock()
    }

    private fun prepareForwarding() {
        lock.writeLock().lock()
        mapForward.clear()
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("filter", false)) {
            DatabaseHelper.Companion.getInstance(this@ServiceSinkhole).getForwarding().use({ cursor ->
                val colProtocol: Int = cursor.getColumnIndex("protocol")
                val colDPort: Int = cursor.getColumnIndex("dport")
                val colRAddr: Int = cursor.getColumnIndex("raddr")
                val colRPort: Int = cursor.getColumnIndex("rport")
                val colRUid: Int = cursor.getColumnIndex("ruid")
                while (cursor.moveToNext()) {
                    val fwd: Forward = Forward()
                    fwd.protocol = cursor.getInt(colProtocol)
                    fwd.dport = cursor.getInt(colDPort)
                    fwd.raddr = cursor.getString(colRAddr)
                    fwd.rport = cursor.getInt(colRPort)
                    fwd.ruid = cursor.getInt(colRUid)
                    mapForward.put(fwd.dport, fwd)
                    Log.i(TAG, "Forward " + fwd)
                }
            })
        }
        lock.writeLock().unlock()
    }

    private fun prepareNotify(listRule: List<Rule?>) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val notify: Boolean = prefs.getBoolean("notify_access", false)
        val system: Boolean = prefs.getBoolean("manage_system", false)
        lock.writeLock().lock()
        mapNotify.clear()
        for (rule: Rule? in listRule) mapNotify.put(rule!!.uid, notify && rule.notify && (system || !rule.system))
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
        val ssidNetwork: String? = Util.getWifiSSID(this)
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
        last_connected = eu.faircode.netguard.Util.isConnected(this@ServiceSinkhole)
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
        if (roaming && eu) roaming = !eu.faircode.netguard.Util.isEU(this)
        if (roaming && national) roaming = !eu.faircode.netguard.Util.isNational(this)
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
        Log.w(TAG, "Native exit reason=" + reason)
        if (reason != null) {
            showErrorNotification(reason)
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit().putBoolean("enabled", false).apply()
            WidgetMain.Companion.updateWidgets(this)
        }
    }

    // Called from native code
    private fun nativeError(error: Int, message: String) {
        Log.w(TAG, "Native error " + error + ": " + message)
        showErrorNotification(message)
    }

    // Called from native code
    private fun logPacket(packet: Packet) {
        logHandler!!.queue(packet)
    }

    // Called from native code
    private fun dnsResolved(rr: ResourceRecord) {
        if (DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)!!.insertDns(rr)) {
            Log.i(TAG, "New IP " + rr)
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
    private fun getUidQ(version: Int, protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) return Process.INVALID_UID
        val cm: ConnectivityManager? = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        if (cm == null) return Process.INVALID_UID
        val local: InetSocketAddress = InetSocketAddress(saddr, sport)
        val remote: InetSocketAddress = InetSocketAddress(daddr, dport)
        Log.i(TAG, "Get uid local=" + local + " remote=" + remote)
        val uid: Int = cm.getConnectionOwnerUid(protocol, local, remote)
        Log.i(TAG, "Get uid=" + uid)
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
        } else if (packet.protocol == 6 && !packet.flags!!.contains("S")) {
            packet.allowed = true
        } else {
            packet.allowed = false
        }
        //packet.allowed = false;
        if (packet.allowed == false) {
            if (prefs.getBoolean("filter", false)) {
                // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
                if (packet.protocol == 17 /* UDP */ && !prefs.getBoolean("filter_udp", false)) {
                    // Allow unfiltered UDP
                    packet.allowed = true
                    Log.i(TAG, "Allowing UDP " + packet)
                } else if (((packet.uid < 2000) &&
                                !last_connected && isSupported(packet.protocol) && false)) {
                    // Allow system applications in disconnected state
                    packet.allowed = true
                    Log.w(TAG, "Allowing disconnected system " + packet)
                } else if (((packet.uid < 2000) &&
                                !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol))) {
                    // Allow unknown system traffic
                    packet.allowed = true
                    Log.w(TAG, "Allowing unknown system " + packet)
                } else if (packet.uid == Process.myUid()) {
                    // Allow self
                    packet.allowed = true
                    Log.w(TAG, "Allowing self " + packet)
                } else {
                    var filtered: Boolean = false
                    val key: IPKey = IPKey(packet.version, packet.protocol, packet.dport, packet.uid)
                    if (mapUidIPFilters.containsKey(key)) try {
                        val iaddr: InetAddress = InetAddress.getByName(packet.daddr)
                        val map: Map<InetAddress?, IPRule?>? = mapUidIPFilters.get(key)
                        if (map != null && map.containsKey(iaddr)) {
                            val rule: IPRule? = map.get(iaddr)
                            if (rule!!.isExpired) Log.i(TAG, "DNS expired " + packet + " rule " + rule) else {
                                filtered = true
                                packet.allowed = !rule.isBlocked
                                Log.i(TAG, ("Filtering " + packet +
                                        " allowed=" + packet.allowed + " rule " + rule))
                            }
                        }
                    } catch (ex: UnknownHostException) {
                        Log.w(TAG, "Allowed " + ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    if (!filtered) if (mapUidAllowed.containsKey(packet.uid)) packet.allowed = (mapUidAllowed.get(packet.uid))!! else Log.w(TAG, "No rules for " + packet)
                }
            }
        }
        var allowed: Allowed? = null
        if (packet.allowed) {
            if (mapForward.containsKey(packet.dport)) {
                val fwd: Forward? = mapForward.get(packet.dport)
                if (fwd!!.ruid == packet.uid) {
                    allowed = Allowed()
                } else {
                    allowed = Allowed(fwd.raddr, fwd.rport)
                    packet.data = "> " + fwd.raddr + "/" + fwd.rport
                }
            } else allowed = Allowed()
        }
        lock.readLock().unlock()
        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", false)) if (packet.protocol != 6 /* TCP */ || !("" == packet.flags)) if (packet.uid != Process.myUid()) logPacket(packet)
        return allowed
    }

    // Called from native code
    private fun accountUsage(usage: Usage) {
        logHandler!!.account(usage)
    }

    private val interactiveStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        public override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            executor.submit(object : Runnable {
                public override fun run() {
                    val am: AlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
                    val i: Intent = Intent(ACTION_SCREEN_OFF_DELAYED)
                    i.setPackage(context.getPackageName())
                    val pi: PendingIntent = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT)
                    am.cancel(pi)
                    try {
                        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                        var delay: Int
                        try {
                            delay = prefs.getString("screen_delay", "0")!!.toInt()
                        } catch (ignored: NumberFormatException) {
                            delay = 0
                        }
                        val interactive: Boolean = (Intent.ACTION_SCREEN_ON == intent.getAction())
                        if (interactive || delay == 0) {
                            last_interactive = interactive
                            reload("interactive state changed", this@ServiceSinkhole, true)
                        } else {
                            if ((ACTION_SCREEN_OFF_DELAYED == intent.getAction())) {
                                last_interactive = interactive
                                reload("interactive state changed", this@ServiceSinkhole, true)
                            } else {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) am.set(AlarmManager.RTC_WAKEUP, Date().getTime() + delay * 60 * 1000L, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().getTime() + delay * 60 * 1000L, pi)
                            }
                        }

                        // Start/stop stats
                        statsHandler!!.sendEmptyMessage(
                                if (Util.isInteractive(this@ServiceSinkhole)) MSG_STATS_START else MSG_STATS_STOP)
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) am.set(AlarmManager.RTC_WAKEUP, Date().getTime() + 15 * 1000L, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Date().getTime() + 15 * 1000L, pi)
                    }
                }
            })
        }
    }
    private val userReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            user_foreground = (Intent.ACTION_USER_FOREGROUND == intent.getAction())
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
        public override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            val pm: PowerManager = context.getSystemService(POWER_SERVICE) as PowerManager
            Log.i(TAG, "device idle=" + pm.isDeviceIdleMode())

            // Reload rules when coming from idle mode
            if (!pm.isDeviceIdleMode()) reload("idle state changed", this@ServiceSinkhole, false)
        }
    }
    private val connectivityChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        public override fun onReceive(context: Context, intent: Intent) {
            // Filter VPN connectivity changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val networkType: Int = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY)
                if (networkType == ConnectivityManager.TYPE_VPN) return
            }

            // Reload rules
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            reload("connectivity changed", this@ServiceSinkhole, false)
        }
    }
    var networkMonitorCallback: NetworkCallback = object : NetworkCallback() {
        private val TAG: String = "NetGuard.Monitor"
        private val validated: MutableMap<Network, Long> = HashMap()

        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java
        public override fun onAvailable(network: Network) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            val capabilities: NetworkCapabilities? = cm.getNetworkCapabilities(network)
            Log.i(TAG, "Available network " + network + " " + ni)
            Log.i(TAG, "Capabilities=" + capabilities)
            checkConnectivity(network, ni, capabilities)
        }

        public override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "New capabilities network " + network + " " + ni)
            Log.i(TAG, "Capabilities=" + capabilities)
            checkConnectivity(network, ni, capabilities)
        }

        public override fun onLosing(network: Network, maxMsToLive: Int) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "Losing network " + network + " within " + maxMsToLive + " ms " + ni)
        }

        public override fun onLost(network: Network) {
            val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            Log.i(TAG, "Lost network " + network + " " + ni)
            synchronized(validated, { validated.remove(network) })
        }

        public override fun onUnavailable() {
            Log.i(TAG, "No networks available")
        }

        private fun checkConnectivity(network: Network, ni: NetworkInfo?, capabilities: NetworkCapabilities?) {
            if (((ni != null) && (capabilities != null) && (
                            ni.getDetailedState() != NetworkInfo.DetailedState.SUSPENDED) && (
                            ni.getDetailedState() != NetworkInfo.DetailedState.BLOCKED) && (
                            ni.getDetailedState() != NetworkInfo.DetailedState.DISCONNECTED) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) {
                synchronized(validated, {
                    if (validated.containsKey(network) &&
                            validated.get(network)!! + 20 * 1000 > Date().getTime()) {
                        Log.i(TAG, "Already validated " + network + " " + ni)
                        return
                    }
                })
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                val host: String? = prefs.getString("validate", "www.google.com")
                Log.i(TAG, "Validating " + network + " " + ni + " host=" + host)
                var socket: Socket? = null
                try {
                    socket = network.getSocketFactory().createSocket()
                    socket.connect(InetSocketAddress(host, 443), 10000)
                    Log.i(TAG, "Validated " + network + " " + ni + " host=" + host)
                    synchronized(validated, { validated.put(network, Date().getTime()) })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        cm.reportNetworkConnectivity(network, true)
                        Log.i(TAG, "Reported " + network + " " + ni)
                    }
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString())
                    Log.i(TAG, "No connectivity " + network + " " + ni)
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
        public override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                val current_generation: String? = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, "Data connected generation=" + current_generation)
                if (last_generation == null || !(last_generation == current_generation)) {
                    Log.i(TAG, "New network generation=" + current_generation)
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
        public override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            try {
                if ((Intent.ACTION_PACKAGE_ADDED == intent.getAction())) {
                    // Application added
                    Rule.Companion.clearCache(context)
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // Show notification
                        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                        if (IAB.Companion.isPurchased(ActivityPro.Companion.SKU_NOTIFY, context) && prefs.getBoolean("install", true)) {
                            val uid: Int = intent.getIntExtra(Intent.EXTRA_UID, -1)
                            notifyNewApplication(uid)
                        }
                    }
                    reload("package added", context, false)
                } else if ((Intent.ACTION_PACKAGE_REMOVED == intent.getAction())) {
                    // Application removed
                    Rule.Companion.clearCache(context)
                    if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                        // Remove settings
                        val packageName: String = intent.getData()!!.getSchemeSpecificPart()
                        Log.i(TAG, "Deleting settings package=" + packageName)
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
                            val dh: DatabaseHelper? = DatabaseHelper.Companion.getInstance(context)
                            dh!!.clearLog(uid)
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
            val pm: PackageManager = getPackageManager()
            val packages: Array<String>? = pm.getPackagesForUid(uid)
            if (packages == null || packages.size < 1) throw PackageManager.NameNotFoundException(Integer.toString(uid))
            val internet: Boolean = Util.hasInternet(uid, this)

            // Build notification
            val main: Intent = Intent(this, ActivityMain::class.java)
            main.putExtra(ActivityMain.Companion.EXTRA_REFRESH, true)
            main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, Integer.toString(uid))
            val pi: PendingIntent = PendingIntent.getActivity(this, uid, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv: TypedValue = TypedValue()
            getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
            builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(name)
                    .setContentText(getString(R.string.msg_installed_n)) else builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_installed, name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)

            // Get defaults
            val prefs_wifi: SharedPreferences = getSharedPreferences("wifi", MODE_PRIVATE)
            val prefs_other: SharedPreferences = getSharedPreferences("other", MODE_PRIVATE)
            val wifi: Boolean = prefs_wifi.getBoolean(packages.get(0), prefs.getBoolean("whitelist_wifi", true))
            val other: Boolean = prefs_other.getBoolean(packages.get(0), prefs.getBoolean("whitelist_other", true))

            // Build Wi-Fi action
            val riWifi: Intent = Intent(this, ServiceSinkhole::class.java)
            riWifi.putExtra(EXTRA_COMMAND, Command.set)
            riWifi.putExtra(EXTRA_NETWORK, "wifi")
            riWifi.putExtra(EXTRA_UID, uid)
            riWifi.putExtra(EXTRA_PACKAGE, packages.get(0))
            riWifi.putExtra(EXTRA_BLOCKED, !wifi)
            val piWifi: PendingIntent = PendingIntent.getService(this, uid, riWifi, PendingIntent.FLAG_UPDATE_CURRENT)
            val wAction: NotificationCompat.Action = NotificationCompat.Action.Builder(
                    if (wifi) R.drawable.wifi_on else R.drawable.wifi_off,
                    getString(if (wifi) R.string.title_allow_wifi else R.string.title_block_wifi),
                    piWifi
            ).build()
            builder.addAction(wAction)

            // Build mobile action
            val riOther: Intent = Intent(this, ServiceSinkhole::class.java)
            riOther.putExtra(EXTRA_COMMAND, Command.set)
            riOther.putExtra(EXTRA_NETWORK, "other")
            riOther.putExtra(EXTRA_UID, uid)
            riOther.putExtra(EXTRA_PACKAGE, packages.get(0))
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

    public override fun onCreate() {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this))
        startForeground(NOTIFY_WAITING, waitingNotification)
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (jni_context != 0L) {
            Log.w(TAG, "Create with context=" + jni_context)
            jni_stop(jni_context)
            synchronized(jni_lock, {
                jni_done(jni_context)
                jni_context = 0
            })
        }

        // Native init
        jni_context = jni_init(Build.VERSION.SDK_INT)
        Log.i(TAG, "Created context=" + jni_context)
        val pcap: Boolean = prefs.getBoolean("pcap", false)
        setPcap(pcap, this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        Util.setTheme(this)
        super.onCreate()
        val commandThread: HandlerThread = HandlerThread(getString(R.string.app_name) + " command", Process.THREAD_PRIORITY_FOREGROUND)
        val logThread: HandlerThread = HandlerThread(getString(R.string.app_name) + " log", Process.THREAD_PRIORITY_BACKGROUND)
        val statsThread: HandlerThread = HandlerThread(getString(R.string.app_name) + " stats", Process.THREAD_PRIORITY_BACKGROUND)
        commandThread.start()
        logThread.start()
        statsThread.start()
        commandLooper = commandThread.getLooper()
        logLooper = logThread.getLooper()
        statsLooper = statsThread.getLooper()
        commandHandler = CommandHandler(commandLooper)
        logHandler = LogHandler(logLooper)
        statsHandler = StatsHandler(statsLooper)

        // Listen for user switches
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val ifUser: IntentFilter = IntentFilter()
            ifUser.addAction(Intent.ACTION_USER_BACKGROUND)
            ifUser.addAction(Intent.ACTION_USER_FOREGROUND)
            registerReceiver(userReceiver, ifUser)
            registeredUser = true
        }

        // Listen for idle mode state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ifIdle: IntentFilter = IntentFilter()
            ifIdle.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            registerReceiver(idleStateReceiver, ifIdle)
            registeredIdleState = true
        }

        // Listen for added/removed applications
        val ifPackage: IntentFilter = IntentFilter()
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED)
        ifPackage.addAction(Intent.ACTION_PACKAGE_REMOVED)
        ifPackage.addDataScheme("package")
        registerReceiver(packageChangedReceiver, ifPackage)
        registeredPackageChanged = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) try {
            listenNetworkChanges()
        } catch (ex: Throwable) {
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            listenConnectivityChanges()
        } else listenConnectivityChanges()

        // Monitor networks
        val cm: ConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
                NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkMonitorCallback)

        // Setup house holding
        val alarmIntent: Intent = Intent(this, ServiceSinkhole::class.java)
        alarmIntent.setAction(ACTION_HOUSE_HOLDING)
        val pi: PendingIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pi = PendingIntent.getForegroundService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT) else pi = PendingIntent.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val am: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi)
    }

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
            public override fun onAvailable(network: Network) {
                Log.i(TAG, "Available network=" + network)
                last_connected = Util.isConnected(this@ServiceSinkhole)
                reload("network available", this@ServiceSinkhole, false)
            }

            public override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.i(TAG, "Changed properties=" + network + " props=" + linkProperties)

                // Make sure the right DNS servers are being used
                val dns: List<InetAddress?> = linkProperties.getDnsServers()
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !same(last_dns, dns) else prefs.getBoolean("reload_onconnectivity", false)) {
                    Log.i(TAG, ("Changed link properties=" + linkProperties +
                            "DNS cur=" + TextUtils.join(",", dns) +
                            "DNS prv=" + (if (last_dns == null) null else TextUtils.join(",", last_dns!!))))
                    last_dns = dns
                    reload("link properties changed", this@ServiceSinkhole, false)
                }
            }

            public override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.i(TAG, "Changed capabilities=" + network + " caps=" + networkCapabilities)
                val connected: Boolean = Util.isConnected(this@ServiceSinkhole)
                val unmetered: Boolean = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val generation: String? = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, ("Connected=" + connected + "/" + last_connected +
                        " unmetered=" + unmetered + "/" + last_unmetered +
                        " generation=" + generation + "/" + last_generation))
                if (last_connected != null && !(last_connected == connected)) reload("Connected state changed", this@ServiceSinkhole, false)
                if (last_unmetered != null && !(last_unmetered == unmetered)) reload("Unmetered state changed", this@ServiceSinkhole, false)
                if (last_generation != null && !(last_generation == generation)) {
                    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    if ((prefs.getBoolean("unmetered_2g", false) ||
                                    prefs.getBoolean("unmetered_3g", false) ||
                                    prefs.getBoolean("unmetered_4g", false))) reload("Generation changed", this@ServiceSinkhole, false)
                }
                last_connected = connected
                last_unmetered = unmetered
                last_generation = generation
            }

            public override fun onLost(network: Network) {
                Log.i(TAG, "Lost network=" + network)
                last_connected = Util.isConnected(this@ServiceSinkhole)
                reload("network lost", this@ServiceSinkhole, false)
            }

            fun same(last: List<InetAddress?>?, current: List<InetAddress?>?): Boolean {
                if (last == null || current == null) return false
                if (last == null || last.size != current.size) return false
                for (i in current.indices) if (!(last.get(i) == current.get(i))) return false
                return true
            }
        }
        cm.registerNetworkCallback(builder.build(), nc)
        networkCallback = nc
    }

    private fun listenConnectivityChanges() {
        // Listen for connectivity updates
        Log.i(TAG, "Starting listening to connectivity changes")
        val ifConnectivity: IntentFilter = IntentFilter()
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

    public override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String) {
        if (("theme" == name)) {
            Log.i(TAG, "Theme changed")
            Util.setTheme(this)
            if (state != State.none) {
                Log.d(TAG, "Stop foreground state=" + state.toString())
                stopForeground(true)
            }
            if (state == State.enforcing) startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1)) else if (state != State.none) startForeground(NOTIFY_WAITING, waitingNotification)
            Log.d(TAG, "Start foreground state=" + state.toString())
        }
    }

    public override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        var intent: Intent? = intent
        if (state == State.enforcing) startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1)) else startForeground(NOTIFY_WAITING, waitingNotification)
        Log.i(TAG, "Received " + intent)
        Util.logExtras(intent)

        // Check for set command
        if ((intent != null) && intent.hasExtra(EXTRA_COMMAND) && (
                        intent.getSerializableExtra(EXTRA_COMMAND) === Command.set)) {
            set(intent)
            return START_STICKY
        }

        // Keep awake
        getLock(this)!!.acquire()

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
        if ((ACTION_HOUSE_HOLDING == intent.getAction())) intent.putExtra(EXTRA_COMMAND, Command.householding)
        if ((ACTION_WATCHDOG == intent.getAction())) intent.putExtra(EXTRA_COMMAND, Command.watchdog)
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
        Log.i(TAG, "Set " + pkg + " " + network + "=" + blocked)

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
        val ruleset: Intent = Intent(ActivityMain.Companion.ACTION_RULES_CHANGED)
        LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
    }

    public override fun onRevoke() {
        Log.i(TAG, "Revoke")

        // Disable firewall (will result in stop command)
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("enabled", false).apply()

        // Feedback
        showDisabledNotification()
        WidgetMain.Companion.updateWidgets(this)
        super.onRevoke()
    }

    public override fun onDestroy() {
        synchronized(this, {
            Log.i(TAG, "Destroy")
            commandLooper!!.quit()
            logLooper!!.quit()
            statsLooper!!.quit()
            for (command: Command in Command.values()) commandHandler!!.removeMessages(command.ordinal)
            releaseLock(this)

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
                    stopNative(vpn!!)
                    stopVPN(vpn!!)
                    vpn = null
                    unprepare()
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            Log.i(TAG, "Destroy context=" + jni_context)
            synchronized(jni_lock, {
                jni_done(jni_context)
                jni_context = 0
            })
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        })
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
        val main: Intent = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "foreground")
        builder.setSmallIcon(if (isLockedDown(last_metered)) R.drawable.ic_lock_outline_white_24dp else R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(getString(R.string.msg_started)) else builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_started))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_MIN)
        if (allowed >= 0) last_allowed = allowed else allowed = last_allowed
        if (blocked >= 0) last_blocked = blocked else blocked = last_blocked
        if (hosts >= 0) last_hosts = hosts else hosts = last_hosts
        if ((allowed >= 0) || (blocked >= 0) || (hosts >= 0)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (Util.isPlayStoreInstall(this)) builder.setContentText(getString(R.string.msg_packages, allowed, blocked)) else builder.setContentText(getString(R.string.msg_hosts, allowed, blocked, hosts))
                return builder.build()
            } else {
                val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
                notification.bigText(getString(R.string.msg_started))
                if (Util.isPlayStoreInstall(this)) notification.setSummaryText(getString(R.string.msg_packages, allowed, blocked)) else notification.setSummaryText(getString(R.string.msg_hosts, allowed, blocked, hosts))
                return notification.build()
            }
        } else return builder.build()
    }

    private fun updateEnforcingNotification(allowed: Int, total: Int) {
        // Update notification
        val notification: Notification? = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size)
        val nm: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ENFORCING, notification)
    }

    private val waitingNotification: Notification
        private get() {
            val main: Intent = Intent(this, ActivityMain::class.java)
            val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
            val tv: TypedValue = TypedValue()
            getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "foreground")
            builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setOngoing(true)
                    .setAutoCancel(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setContentTitle(getString(R.string.msg_waiting)) else builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_waiting))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
            return builder.build()
        }

    private fun showDisabledNotification() {
        val main: Intent = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_revoked))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_revoked))
        NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, (notification.build())!!)
    }

    private fun showAutoStartNotification() {
        val main: Intent = Intent(this, ActivityMain::class.java)
        main.putExtra(ActivityMain.Companion.EXTRA_APPROVE, true)
        val pi: PendingIntent = PendingIntent.getActivity(this, NOTIFY_AUTOSTART, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_autostart))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_autostart))
        NotificationManagerCompat.from(this).notify(NOTIFY_AUTOSTART, (notification.build())!!)
    }

    private fun showErrorNotification(message: String) {
        val main: Intent = Intent(this, ActivityMain::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_error, message))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        val notification: NotificationCompat.BigTextStyle = NotificationCompat.BigTextStyle(builder)
        notification.bigText(getString(R.string.msg_error, message))
        notification.setSummaryText(message)
        NotificationManagerCompat.from(this).notify(NOTIFY_ERROR, (notification.build())!!)
    }

    private fun showAccessNotification(uid: Int) {
        val name: String = TextUtils.join(", ", Util.getApplicationNames(uid, this@ServiceSinkhole))
        val main: Intent = Intent(this@ServiceSinkhole, ActivityMain::class.java)
        main.putExtra(ActivityMain.Companion.EXTRA_SEARCH, Integer.toString(uid))
        val pi: PendingIntent = PendingIntent.getActivity(this@ServiceSinkhole, uid + 10000, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorOn, tv, true)
        val colorOn: Int = tv.data
        getTheme().resolveAttribute(R.attr.colorOff, tv, true)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
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
        val pm: PackageManager = getPackageManager()
        val packages: Array<String>? = pm.getPackagesForUid(uid)
        if (packages != null && packages.size > 0) try {
            since = pm.getPackageInfo(packages.get(0), 0).firstInstallTime
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        DatabaseHelper.Companion.getInstance(this@ServiceSinkhole)!!.getAccessUnset(uid, 7, since).use({ cursor ->
            val colDAddr: Int = cursor.getColumnIndex("daddr")
            val colTime: Int = cursor.getColumnIndex("time")
            val colAllowed: Int = cursor.getColumnIndex("allowed")
            while (cursor.moveToNext()) {
                val sb: StringBuilder = StringBuilder()
                sb.append(df.format(cursor.getLong(colTime))).append(' ')
                var daddr: String = cursor.getString(colDAddr)
                if (Util.isNumericAddress(daddr)) try {
                    daddr = InetAddress.getByName(daddr).getHostName()
                } catch (ignored: UnknownHostException) {
                }
                sb.append(daddr)
                val allowed: Int = cursor.getInt(colAllowed)
                if (allowed >= 0) {
                    val pos: Int = sb.indexOf(daddr)
                    val sp: Spannable = SpannableString(sb)
                    val fgsp: ForegroundColorSpan = ForegroundColorSpan(if (allowed > 0) colorOn else colorOff)
                    sp.setSpan(fgsp, pos, pos + daddr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    notification.addLine(sp)
                } else notification.addLine(sb)
            }
        })
        NotificationManagerCompat.from(this).notify(uid + 10000, (notification.build())!!)
    }

    private fun showUpdateNotification(name: String, url: String) {
        val download: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pi: PendingIntent = PendingIntent.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, "notify")
        builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(name)
                .setContentText(getString(R.string.msg_update))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, builder.build())
    }

    private fun removeWarningNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_DISABLED)
        NotificationManagerCompat.from(this).cancel(NOTIFY_AUTOSTART)
        NotificationManagerCompat.from(this).cancel(NOTIFY_ERROR)
    }

    private inner class Builder private constructor() : VpnService.Builder() {
        private val networkInfo: NetworkInfo?
        private var mtu: Int = 0
        private val listAddress: MutableList<String> = ArrayList()
        private val listRoute: MutableList<String> = ArrayList()
        private val listDns: MutableList<InetAddress> = ArrayList()
        private val listDisallowed: MutableList<String> = ArrayList()
        public override fun setMtu(mtu: Int): VpnService.Builder {
            this.mtu = mtu
            super.setMtu(mtu)
            return this
        }

        public override fun addAddress(address: String, prefixLength: Int): Builder {
            listAddress.add(address + "/" + prefixLength)
            super.addAddress(address, prefixLength)
            return this
        }

        public override fun addRoute(address: String, prefixLength: Int): Builder {
            listRoute.add(address + "/" + prefixLength)
            super.addRoute(address, prefixLength)
            return this
        }

        public override fun addRoute(address: InetAddress, prefixLength: Int): Builder {
            listRoute.add(address.getHostAddress() + "/" + prefixLength)
            super.addRoute(address, prefixLength)
            return this
        }

        public override fun addDnsServer(address: InetAddress): Builder {
            listDns.add(address)
            super.addDnsServer(address)
            return this
        }

        @Throws(PackageManager.NameNotFoundException::class)
        public override fun addDisallowedApplication(packageName: String): Builder {
            listDisallowed.add(packageName)
            super.addDisallowedApplication(packageName)
            return this
        }

        public override fun equals(obj: Any?): Boolean {
            val other: Builder? = obj as Builder?
            if (other == null) return false
            if ((networkInfo == null) || (other.networkInfo == null) || (
                            networkInfo.getType() != other.networkInfo.getType())) return false
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
            networkInfo = cm.getActiveNetworkInfo()
        }
    }

    private inner class IPKey constructor(var version: Int, var protocol: Int, dport: Int, uid: Int) {
        var dport: Int
        var uid: Int
        public override fun equals(obj: Any?): Boolean {
            if (!(obj is IPKey)) return false
            val other: IPKey = obj
            return ((version == other.version) && (
                    protocol == other.protocol) && (
                    dport == other.dport) && (
                    uid == other.uid))
        }

        public override fun hashCode(): Int {
            return (version shl 40) or (protocol shl 32) or (dport shl 16) or uid
        }

        public override fun toString(): String {
            return "v" + version + " p" + protocol + " port=" + dport + " uid=" + uid
        }

        init {
            // Only TCP (6) and UDP (17) have port numbers
            this.dport = (if (protocol == 6 || protocol == 17) dport else 0)
            this.uid = uid
        }
    }

    private inner class IPRule constructor(private val key: IPKey, private val name: String, val isBlocked: Boolean, private var expires: Long) {
        val isExpired: Boolean
            get() {
                return System.currentTimeMillis() > expires
            }

        fun updateExpires(expires: Long) {
            this.expires = Math.max(this.expires, expires)
        }

        public override fun equals(obj: Any?): Boolean {
            val other: IPRule? = obj as IPRule?
            return (isBlocked == other!!.isBlocked && expires == other.expires)
        }

        public override fun toString(): String {
            return key.toString() + " " + name
        }
    }

    companion object {
        private val TAG: String = "NetGuard.Service"
        private val jni_lock: Any = Any()
        private var jni_context: Long = 0
        private val NOTIFY_ENFORCING: Int = 1
        private val NOTIFY_WAITING: Int = 2
        private val NOTIFY_DISABLED: Int = 3
        private val NOTIFY_AUTOSTART: Int = 4
        private val NOTIFY_ERROR: Int = 5
        private val NOTIFY_TRAFFIC: Int = 6
        private val NOTIFY_UPDATE: Int = 7
        val NOTIFY_EXTERNAL: Int = 8
        val NOTIFY_DOWNLOAD: Int = 9
        val EXTRA_COMMAND: String = "Command"
        private val EXTRA_REASON: String = "Reason"
        val EXTRA_NETWORK: String = "Network"
        val EXTRA_UID: String = "UID"
        val EXTRA_PACKAGE: String = "Package"
        val EXTRA_BLOCKED: String = "Blocked"
        val EXTRA_INTERACTIVE: String = "Interactive"
        val EXTRA_TEMPORARY: String = "Temporary"
        private val MSG_STATS_START: Int = 1
        private val MSG_STATS_STOP: Int = 2
        private val MSG_STATS_UPDATE: Int = 3
        private val MSG_PACKET: Int = 4
        private val MSG_USAGE: Int = 5

        @Volatile
        private var wlInstance: WakeLock? = null
        private val ACTION_HOUSE_HOLDING: String = "eu.faircode.netguard.HOUSE_HOLDING"
        private val ACTION_SCREEN_OFF_DELAYED: String = "eu.faircode.netguard.SCREEN_OFF_DELAYED"
        private val ACTION_WATCHDOG: String = "eu.faircode.netguard.WATCHDOG"
        private external fun jni_pcap(name: String?, record_size: Int, file_size: Int)
        fun setPcap(enabled: Boolean, context: Context) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            var record_size: Int = 64
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
            val pcap: File? = (if (enabled) File(context.getDir("data", MODE_PRIVATE), "netguard.pcap") else null)
            jni_pcap(if (pcap == null) null else pcap.getAbsolutePath(), record_size, file_size)
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
        private fun releaseLock(context: Context) {
            if (wlInstance != null) {
                while (wlInstance!!.isHeld()) wlInstance!!.release()
                wlInstance = null
            }
        }

        fun getDns(context: Context): List<InetAddress?> {
            val listDns: MutableList<InetAddress?> = ArrayList()
            val sysDns: List<String?>? = Util.getDefaultDNS(context)

            // Get custom DNS servers
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val ip6: Boolean = prefs.getBoolean("ip6", true)
            val filter: Boolean = prefs.getBoolean("filter", false)
            val vpnDns1: String? = prefs.getString("dns", null)
            val vpnDns2: String? = prefs.getString("dns2", null)
            Log.i(TAG, "DNS system=" + TextUtils.join(",", (sysDns)!!) + " config=" + vpnDns1 + "," + vpnDns2)
            if (vpnDns1 != null) try {
                val dns: InetAddress = InetAddress.getByName(vpnDns1)
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (ip6 || dns is Inet4Address)) listDns.add(dns)
            } catch (ignored: Throwable) {
            }
            if (vpnDns2 != null) try {
                val dns: InetAddress = InetAddress.getByName(vpnDns2)
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (ip6 || dns is Inet4Address)) listDns.add(dns)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            if (listDns.size == 2) return listDns
            for (def_dns: String? in sysDns!!) try {
                val ddns: InetAddress = InetAddress.getByName(def_dns)
                if ((!listDns.contains(ddns) &&
                                !(ddns.isLoopbackAddress() || ddns.isAnyLocalAddress()) &&
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
                    val host: BigInteger = BigInteger(1, hostAddress.getAddress())
                    val prefix: Int = subnet.second
                    val mask: BigInteger = BigInteger.valueOf(-1).shiftLeft(hostAddress.getAddress().size * 8 - prefix)
                    for (dns: InetAddress? in ArrayList(listDns)) if (hostAddress.getAddress().size == dns!!.getAddress().size) {
                        val ip: BigInteger = BigInteger(1, dns.getAddress())
                        if ((host.and(mask) == ip.and(mask))) {
                            Log.i(TAG, "Local DNS server host=" + hostAddress + "/" + prefix + " dns=" + dns)
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
            val intent: Intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.run)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun start(reason: String?, context: Context?) {
            val intent: Intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.start)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun reload(reason: String?, context: Context?, interactive: Boolean) {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean("enabled", false)) {
                val intent: Intent = Intent(context, ServiceSinkhole::class.java)
                intent.putExtra(EXTRA_COMMAND, Command.reload)
                intent.putExtra(EXTRA_REASON, reason)
                intent.putExtra(EXTRA_INTERACTIVE, interactive)
                ContextCompat.startForegroundService((context)!!, intent)
            }
        }

        fun stop(reason: String?, context: Context?, vpnonly: Boolean) {
            val intent: Intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.stop)
            intent.putExtra(EXTRA_REASON, reason)
            intent.putExtra(EXTRA_TEMPORARY, vpnonly)
            ContextCompat.startForegroundService((context)!!, intent)
        }

        fun reloadStats(reason: String?, context: Context?) {
            val intent: Intent = Intent(context, ServiceSinkhole::class.java)
            intent.putExtra(EXTRA_COMMAND, Command.stats)
            intent.putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService((context)!!, intent)
        }
    }
}