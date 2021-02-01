package eu.faircode.netguard;

import android.util.Log;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import eu.faircode.netguard.Packet;

public class ParseNetwork {
    static final private String TAG = "ParseNetwork";
    static public Map<String, Object> parse(Packet packet){
        final byte data[] = packet.packetData;
        int ipProtocol=(int)((packet.packetData[0] & 0xFF)>>4);
        if(ipProtocol==4 & packet.protocol==6) {
            int headerLen = (int) (packet.packetData[0] & 0x0F) * 4;
            int tcpHeaderLen = (int) ((packet.packetData[headerLen + 12] & 0xFF) >> 4) * 4;
            int startTcpData = headerLen + tcpHeaderLen;
            if (data.length > startTcpData+6) {
                if(packet.dport==443){
                    Log.w(TAG, Integer.toHexString(data[startTcpData]& 0xFF)+" "+Integer.toHexString(data[startTcpData+1]& 0xFF)+" "+Integer.toHexString(data[startTcpData+2]& 0xFF)+" "+Integer.toHexString(data[startTcpData+3]& 0xFF)+" "+Integer.toHexString(data[startTcpData+4]& 0xFF)+" "+Integer.toHexString(data[startTcpData+5]& 0xFF));
                if (data[startTcpData] == 0x16) {
                    Log.w(TAG, "SSL HELLO MESSAGE");
                    return null;
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
        return null;

    }
}
