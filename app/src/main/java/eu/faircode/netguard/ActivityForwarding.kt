package eu.faircode.netguardimport

import android.content.DialogInterface
import android.database.Cursor
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import eu.faircode.netguard.*
import eu.faircode.netguard.DatabaseHelper.ForwardChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress


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
*/   class ActivityForwarding : AppCompatActivity() {
    private var running: Boolean = false
    private lateinit var  lvForwarding: ListView
    private lateinit var adapter: AdapterForwarding
    private var dialog: AlertDialog? = null
    val uiScope = CoroutineScope(Dispatchers.Main)
    private val listener by lazy {
        object : ForwardChangedListener {
        override fun onChanged() {
            runOnUiThread { adapter.changeCursor(DatabaseHelper.getInstance(this@ActivityForwarding).forwarding) }
        }
    }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.forwarding)
        running = true
        supportActionBar!!.setTitle(R.string.setting_forwarding)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        lvForwarding = findViewById(R.id.lvForwarding)
        adapter = AdapterForwarding(this, DatabaseHelper.getInstance(this).forwarding)
        lvForwarding.adapter = adapter
        lvForwarding.onItemClickListener = OnItemClickListener { _, view, position, _ ->
            val cursor: Cursor = adapter.getItem(position) as Cursor
            val protocol: Int = cursor.getInt(cursor.getColumnIndex("protocol"))
            val dport: Int = cursor.getInt(cursor.getColumnIndex("dport"))
            val raddr: String = cursor.getString(cursor.getColumnIndex("raddr"))
            val rport: Int = cursor.getInt(cursor.getColumnIndex("rport"))
            val popup = PopupMenu(this@ActivityForwarding, view)
            popup.inflate(R.menu.forward)
            popup.menu.findItem(R.id.menu_port).title = (Util.getProtocolName(protocol, 0, false) + " " +
                    dport + " > " + raddr + "/" + rport)
            popup.setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.menu_delete) {
                    DatabaseHelper.getInstance(this@ActivityForwarding).deleteForward(protocol, dport)
                    ServiceSinkhole.reload("forwarding", this@ActivityForwarding, false)
                    adapter = AdapterForwarding(this@ActivityForwarding,
                            DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
                    lvForwarding.adapter = adapter
                }
                false
            }
            popup.show()
        }
    }

    override fun onResume() {
        super.onResume()
        DatabaseHelper.getInstance(this).addForwardChangedListener(listener)
        adapter.changeCursor(DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
    }

    override fun onPause() {
        super.onPause()
        DatabaseHelper.getInstance(this).removeForwardChangedListener(listener)
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.forwarding, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> {
                val inflater: LayoutInflater = LayoutInflater.from(this)
                val view: View = inflater.inflate(R.layout.forwardadd, null, false)
                val spProtocol: Spinner = view.findViewById(R.id.spProtocol)
                val etDPort: EditText = view.findViewById(R.id.etDPort)
                val etRAddr: EditText = view.findViewById(R.id.etRAddr)
                val etRPort: EditText = view.findViewById(R.id.etRPort)
                val pbRuid: ProgressBar = view.findViewById(R.id.pbRUid)
                val spRuid: Spinner = view.findViewById(R.id.spRUid)
                val task = uiScope.launch {
                    withContext(Dispatchers.Main) {
                        pbRuid.visibility = View.VISIBLE
                        spRuid.visibility = View.GONE
                        withContext(Dispatchers.Default) {
                        }
                        val rules = Rule.getRules(true, this@ActivityForwarding)
                        withContext(Dispatchers.Main) {
                            val spinnerArrayAdapter: ArrayAdapter<*> = ArrayAdapter<Any?>(this@ActivityForwarding,
                                    android.R.layout.simple_spinner_item, rules)
                            spRuid.adapter = spinnerArrayAdapter
                            pbRuid.visibility = View.GONE
                            spRuid.visibility = View.VISIBLE
                        }
                    }
                }
                dialog = AlertDialog.Builder(this)
                        .setView(view)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.yes, object : DialogInterface.OnClickListener {
                            override fun onClick(dialog: DialogInterface, which: Int) {
                                try {
                                    val pos: Int = spProtocol.selectedItemPosition
                                    val values: Array<String> = resources.getStringArray(R.array.protocolValues)
                                    val protocol: Int = Integer.valueOf(values[pos])
                                    val dport: Int = etDPort.text.toString().toInt()
                                    val raddr: String = etRAddr.text.toString()
                                    val rport: Int = etRPort.text.toString().toInt()
                                    val ruid: Int = (spRuid.selectedItem as Rule).uid
                                    val iraddr: InetAddress = InetAddress.getByName(raddr)
                                    if (rport < 1024 && (iraddr.isLoopbackAddress || iraddr.isAnyLocalAddress)) throw IllegalArgumentException("Port forwarding to privileged port on local address not possible")
                                    uiScope.launch {
                                        withContext(Dispatchers.Default){
                                            try {
                                                DatabaseHelper.getInstance(this@ActivityForwarding)
                                                        .addForward(protocol, dport, raddr, rport, ruid)
                                                withContext(Dispatchers.Main){
                                                    if (running) {
                                                        ServiceSinkhole.reload("forwarding", this@ActivityForwarding, false)
                                                        adapter = AdapterForwarding(this@ActivityForwarding,
                                                                DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
                                                        lvForwarding.adapter = adapter
                                                    }
                                                }
                                            } catch (ex: Throwable) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@ActivityForwarding, ex.toString(), Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                } catch (ex: Throwable) {
                                    Toast.makeText(this@ActivityForwarding, ex.toString(), Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.no) { dialog, which ->
                            task.cancel()
                            dialog.dismiss()
                        }
                        .setOnDismissListener { dialog = null }
                        .create()
                dialog!!.show()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }
}