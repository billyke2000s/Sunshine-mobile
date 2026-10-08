package dev.sunshinemobile.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** MediaCodec vendors may return Annex B or four-byte length-prefixed NAL units. */
public final class Avc {
    private Avc() {}
    public static byte[] annexB(byte[] data) {
        if(data.length>=3 && data[0]==0 && data[1]==0 && (data[2]==1 || (data.length>=4 && data[2]==0 && data[3]==1))) return data;
        ByteArrayOutputStream out=new ByteArrayOutputStream(); int pos=0;
        while(pos<data.length) {
            if(data.length-pos<4) throw new IllegalArgumentException("Truncated AVC NAL length");
            int n=ByteBuffer.wrap(data,pos,4).getInt(); pos+=4;
            if(n<=0 || n>data.length-pos) throw new IllegalArgumentException("Invalid AVC NAL length");
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(data,pos,n); pos+=n;
        }
        return out.toByteArray();
    }
}
