package eu.faircode.netguardimport

import android.content.*
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import eu.faircode.netguard.*


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
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received $intent")
        Util.logExtras(intent)
        val action: String? = (intent.action)
        if ((Intent.ACTION_PACKAGE_FULLY_REMOVED == action)) {
            val uid: Int = intent.getIntExtra(Intent.EXTRA_UID, 0)
            if (uid > 0) {
                val dh: DatabaseHelper = DatabaseHelper.Companion.getInstance(context)
                dh.clearLog(uid)
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