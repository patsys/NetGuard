package eu.faircode.netguard

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.os.AsyncTask
import android.os.Build
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

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
*/   class DownloadTask constructor(context: Activity, url: URL, file: File, listener: Listener) : AsyncTask<Any?, Int?, Any?>() {
    private val context: Context
    private val url: URL
    private val file: File
    private val listener: Listener
    private var wakeLock: WakeLock? = null

    open interface Listener {
        fun onCompleted()
        fun onCancelled()
        fun onException(ex: Throwable)
    }

    override fun onPreExecute() {
        val pm: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, javaClass.getName())
        wakeLock.acquire()
        showNotification(0)
        Toast.makeText(context, context.getString(R.string.msg_downloading, url.toString()), Toast.LENGTH_SHORT).show()
    }

    protected override fun doInBackground(vararg args: Any): Any? {
        Log.i(TAG, "Downloading " + url + " into " + file)
        var `in`: InputStream? = null
        var out: OutputStream? = null
        var connection: URLConnection? = null
        try {
            connection = url.openConnection()
            connection.connect()
            if (connection is HttpURLConnection) {
                val httpConnection: HttpURLConnection = connection
                if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK) throw IOException(httpConnection.getResponseCode().toString() + " " + httpConnection.getResponseMessage())
            }
            val contentLength: Int = connection.getContentLength()
            Log.i(TAG, "Content length=" + contentLength)
            `in` = connection.getInputStream()
            out = FileOutputStream(file)
            var size: Long = 0
            val buffer: ByteArray = ByteArray(4096)
            var bytes: Int
            while (!isCancelled() && (`in`.read(buffer).also({ bytes = it })) != -1) {
                out.write(buffer, 0, bytes)
                size += bytes.toLong()
                if (contentLength > 0) publishProgress((size * 100 / contentLength).toInt())
            }
            Log.i(TAG, "Downloaded size=" + size)
            return null
        } catch (ex: Throwable) {
            return ex
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

    protected override fun onProgressUpdate(vararg progress: Int) {
        super.onProgressUpdate(*progress)
        showNotification(progress.get(0))
    }

    override fun onCancelled() {
        super.onCancelled()
        Log.i(TAG, "Cancelled")
        listener.onCancelled()
    }

    override fun onPostExecute(result: Any?) {
        wakeLock!!.release()
        NotificationManagerCompat.from(context).cancel(ServiceSinkhole.Companion.NOTIFY_DOWNLOAD)
        if (result is Throwable) {
            Log.e(TAG, result.toString() + "\n" + Log.getStackTraceString(result as Throwable?))
            listener.onException(result)
        } else listener.onCompleted()
    }

    private fun showNotification(progress: Int) {
        val main: Intent = Intent(context, ActivitySettings::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(context, ServiceSinkhole.Companion.NOTIFY_DOWNLOAD, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        context.getTheme().resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, "notify")
        builder.setSmallIcon(R.drawable.ic_file_download_white_24dp)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.msg_downloading, url.toString()))
                .setContentIntent(pi)
                .setProgress(100, progress, false)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        NotificationManagerCompat.from(context).notify(ServiceSinkhole.Companion.NOTIFY_DOWNLOAD, builder.build())
    }

    companion object {
        private val TAG: String = "NetGuard.Download"
    }

    init {
        this.context = context
        this.url = url
        this.file = file
        this.listener = listener
    }
}