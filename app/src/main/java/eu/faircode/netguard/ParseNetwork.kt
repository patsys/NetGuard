package eu.faircode.netguard

import android.util.Log
import kotlin.experimental.and

object ParseNetwork {
    private const val TAG: String = "ParseNetwork"
    fun parse(packet: Packet): Map<String, Any>? {
        val data: ByteArray = packet.packetData
        if (((packet.packetData[0] and 0xFF.toByte()).toInt() shr 4) == 4 && packet.protocol == 6) {
            val headerLen: Int = (packet.packetData[0] and 0x0F).toInt() * 4
            val tcpHeaderLen: Int = ((packet.packetData[headerLen + 12] and 0xFF.toByte()).toInt() shr 4) * 4
            val startTcpData: Int = headerLen + tcpHeaderLen
            if (data.size > startTcpData + 6) {
                if (packet.dport == 443) {
                    Log.w(TAG, Integer.toHexString((data[startTcpData] and 0xFF.toByte()).toInt()) + " " + Integer.toHexString((data[startTcpData + 1] and 0xFF.toByte()).toInt()) + " " + Integer.toHexString((data[startTcpData + 2] and 0xFF.toByte()).toInt()) + " " + Integer.toHexString((data[startTcpData + 3] and 0xFF.toByte()).toInt()) + " " + Integer.toHexString((data[startTcpData + 4] and 0xFF.toByte()).toInt()) + " " + Integer.toHexString((data[startTcpData + 5] and 0xFF.toByte()).toInt()))
                    if (data[startTcpData] == 0x16.toByte()) {
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
            }
        }
        return null
    }
}