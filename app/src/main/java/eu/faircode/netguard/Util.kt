package eu.faircode.netguard

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.app.ApplicationErrorReport
import android.app.ApplicationErrorReport.CrashInfo
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.net.ConnectivityManagerCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and
import kotlin.math.roundToInt
import kotlin.system.exitProcess

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
*/   object Util {
    private const val TAG: String = "NetGuard.Util"

    // Roam like at home
    private val listEU: List<String> = listOf(
            "AT",  // Austria
            "BE",  // Belgium
            "BG",  // Bulgaria
            "HR",  // Croatia
            "CY",  // Cyprus
            "CZ",  // Czech Republic
            "DK",  // Denmark
            "EE",  // Estonia
            "FI",  // Finland
            "FR",  // France
            "DE",  // Germany
            "GR",  // Greece
            "HU",  // Hungary
            "IS",  // Iceland
            "IE",  // Ireland
            "IT",  // Italy
            "LV",  // Latvia
            "LI",  // Liechtenstein
            "LT",  // Lithuania
            "LU",  // Luxembourg
            "MT",  // Malta
            "NL",  // Netherlands
            "NO",  // Norway
            "PL",  // Poland
            "PT",  // Portugal
            "RO",  // Romania
            "SK",  // Slovakia
            "SI",  // Slovenia
            "ES",  // Spain
            "SE",  // Sweden
            "GB" // United Kingdom
    )

    private external fun jni_getprop(name: String): String?
    private external fun is_numeric_address(ip: String?): Boolean
    private external fun dump_memory_profile()
    fun getSelfVersionName(context: Context): String {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (ex: PackageManager.NameNotFoundException) {
            ex.toString()
        }
    }

    fun getSelfVersionCode(context: Context): Int {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionCode
        } catch (ex: PackageManager.NameNotFoundException) {
            -1
        }
    }

    fun isNetworkActive(context: Context): Boolean {
        val cm: ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        return (cm?.activeNetworkInfo != null)
    }

    fun isConnected(context: Context): Boolean {
        val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                ?: return false
        var ni: NetworkInfo? = cm.activeNetworkInfo
        if (ni != null && ni.isConnected) return true
        val networks: Array<Network> = cm.allNetworks
        for (network: Network? in networks) {
            ni = cm.getNetworkInfo(network)
            if ((ni != null) && (ni.type != ConnectivityManager.TYPE_VPN) && ni.isConnected) return true
        }
        return false
    }

    fun isWifiActive(context: Context): Boolean {
        val ni: NetworkInfo? = ((context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)?.activeNetworkInfo)
        return (ni != null && ni.type == ConnectivityManager.TYPE_WIFI)
    }

    fun isMeteredNetwork(context: Context): Boolean {
        val cm: ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        return (cm != null && ConnectivityManagerCompat.isActiveNetworkMetered(cm))
    }

    fun getWifiSSID(context: Context): String {
        val wm: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        return ((wm?.connectionInfo?.ssid) ?: "NULL")
    }

    fun getNetworkGeneration(context: Context): String? {
        val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ni: NetworkInfo? = cm.activeNetworkInfo
        return (if (ni != null && ni.type == ConnectivityManager.TYPE_MOBILE) getNetworkGeneration(ni.subtype) else null)
    }

    fun isRoaming(context: Context): Boolean {
        val ni: NetworkInfo? = ((context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)?.activeNetworkInfo)
        return (ni != null && ni.isRoaming)
    }

    fun isNational(context: Context): Boolean {
        return try {
            val tm: TelephonyManager? = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            ((tm != null) && (tm.simCountryIso != null) && (tm.simCountryIso == tm.networkCountryIso))
        } catch (ignored: Throwable) {
            false
        }
    }

    fun isEU(context: Context): Boolean {
        return try {
            val tm: TelephonyManager? = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            ((tm != null) && isEU(tm.simCountryIso) && isEU(tm.networkCountryIso))
        } catch (ignored: Throwable) {
            false
        }
    }

    fun isEU(country: String?): Boolean {
        return (country != null && listEU.contains(country.toUpperCase(Locale.ROOT)))
    }

    fun isPrivateDns(context: Context): Boolean {
        var dns_mode: String? = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        Log.i(TAG, "Private DNS mode=$dns_mode")
        if (dns_mode == null) dns_mode = "off"
        return ("off" != dns_mode)
    }

    private fun getNetworkGeneration(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
            else -> "?G"
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun hasPhoneStatePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) else true
    }

    fun getDefaultDNS(context: Context): List<String> {
        val listDns: MutableList<String> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val an: Network? = cm.activeNetwork
            if (an != null) {
                val lp: LinkProperties? = cm.getLinkProperties(an)
                if (lp != null) {
                    val dns: List<InetAddress> = lp.dnsServers
                    for (d: InetAddress in dns) {
                        Log.i(TAG, "DNS from LP: " + d.hostAddress)
                        listDns.add(d.hostAddress.split("%").toTypedArray()[0])
                    }
                }
            }
        } else {
            val dns1: String? = jni_getprop("net.dns1")
            val dns2: String? = jni_getprop("net.dns2")
            if (dns1 != null) listDns.add(dns1.split("%").toTypedArray()[0])
            if (dns2 != null) listDns.add(dns2.split("%").toTypedArray()[0])
        }
        return listDns
    }

    fun isNumericAddress(ip: String?): Boolean {
        return is_numeric_address(ip)
    }

    fun isInteractive(context: Context): Boolean {
        val pm: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
        return (pm != null && pm.isInteractive)
    }

    private fun isPackageInstalled(packageName: String?, context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo((packageName)!!, 0)
            true
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isSystem(packageName: String?, context: Context): Boolean {
        return try {
            val pm: PackageManager = context.packageManager
            val info: PackageInfo = pm.getPackageInfo((packageName)!!, 0)
            ((info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
            /*
            PackageInfo pkg = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            PackageInfo sys = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES);
            return (pkg != null && pkg.signatures != null && pkg.signatures.length > 0 &&
                    sys.signatures.length > 0 && sys.signatures[0].equals(pkg.signatures[0]));
            */
        } catch (ignore: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasInternet(packageName: String?, context: Context): Boolean {
        val pm: PackageManager = context.packageManager
        return (pm.checkPermission("android.permission.INTERNET", (packageName)!!) == PackageManager.PERMISSION_GRANTED)
    }

    fun hasInternet(uid: Int, context: Context): Boolean {
        val pm: PackageManager = context.packageManager
        val pkgs: Array<String>? = pm.getPackagesForUid(uid)
        if (pkgs != null) for (pkg: String? in pkgs) if (hasInternet(pkg, context)) return true
        return false
    }

    fun isEnabled(info: PackageInfo, context: Context): Boolean {
        var setting: Int
        try {
            val pm: PackageManager = context.packageManager
            setting = pm.getApplicationEnabledSetting(info.packageName)
        } catch (ex: IllegalArgumentException) {
            setting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        return if (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) info.applicationInfo.enabled else (setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    fun getApplicationNames(uid: Int, context: Context): List<String> {
        val listResult: MutableList<String> = ArrayList()
        when (uid) {
            0 -> listResult.add(context.getString(R.string.title_root))
            1013 -> listResult.add(context.getString(R.string.title_mediaserver))
            9999 -> listResult.add(context.getString(R.string.title_nobody))
            else -> {
                val pm: PackageManager = context.packageManager
                val pkgs: Array<String>? = pm.getPackagesForUid(uid)
                if (pkgs == null) listResult.add(uid.toString()) else for (pkg: String? in pkgs) try {
                    val info: ApplicationInfo = pm.getApplicationInfo((pkg)!!, 0)
                    listResult.add(pm.getApplicationLabel(info).toString())
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
                listResult.sort()
            }
        }
        return listResult
    }

    fun canFilter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true

        // https://android-review.googlesource.com/#/c/206710/1/untrusted_app.te
        val tcp = File("/proc/net/tcp")
        val tcp6 = File("/proc/net/tcp6")
        try {
            if (tcp.exists() && tcp.canRead()) return true
        } catch (ignored: SecurityException) {
        }
        return try {
            (tcp6.exists() && tcp6.canRead())
        } catch (ignored: SecurityException) {
            false
        }
    }

    fun isDebuggable(context: Context): Boolean {
        return ((context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    fun isPlayStoreInstall(context: Context): Boolean {
        if (BuildConfig.PLAY_STORE_RELEASE) return true
        return try {
            ("com.android.vending" == context.packageManager.getInstallerPackageName(context.packageName))
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            false
        }
    }

    fun hasXposed(): Boolean {
        return false
        for (ste: StackTraceElement in Thread.currentThread().stackTrace) if (ste.className.startsWith("de.robv.android.xposed")) return true
        return false
    }

    fun ownFault(context: Context, ex: Throwable?): Boolean {
        var ex: Throwable? = ex
        if (ex is OutOfMemoryError) return false
        if (ex!!.cause != null) ex = ex.cause
        for (ste: StackTraceElement in ex!!.stackTrace) if (ste.className.startsWith(context.packageName)) return true
        return false
    }

    @SuppressLint("PackageManagerGetSignatures")
    fun getFingerprint(context: Context): String? {
        return try {
            val pm: PackageManager = context.packageManager
            val pkg: String = context.packageName
            val info: PackageInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            val cert: ByteArray = info.signatures[0].toByteArray()
            val digest: MessageDigest = MessageDigest.getInstance("SHA1")
            val bytes: ByteArray = digest.digest(cert)
            val sb: StringBuilder = StringBuilder()
            for (b: Byte in bytes) sb.append((b and 0xff.toByte()).toString(16).toLowerCase(Locale.ROOT))
            sb.toString()
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            null
        }
    }

    fun hasValidFingerprint(context: Context): Boolean {
        val calculated: String? = getFingerprint(context)
        val expected: String = context.getString(R.string.fingerprint)
        return (calculated != null && (calculated == expected))
    }

    fun setTheme(context: Context) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val dark: Boolean = prefs.getBoolean("dark_theme", false)
        val theme: String? = prefs.getString("theme", "teal")
        if ((theme == "teal")) context.setTheme(if (dark) R.style.AppThemeTealDark else R.style.AppThemeTeal) else if ((theme == "blue")) context.setTheme(if (dark) R.style.AppThemeBlueDark else R.style.AppThemeBlue) else if ((theme == "purple")) context.setTheme(if (dark) R.style.AppThemePurpleDark else R.style.AppThemePurple) else if ((theme == "amber")) context.setTheme(if (dark) R.style.AppThemeAmberDark else R.style.AppThemeAmber) else if ((theme == "orange")) context.setTheme(if (dark) R.style.AppThemeOrangeDark else R.style.AppThemeOrange) else if ((theme == "green")) context.setTheme(if (dark) R.style.AppThemeGreenDark else R.style.AppThemeGreen)
        setTaskColor(context)
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setTaskColor(context: Context) {
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        (context as Activity).setTaskDescription(TaskDescription(null, null, tv.data))
    }

    fun dips2pixels(dips: Int, context: Context): Int {
        return (dips * context.resources.displayMetrics.density + 0.5f).roundToInt()
    }

    private fun calculateInSampleSize(
            options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height: Int = options.outHeight
        val width: Int = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    fun getProtocolName(protocol: Int, version: Int, brief: Boolean): String {
        // https://en.wikipedia.org/wiki/List_of_IP_protocol_numbers
        var p: String? = null
        var b: String? = null
        when (protocol) {
            0 -> {
                p = "HOPO"
                b = "H"
            }
            2 -> {
                p = "IGMP"
                b = "G"
            }
            1, 58 -> {
                p = "ICMP"
                b = "I"
            }
            6 -> {
                p = "TCP"
                b = "T"
            }
            17 -> {
                p = "UDP"
                b = "U"
            }
            50 -> {
                p = "ESP"
                b = "E"
            }
        }
        if (p == null) return "$protocol/$version"
        return ((if (brief) b else p) + (if (version > 0) version else ""))
    }

    fun areYouSure(context: Context?, explanation: Int, listener: DoubtListener) {
        val inflater: LayoutInflater = LayoutInflater.from(context)
        val view: View = inflater.inflate(R.layout.sure, null, false)
        val tvExplanation: TextView = view.findViewById(R.id.tvExplanation)
        tvExplanation.setText(explanation)
        AlertDialog.Builder((context)!!)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes) { _, _ -> listener.onSure() }
                .setNegativeButton(android.R.string.no) { _, _ ->
                    // Do nothing
                }
                .create().show()
    }

    private val mapIPOrganization: MutableMap<String, String?> = HashMap()
    @Throws(Exception::class)
    fun getOrganization(ip: String): String? {
        synchronized(mapIPOrganization) { if (mapIPOrganization.containsKey(ip)) return mapIPOrganization[ip] }
        var reader: BufferedReader? = null
        try {
            val url = URL("https://ipinfo.io/$ip/org")
            val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.readTimeout = 15 * 1000
            connection.connect()
            reader = BufferedReader(InputStreamReader(connection.inputStream))
            var organization: String? = reader.readLine()
            if (("undefined" == organization)) organization = null
            synchronized(mapIPOrganization) { mapIPOrganization.put(ip, organization) }
            return organization
        } finally {
            reader?.close()
        }
    }

    @Throws(NoSuchAlgorithmException::class, UnsupportedEncodingException::class)
    fun md5(text: String, salt: String): String {
        // MD5
        val bytes: ByteArray = MessageDigest.getInstance("MD5").digest((text + salt).toByteArray(charset("UTF-8")))
        val sb: StringBuilder = StringBuilder()
        for (b: Byte in bytes) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    fun logExtras(intent: Intent?) {
        if (intent != null) logBundle(intent.extras)
    }

    fun logBundle(data: Bundle?) {
        if (data != null) {
            val keys: Set<String> = data.keySet()
            val stringBuilder: StringBuilder = StringBuilder()
            for (key: String? in keys) {
                val value: Any? = data.get(key)
                stringBuilder.append(key)
                        .append("=")
                        .append(value)
                        .append(if (value == null) "" else " (" + value.javaClass.simpleName + ")")
                        .append("\r\n")
            }
            Log.d(TAG, stringBuilder.toString())
        }
    }

    fun sendCrashReport(ex: Throwable, context: Context) {
        if (!isPlayStoreInstall(context) || isDebuggable(context)) return
        try {
            val report = ApplicationErrorReport()
            report.processName = context.packageName
            report.packageName = report.processName
            report.time = System.currentTimeMillis()
            report.type = ApplicationErrorReport.TYPE_CRASH
            report.systemApp = false
            val crash = CrashInfo()
            crash.exceptionClassName = ex.javaClass.simpleName
            crash.exceptionMessage = ex.message
            val writer = StringWriter()
            val printer = PrintWriter(writer)
            ex.printStackTrace(printer)
            crash.stackTrace = writer.toString()
            val stack: StackTraceElement = ex.stackTrace[0]
            crash.throwClassName = stack.className
            crash.throwFileName = stack.fileName
            crash.throwLineNumber = stack.lineNumber
            crash.throwMethodName = stack.methodName
            report.crashInfo = crash
            val bug = Intent(Intent.ACTION_APP_ERROR)
            bug.putExtra(Intent.EXTRA_BUG_REPORT, report)
            bug.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (bug.resolveActivity(context.packageManager) != null) context.startActivity(bug)
        } catch (exex: Throwable) {
            Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex))
        }
    }

    fun getGeneralInfo(context: Context): String {
        val sb: StringBuilder = StringBuilder()
        val tm: TelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        sb.append(String.format("Interactive %B\r\n", isInteractive(context)))
        sb.append(String.format("Connected %B\r\n", isConnected(context)))
        sb.append(String.format("WiFi %B\r\n", isWifiActive(context)))
        sb.append(String.format("Metered %B\r\n", isMeteredNetwork(context)))
        sb.append(String.format("Roaming %B\r\n", isRoaming(context)))
        if (tm.simState == TelephonyManager.SIM_STATE_READY) sb.append(String.format("SIM %s/%s/%s\r\n", tm.simCountryIso, tm.simOperatorName, tm.simOperator))
        //if (tm.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN)
        try {
            sb.append(String.format("Network %s/%s/%s\r\n", tm.networkCountryIso, tm.networkOperatorName, tm.networkOperator))
        } catch (ex: Throwable) {
            /*
                06-14 13:02:41.331 19703 19703 W ircode.netguar: Accessing hidden method Landroid/view/View;->computeFitSystemWindows(Landroid/graphics/Rect;Landroid/graphics/Rect;)Z (greylist, reflection, allowed)
                06-14 13:02:41.332 19703 19703 W ircode.netguar: Accessing hidden method Landroid/view/ViewGroup;->makeOptionalFitsSystemWindows()V (greylist, reflection, allowed)
                06-14 13:02:41.495 19703 19703 I TetheringManager: registerTetheringEventCallback:eu.faircode.netguard
                06-14 13:02:41.518 19703 19703 E AndroidRuntime: Process: eu.faircode.netguard, PID: 19703
                06-14 13:02:41.518 19703 19703 E AndroidRuntime:        at eu.faircode.netguard.Util.getGeneralInfo(SourceFile:744)
                06-14 13:02:41.518 19703 19703 E AndroidRuntime:        at eu.faircode.netguard.ActivitySettings.updateTechnicalInfo(SourceFile:858)
                06-14 13:02:41.518 19703 19703 E AndroidRuntime:        at eu.faircode.netguard.ActivitySettings.onPostCreate(SourceFile:425)
                06-14 13:02:41.520 19703 19703 W NetGuard.App: java.lang.SecurityException: getDataNetworkTypeForSubscriber
                06-14 13:02:41.520 19703 19703 W NetGuard.App: java.lang.SecurityException: getDataNetworkTypeForSubscriber
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.os.Parcel.createExceptionOrNull(Parcel.java:2373)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.os.Parcel.createException(Parcel.java:2357)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.os.Parcel.readException(Parcel.java:2340)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.os.Parcel.readException(Parcel.java:2282)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at com.android.internal.telephony.ITelephony$Stub$Proxy.getNetworkTypeForSubscriber(ITelephony.java:8711)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.telephony.TelephonyManager.getNetworkType(TelephonyManager.java:2945)
                06-14 13:02:41.520 19703 19703 W NetGuard.App:  at android.telephony.TelephonyManager.getNetworkType(TelephonyManager.java:2909)
             */
        }
        val pm: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.append(String.format("Power saving %B\r\n", pm.isPowerSaveMode))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sb.append(String.format("Battery optimizing %B\r\n", batteryOptimizing(context)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) sb.append(String.format("Data saving %B\r\n", dataSaving(context)))
        if (sb.length > 2) sb.setLength(sb.length - 2)
        return sb.toString()
    }

    fun getNetworkInfo(context: Context): String {
        val sb: StringBuilder = StringBuilder()
        val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ani: NetworkInfo? = cm.activeNetworkInfo
        val listNI: MutableList<NetworkInfo> = ArrayList()
        for (network: Network? in cm.allNetworks) {
            val ni: NetworkInfo? = cm.getNetworkInfo(network)
            if (ni != null) listNI.add(ni)
        }
        for (ni: NetworkInfo in listNI) {
            sb.append(ni.typeName).append('/').append(ni.subtypeName)
                    .append(' ').append(ni.detailedState)
                    .append(if (TextUtils.isEmpty(ni.extraInfo)) "" else " " + ni.extraInfo)
                    .append(if (ni.type == ConnectivityManager.TYPE_MOBILE) " " + getNetworkGeneration(ni.subtype) else "")
                    .append(if (ni.isRoaming) " R" else "")
                    .append(if ((ani != null) && (ni.type == ani.type) && (ni.subtype == ani.subtype)) " *" else "")
                    .append("\r\n")
        }
        try {
            val nis: Enumeration<NetworkInterface>? = NetworkInterface.getNetworkInterfaces()
            if (nis != null) while (nis.hasMoreElements()) {
                val ni: NetworkInterface? = nis.nextElement()
                if (ni != null && !ni.isLoopback) {
                    val ias: List<InterfaceAddress>? = ni.interfaceAddresses
                    if (ias != null) for (ia: InterfaceAddress in ias) sb.append(ni.name)
                            .append(' ').append(ia.address.hostAddress)
                            .append('/').append(ia.networkPrefixLength.toInt())
                            .append(' ').append(ni.mtu)
                            .append(' ').append(if (ni.isUp) '^' else 'v')
                            .append("\r\n")
                }
            }
        } catch (ex: Throwable) {
            sb.append(ex.toString()).append("\r\n")
        }
        if (sb.length > 2) sb.setLength(sb.length - 2)
        return sb.toString()
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun batteryOptimizing(context: Context): Boolean {
        val pm: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @TargetApi(Build.VERSION_CODES.N)
    fun dataSaving(context: Context): Boolean {
        val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }

    fun sendLogcat(uri: Uri?, context: Context) {
        GlobalScope.launch {
            withContext(Dispatchers.Default){
                val sb: StringBuilder = StringBuilder()
                sb.append(context.getString(R.string.msg_issue))
                sb.append("\r\n\r\n\r\n\r\n")

                // Get version info
                val version: String = getSelfVersionName(context)
                sb.append(String.format("NetGuard: %s/%d\r\n", version, getSelfVersionCode(context)))
                sb.append(String.format("Android: %s (SDK %d)\r\n", Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                sb.append("\r\n")

                // Get device info
                sb.append(String.format("Brand: %s\r\n", Build.BRAND))
                sb.append(String.format("Manufacturer: %s\r\n", Build.MANUFACTURER))
                sb.append(String.format("Model: %s\r\n", Build.MODEL))
                sb.append(String.format("Product: %s\r\n", Build.PRODUCT))
                sb.append(String.format("Device: %s\r\n", Build.DEVICE))
                sb.append(String.format("Host: %s\r\n", Build.HOST))
                sb.append(String.format("Display: %s\r\n", Build.DISPLAY))
                sb.append(String.format("Id: %s\r\n", Build.ID))
                sb.append(String.format("Fingerprint: %B\r\n", hasValidFingerprint(context)))
                val abi: String = (if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "?")
                sb.append(String.format("ABI: %s\r\n", abi))
                val rt: Runtime = Runtime.getRuntime()
                val hused: Long = (rt.totalMemory() - rt.freeMemory()) / 1024L
                val hmax: Long = rt.maxMemory() / 1024L
                val nheap: Long = Debug.getNativeHeapAllocatedSize() / 1024L
                val nf: NumberFormat = NumberFormat.getIntegerInstance()
                sb.append(String.format("Heap usage: %s/%s KiB native: %s KiB\r\n",
                        nf.format(hused), nf.format(hmax), nf.format(nheap)))
                sb.append("\r\n")
                sb.append(String.format("VPN dialogs: %B\r\n", isPackageInstalled("com.android.vpndialogs", context)))
                try {
                    sb.append(String.format("Prepared: %B\r\n", VpnService.prepare(context) == null))
                } catch (ex: Throwable) {
                    sb.append("Prepared: ").append((ex.toString())).append("\r\n").append(Log.getStackTraceString(ex))
                }
                sb.append("\r\n")
                sb.append(getGeneralInfo(context))
                sb.append("\r\n\r\n")
                sb.append(getNetworkInfo(context))
                sb.append("\r\n\r\n")

                // Get DNS
                sb.append("DNS system:\r\n")
                for (dns: String? in getDefaultDNS(context)) sb.append("- ").append(dns).append("\r\n")
                sb.append("DNS VPN:\r\n")
                for (dns: InetAddress? in ServiceSinkhole.getDns(context)) sb.append("- ").append(dns).append("\r\n")
                sb.append("\r\n")

                // Get TCP connection info
                var line: String?
                var `in`: BufferedReader
                try {
                    sb.append("/proc/net/tcp:\r\n")
                    `in` = BufferedReader(FileReader("/proc/net/tcp"))
                    while ((`in`.readLine().also { line = it }) != null) sb.append(line).append("\r\n")
                    `in`.close()
                    sb.append("\r\n")
                    sb.append("/proc/net/tcp6:\r\n")
                    `in` = BufferedReader(FileReader("/proc/net/tcp6"))
                    while ((`in`.readLine().also { line = it }) != null) sb.append(line).append("\r\n")
                    `in`.close()
                    sb.append("\r\n")
                } catch (ex: IOException) {
                    sb.append(ex.toString()).append("\r\n")
                }

                // Get settings
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val all: Map<String, *> = prefs.all
                for (key: String in all.keys) sb.append("Setting: ").append(key).append('=').append(all[key]).append("\r\n")
                sb.append("\r\n")

                // Write logcat
                dump_memory_profile()
                var out: OutputStream? = null
                try {
                    Log.i(TAG, "Writing logcat URI=$uri")
                    out = context.contentResolver.openOutputStream((uri)!!)
                    out!!.write(logcat.toString().toByteArray())
                    out.write(getTrafficLog(context).toString().toByteArray())
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    sb.append(ex.toString()).append("\r\n").append(Log.getStackTraceString(ex)).append("\r\n")
                } finally {
                    if (out != null) try {
                        out.close()
                    } catch (ignored: IOException) {
                    }
                }

                // Build intent
                val sendEmail = Intent(Intent.ACTION_SEND)
                sendEmail.type = "message/rfc822"
                sendEmail.putExtra(Intent.EXTRA_EMAIL, arrayOf("marcel+netguard@faircode.eu"))
                sendEmail.putExtra(Intent.EXTRA_SUBJECT, "NetGuard $version logcat")
                sendEmail.putExtra(Intent.EXTRA_TEXT, sb.toString())
                sendEmail.putExtra(Intent.EXTRA_STREAM, uri)
                withContext(Dispatchers.Main){
                    if (sendEmail != null) try {
                        context.startActivity(sendEmail)
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }
        }
    }

    private fun getTrafficLog(context: Context): StringBuilder {
        val sb: StringBuilder = StringBuilder()
        DatabaseHelper.getInstance(context).getLog(udp = true, tcp = true, other = true, allowed = true, blocked = true).use { cursor ->
            val colTime: Int = cursor.getColumnIndex("time")
            val colVersion: Int = cursor.getColumnIndex("version")
            val colProtocol: Int = cursor.getColumnIndex("protocol")
            val colFlags: Int = cursor.getColumnIndex("flags")
            val colSAddr: Int = cursor.getColumnIndex("saddr")
            val colSPort: Int = cursor.getColumnIndex("sport")
            val colDAddr: Int = cursor.getColumnIndex("daddr")
            val colDPort: Int = cursor.getColumnIndex("dport")
            val colDName: Int = cursor.getColumnIndex("dname")
            val colUid: Int = cursor.getColumnIndex("uid")
            val colData: Int = cursor.getColumnIndex("data")
            val colAllowed: Int = cursor.getColumnIndex("allowed")
            val colConnection: Int = cursor.getColumnIndex("connection")
            val colInteractive: Int = cursor.getColumnIndex("interactive")
            val format: DateFormat = SimpleDateFormat.getDateTimeInstance()
            var count = 0
            while (cursor.moveToNext() && ++count < 250) {
                sb.append(format.format(cursor.getLong(colTime)))
                sb.append(" v").append(cursor.getInt(colVersion))
                sb.append(" p").append(cursor.getInt(colProtocol))
                sb.append(' ').append(cursor.getString(colFlags))
                sb.append(' ').append(cursor.getString(colSAddr))
                sb.append('/').append(cursor.getInt(colSPort))
                sb.append(" > ").append(cursor.getString(colDAddr))
                sb.append('/').append(cursor.getString(colDName))
                sb.append('/').append(cursor.getInt(colDPort))
                sb.append(" u").append(cursor.getInt(colUid))
                sb.append(" a").append(cursor.getInt(colAllowed))
                sb.append(" c").append(cursor.getInt(colConnection))
                sb.append(" i").append(cursor.getInt(colInteractive))
                sb.append(' ').append(cursor.getString(colData))
                sb.append("\r\n")
            }
        }
        return sb
    }

    private val logcat: StringBuilder
        get() {
            val builder: StringBuilder = StringBuilder()
            var  process1: java.lang.Process? = null
            val process2: java.lang.Process? = null
            var br: BufferedReader? = null
            try {
                val command1: Array<String> = arrayOf("logcat", "-d", "-v", "threadtime")
                process1 = Runtime.getRuntime().exec(command1)
                br = BufferedReader(InputStreamReader(process1.inputStream))
                var count = 0
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    count++
                    builder.append(line).append("\r\n")
                }
                Log.i(TAG, "Logcat lines=$count")
            } catch (ex: IOException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                if (br != null) try {
                    br.close()
                } catch (ignored: IOException) {
                }
                if (process2 != null) try {
                    process2.destroy()
                } catch (ex: Throwable) {
                    Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
                if (process1 != null) try {
                    process1.destroy()
                } catch (ex: Throwable) {
                    Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }
            return builder
        }

    interface DoubtListener {
        fun onSure()
    }

    init {
        try {
            System.loadLibrary("netguard")
        } catch (ignored: UnsatisfiedLinkError) {
            exitProcess(1)
        }
    }
}