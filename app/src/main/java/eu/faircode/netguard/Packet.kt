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
class Packet constructor() {
    var time: Long = 0
    var version: Int = 0
    var protocol: Int = 0
    var flags: String? = null
    var saddr: String? = null
    var sport: Int = 0
    var daddr: String? = null
    var dport: Int = 0
    var data: String? = null
    var packetData: ByteArray
    var uid: Int = 0
    var allowed: Boolean = false
    public override fun toString(): String {
        return "uid=" + uid + " v" + version + " p" + protocol + " " + daddr + "/" + dport
    }
}