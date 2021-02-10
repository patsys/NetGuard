package eu.faircode.netguard

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
class Version constructor(version: String?) : Comparable<Version> {
    private val version: String = version!!.replace("-beta", "")
    override fun compareTo(other: Version): Int {
        val lhs: Array<String> = version.split("\\.").toTypedArray()
        val rhs: Array<String> = other.version.split("\\.").toTypedArray()
        val length: Int = lhs.size.coerceAtLeast(rhs.size)
        for (i in 0 until length) {
            val vLhs: Int = (if (i < lhs.size) lhs[i].toInt() else 0)
            val vRhs: Int = (if (i < rhs.size) rhs[i].toInt() else 0)
            if (vLhs < vRhs) return -1
            if (vLhs > vRhs) return 1
        }
        return 0
    }

    override fun toString(): String {
        return version
    }

}