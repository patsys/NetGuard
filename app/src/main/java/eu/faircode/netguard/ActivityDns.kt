package eu.faircode.netguard

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Bundle
import android.util.Log
import android.util.Xml
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import eu.faircode.netguard.Util.DoubtListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
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
*/
open class ActivityDns : AppCompatActivity() {
    private var running: Boolean = false
    private var adapter: AdapterDns? = null
    val uiScope = CoroutineScope(Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.resolving)
        supportActionBar!!.setTitle(R.string.setting_show_resolved)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        val lvDns: ListView = findViewById(R.id.lvDns)
        adapter = AdapterDns(this, DatabaseHelper.getInstance(this).dns)
        lvDns.adapter = adapter
        running = true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.dns, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val pm: PackageManager = packageManager
        menu.findItem(R.id.menu_export).isEnabled = intentExport.resolveActivity(pm) != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> {
                refresh()
                return true
            }
            R.id.menu_cleanup -> {
                uiScope.launch {cleanup()}
                return true
            }
            R.id.menu_clear -> {
                Util.areYouSure(this, R.string.menu_clear, object : DoubtListener {
                    override fun onSure() {
                        uiScope.launch {clear()}
                    }
                })
                return true
            }
            R.id.menu_export -> {
                export()
                return true
            }
        }
        return false
    }

    private fun refresh() {
        updateAdapter()
    }

    private suspend fun cleanup() {
        withContext(Dispatchers.Default){
            Log.i(TAG, "Cleanup DNS")
            DatabaseHelper.getInstance(this@ActivityDns).cleanupDns()

            withContext(Dispatchers.Main){
                ServiceSinkhole.reload("DNS cleanup", this@ActivityDns, false)
                updateAdapter()
            }
        }
    }


    private suspend fun clear() {
        withContext(Dispatchers.Default){
            Log.i(TAG, "Clear DNS")
            DatabaseHelper.getInstance(this@ActivityDns).clearDns()
            withContext(Dispatchers.Main){
                ServiceSinkhole.reload("DNS clear", this@ActivityDns, false)
                updateAdapter()
            }
        }
    }

    private fun export() {
        startActivityForResult(intentExport, REQUEST_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK))
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null) uiScope.launch {handleExport(data)}
        }
    }

    // text/xml
    private val intentExport: Intent
        @SuppressLint("SimpleDateFormat") get() {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*" // text/xml
            intent.putExtra(Intent.EXTRA_TITLE, "netguard_dns_" + SimpleDateFormat("yyyyMMdd").format(Date().time) + ".xml")
            return intent
        }

    private suspend fun handleExport(data: Intent) {
        withContext(Dispatchers.Default) {
            var out: OutputStream? = null
            try {
                val target: Uri? = data.data
                Log.i(TAG, "Writing URI=$target")
                out = contentResolver.openOutputStream((target)!!)
                xmlExport(out)
                withContext(Dispatchers.Main) { if (this@ActivityDns.running) Toast.makeText(this@ActivityDns, R.string.msg_completed, Toast.LENGTH_LONG).show() }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                withContext(Dispatchers.Main) { Toast.makeText(this@ActivityDns, ex.toString(), Toast.LENGTH_LONG).show() }
            } finally {
                if (out != null) try {
                    out.close()
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun xmlExport(out: OutputStream?) {
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument(null, true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.startTag(null, "netguard")
        val df: DateFormat = SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Locale.US) // RFC 822
        DatabaseHelper.getInstance(this).dns.use {
            val colTime: Int = it.getColumnIndex("time")
            val colQName: Int = it.getColumnIndex("qname")
            val colAName: Int = it.getColumnIndex("aname")
            val colResource: Int = it.getColumnIndex("resource")
            val colTTL: Int = it.getColumnIndex("ttl")
            while (it.moveToNext()) {
                val time: Long = it.getLong(colTime)
                val qname: String = it.getString(colQName)
                val aname: String = it.getString(colAName)
                val resource: String = it.getString(colResource)
                val ttl: Int = it.getInt(colTTL)
                serializer.startTag(null, "dns")
                serializer.attribute(null, "time", df.format(time))
                serializer.attribute(null, "qname", qname)
                serializer.attribute(null, "aname", aname)
                serializer.attribute(null, "resource", resource)
                serializer.attribute(null, "ttl", ttl.toString())
                serializer.endTag(null, "dns")
            }
        }
        serializer.endTag(null, "netguard")
        serializer.endDocument()
        serializer.flush()
    }

    private fun updateAdapter() {
        if (adapter != null) adapter!!.changeCursor(DatabaseHelper.getInstance(this).dns)
    }

    override fun onDestroy() {
        running = false
        adapter = null
        super.onDestroy()
    }

    companion object {
        private val TAG: String = "NetGuard.DNS"
        private const val REQUEST_EXPORT: Int = 1
    }
}