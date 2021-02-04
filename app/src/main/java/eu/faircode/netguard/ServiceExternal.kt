package eu.faircode.netguard

import android.app.*
import android.content.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
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
*/   class ServiceExternal  // am startservice -a eu.faircode.netguard.DOWNLOAD_HOSTS_FILE
constructor() : IntentService(TAG) {
    override fun onHandleIntent(intent: Intent?) {
        try {
            startForeground(ServiceSinkhole.Companion.NOTIFY_EXTERNAL, getForegroundNotification(this))
            Log.i(TAG, "Received " + intent)
            Util.logExtras(intent)
            if ((ACTION_DOWNLOAD_HOSTS_FILE == intent!!.getAction())) {
                val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                var hosts_url: String? = prefs.getString("hosts_url", null)
                if (("https://www.netguard.me/hosts" == hosts_url)) hosts_url = BuildConfig.HOSTS_FILE_URI
                val tmp: File = File(getFilesDir(), "hosts.tmp")
                val hosts: File = File(getFilesDir(), "hosts.txt")
                var `in`: InputStream? = null
                var out: OutputStream? = null
                var connection: URLConnection? = null
                try {
                    val url: URL = URL(hosts_url)
                    connection = url.openConnection()
                    connection.connect()
                    if (connection is HttpURLConnection) {
                        val httpConnection: HttpURLConnection = connection
                        if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK) throw IOException(httpConnection.getResponseCode().toString() + " " + httpConnection.getResponseMessage())
                    }
                    val contentLength: Int = connection.getContentLength()
                    Log.i(TAG, "Content length=" + contentLength)
                    `in` = connection.getInputStream()
                    out = FileOutputStream(tmp)
                    var size: Long = 0
                    val buffer: ByteArray = ByteArray(4096)
                    var bytes: Int
                    while ((`in`.read(buffer).also({ bytes = it })) != -1) {
                        out.write(buffer, 0, bytes)
                        size += bytes.toLong()
                    }
                    Log.i(TAG, "Downloaded size=" + size)
                    if (hosts.exists()) hosts.delete()
                    tmp.renameTo(hosts)
                    val last: String = SimpleDateFormat.getDateTimeInstance().format(Date().getTime())
                    prefs.edit().putString("hosts_last_download", last).apply()
                    ServiceSinkhole.Companion.reload("hosts file download", this, false)
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    if (tmp.exists()) tmp.delete()
                } finally {
                    try {
                        if (out != null) out.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    try {
                        if (`in` != null) `in`.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    if (connection is HttpURLConnection) connection.disconnect()
                }
            }
        } finally {
            stopForeground(true)
        }
    }

    companion object {
        private val TAG: String = "NetGuard.External"
        private val ACTION_DOWNLOAD_HOSTS_FILE: String = "eu.faircode.netguard.DOWNLOAD_HOSTS_FILE"
        private fun getForegroundNotification(context: Context): Notification {
            val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, "foreground")
            builder.setSmallIcon(R.drawable.ic_hourglass_empty_white_24dp)
            builder.setPriority(NotificationCompat.PRIORITY_MIN)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            builder.setContentTitle(context.getString(R.string.app_name))
            return builder.build()
        }
    }
}