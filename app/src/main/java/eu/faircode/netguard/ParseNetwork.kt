package eu.faircode.netguard

import android.util.Log

object ParseNetwork {
    private val TAG: String = "ParseNetwork"
    fun parse(packet: Packet): Map<String, Any>? {
        val data: ByteArray? = packet.packetData
        if (((packet.packetData.get(0) and 0xFF) shr 4) == 4 and packet.protocol == 6) {
            val headerLen: Int = (packet.packetData.get(0) and 0x0F) as Int * 4
            val tcpHeaderLen: Int = ((packet.packetData.get(headerLen + 12) and 0xFF) shr 4) as Int * 4
            val startTcpData: Int = headerLen + tcpHeaderLen
            if (data!!.size > startTcpData + 6) {
                if (packet.dport == 443) {
                    Log.w(TAG, Integer.toHexString(data.get(startTcpData) and 0xFF) + " " + Integer.toHexString(data.get(startTcpData + 1) and 0xFF) + " " + Integer.toHexString(data.get(startTcpData + 2) and 0xFF) + " " + Integer.toHexString(data.get(startTcpData + 3) and 0xFF) + " " + Integer.toHexString(data.get(startTcpData + 4) and 0xFF) + " " + Integer.toHexString(data.get(startTcpData + 5) and 0xFF))
                    if (data.get(startTcpData) == 0x16) {
                        Log.w(TAG, "SSL HELLO MESSAGE")
                        return null
                    }
                    /*
                String strPacket = new String(data, startTcpData, data.length - startTcpData);
                if (strPacket.contains("http")) {
                    Log.w(TAG, strPacket);
                int pos = strhttp.indexOf("Host:");
                if(pos>0){
                    Log.w(TAG,http.substring(pos+5,http.indexOf("\n",pos)));
                }*/
                }
            } else {
            }
        }
        return null
    }
}