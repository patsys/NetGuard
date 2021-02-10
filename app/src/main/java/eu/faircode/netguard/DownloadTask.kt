package eu.faircode.netguard

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.os.Build
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.NonCancellable.isCancelled
import kotlinx.coroutines.withContext
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
*/   open class DownloadTask constructor(context: Activity, url: URL, file: File, listener: Listener) {
    private val context: Context
    private val url: URL
    private val file: File
    private val listener: Listener
    private var wakeLock: WakeLock? = null
    private var progress = 0

    interface Listener {
        suspend fun onCompleted()
        suspend fun onCancelled()
        suspend fun onException(ex: Throwable)
    }

    @InternalCoroutinesApi
    suspend fun beginDownload(vararg args: Any){
        withContext(Dispatchers.Main){
            onPreExecute()
            withContext(Dispatchers.IO){
                var res = doInBackground()
                withContext(Dispatchers.Main){
                    onPostExecute(res)
                }
            }
        }
    }

    suspend fun onPreExecute() {
        val pm: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, javaClass.name)
        wakeLock!!.acquire(10*60*1000L /*10 minutes*/)
        showNotification(0)
        Toast.makeText(context, context.getString(R.string.msg_downloading, url.toString()), Toast.LENGTH_SHORT).show()
    }

    @InternalCoroutinesApi
    protected suspend fun doInBackground(): Any? {
        Log.i(TAG, "Downloading $url into $file")
        var `in`: InputStream? = null
        var out: OutputStream? = null
        var connection: URLConnection? = null
        try {
            connection = url.openConnection()
            connection.connect()
            if (connection is HttpURLConnection) {
                val httpConnection: HttpURLConnection = connection
                if (httpConnection.responseCode != HttpURLConnection.HTTP_OK) throw IOException(httpConnection.responseCode.toString() + " " + httpConnection.responseMessage)
            }
            val contentLength: Int = connection.contentLength
            Log.i(TAG, "Content length=$contentLength")
            `in` = connection.getInputStream()
            out = FileOutputStream(file)
            var size: Long = 0
            val buffer = ByteArray(4096)
            var bytes: Int
            while (!isCancelled && `in`.read(buffer).let { bytes = it
                    if(bytes != -1) {
                        out.write(buffer, 0, bytes)
                        size += bytes.toLong()
                        if (contentLength > 0) {
                            onProgressUpdate((size * 100 / contentLength).toInt())
                        }
                    }
                    return bytes
            } != -1);
            Log.i(TAG, "Downloaded size=$size")
            return null
        } catch (ex: Throwable) {
            return ex
        } finally {
            try {
                out?.close()
            } catch (ex: IOException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            try {
                `in`?.close()
            } catch (ex: IOException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
            if (connection is HttpURLConnection) connection.disconnect()
        }
    }

    protected suspend fun onProgressUpdate(vararg progress: Int) {
        showNotification(progress[0])
    }

    suspend fun onCancelled() {
        Log.i(TAG, "Cancelled")
        listener.onCancelled()
    }

    private suspend fun onPostExecute(result: Any?) {
        wakeLock!!.release()
        NotificationManagerCompat.from(context).cancel(ServiceSinkhole.Companion.NOTIFY_DOWNLOAD)
        if (result is Throwable) {
            Log.e(TAG, result.toString() + "\n" + Log.getStackTraceString(result as Throwable?))
            listener.onException(result)
        } else listener.onCompleted()
    }

    private suspend fun showNotification(progress: Int) {
        val main: Intent = Intent(context, ActivitySettings::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(context, ServiceSinkhole.Companion.NOTIFY_DOWNLOAD, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv: TypedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, "notify")
        builder.setSmallIcon(R.drawable.ic_file_download_white_24dp)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.msg_downloading, url.toString()))
                .setContentIntent(pi)
                .setProgress(100, progress, false)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        NotificationManagerCompat.from(context).notify(ServiceSinkhole.NOTIFY_DOWNLOAD, builder.build())
    }

    companion object {
        private const val TAG: String = "NetGuard.Download"
    }

    init {
        this.context = context
        this.url = url
        this.file = file
        this.listener = listener
    }
}