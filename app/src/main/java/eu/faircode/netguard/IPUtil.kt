package eu.faircode.netguard

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*
import kotlin.experimental.and
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

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
*/   object IPUtil {
    private const val TAG: String = "NetGuard.IPUtil"
    @Throws(UnknownHostException::class)
    fun toCIDR(start: String?, end: String?): List<CIDR> {
        return toCIDR(InetAddress.getByName(start), InetAddress.getByName(end))
    }

    @Throws(UnknownHostException::class)
    fun toCIDR(start: InetAddress?, end: InetAddress?): List<CIDR> {
        val listResult: MutableList<CIDR> = ArrayList()
        Log.i(TAG, "toCIDR(" + start!!.hostAddress + "," + end!!.hostAddress + ")")
        var from: Long = inet2long(start)
        val to: Long = inet2long(end)
        while (to >= from) {
            var prefix: Byte = 32
            while (prefix > 0) {
                val mask: Long = prefix2mask(prefix - 1)
                if ((from and mask) != from) break
                prefix--
            }
            val max: Byte = (32 - floor(ln((to - from + 1).toDouble()) / ln(2.0))).toInt().toByte()
            if (prefix < max) prefix = max
            listResult.add(CIDR(long2inet(from), prefix.toInt()))
            from += 2.0.pow((32 - prefix).toDouble()).toLong()
        }
        for (cidr: CIDR in listResult) Log.i(TAG, cidr.toString())
        return listResult
    }

    private fun prefix2mask(bits: Int): Long {
        return (-0x100000000L shr bits) and 0xFFFFFFFFL
    }

    private fun inet2long(addr: InetAddress?): Long {
        var result: Long = 0
        if (addr != null) for (b: Byte in addr.address) result = result shl 8 or ((b and 0xFF.toByte()).toLong())
        return result
    }

    private fun long2inet(addr: Long): InetAddress? {
        var addr: Long = addr
        return try {
            val b = ByteArray(4)
            for (i in b.indices.reversed()) {
                b[i] = (addr and 0xFF).toByte()
                addr = addr shr 8
            }
            InetAddress.getByAddress(b)
        } catch (ignore: UnknownHostException) {
            null
        }
    }

    fun minus1(addr: InetAddress?): InetAddress? {
        return long2inet(inet2long(addr) - 1)
    }

    fun plus1(addr: InetAddress?): InetAddress? {
        return long2inet(inet2long(addr) + 1)
    }

    class CIDR : Comparable<CIDR> {
        var address: InetAddress? = null
        var prefix: Int = 0

        constructor(address: InetAddress?, prefix: Int) {
            this.address = address
            this.prefix = prefix
        }

        constructor(ip: String?, prefix: Int) {
            try {
                address = InetAddress.getByName(ip)
                this.prefix = prefix
            } catch (ex: UnknownHostException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        val start: InetAddress?
            get() {
                return long2inet(inet2long(address) and prefix2mask(prefix))
            }
        val end: InetAddress?
            get() {
                return long2inet((inet2long(address) and prefix2mask(prefix)) + (1L shl (32 - prefix)) - 1)
            }

        override fun toString(): String {
            return address!!.hostAddress + "/" + prefix + "=" + start!!.hostAddress + "..." + end!!.hostAddress
        }

        override fun compareTo(other: CIDR): Int {
            val lcidr: Long = inet2long(address)
            val lother: Long = inet2long(other.address)
            return lcidr.compareTo(lother)
        }
    }
}