package dev.sunshinemobile.protocol;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** GameStream video/FEC and Opus audio/FEC, mirroring Sunshine stream.cpp. */
public final class MediaTransport implements AutoCloseable {
    private final HostSession session;
    private final DatagramSocket video,audio;
    private final AtomicBoolean running=new AtomicBoolean(true);
    private volatile InetSocketAddress videoPeer,audioPeer;
    private int videoSequence,frameIndex=1,audioSequence,audioTimestamp;
    private long videoIv;
    private final byte[][] audioBlock=new byte[4][];
    private final Thread videoPing,audioPing;
    public MediaTransport(HostSession session) throws Exception {
        this.session=session;
        video=new DatagramSocket(null); audio=new DatagramSocket(null);
        try { video.bind(new InetSocketAddress("0.0.0.0",47998)); audio.bind(new InetSocketAddress("0.0.0.0",48000)); video.setSendBufferSize(1024*1024); }
        catch(Exception e) { video.close(); audio.close(); throw e; }
        videoPing=pingThread(video,true); audioPing=pingThread(audio,false);
    }
    private Thread pingThread(DatagramSocket sock,boolean isVideo) {
        Thread t=new Thread(()->{ byte[] b=new byte[64]; while(running.get()) { try {
            DatagramPacket p=new DatagramPacket(b,b.length); sock.receive(p);
            if(p.getAddress().equals(session.remote) && p.getLength()==20 && java.security.MessageDigest.isEqual(Arrays.copyOf(b,16),session.ping.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                InetSocketAddress addr=new InetSocketAddress(p.getAddress(),p.getPort()); if(isVideo) videoPeer=addr; else audioPeer=addr;
                session.touch();
            }
        } catch(Exception e) { if(running.get()) session.fail("UDP receive: "+e.getClass().getSimpleName()); break; } }},isVideo?"video-ping":"audio-ping"); t.setDaemon(true); t.start(); return t;
    }
    public synchronized void video(byte[] annexB,long ptsUs,boolean keyFrame) throws Exception {
        InetSocketAddress peer=videoPeer; if(!running.get() || peer==null || !session.playing || !session.controlReady) return;
        int payloadSize=session.packetSize-16;
        byte[] fh=new byte[8]; fh[0]=1; fh[3]=(byte)(keyFrame?2:1);
        int last=(annexB.length+8)%payloadSize; if(last==0) last=payloadSize;
        ByteBuffer.wrap(fh).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)last);
        byte[] frame=Wire.concat(fh,annexB);
        int packets=(frame.length+payloadSize-1)/payloadSize;
        int blocks=(packets+212)/213;
        if(blocks>4) throw new IllegalArgumentException("Encoded frame exceeds GameStream FEC capacity");
        int perBlock=(packets+blocks-1)/blocks,offset=0;
        int timestamp=(int)(ptsUs*90/1000);
        for(int block=0;block<blocks;block++) {
            int ds=Math.min(perBlock,packets-block*perBlock),parity=Math.max(session.minFec,(ds*20+99)/100);
            int percentage=20; if((ds*20+99)/100<session.minFec) percentage=100*parity/ds;
            byte[][] data=new byte[ds][];
            int size=session.packetSize+16;
            for(int i=0;i<ds;i++) {
                byte[] p=new byte[size]; ByteBuffer le=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
                le.putInt(16,(videoSequence+i)<<8); le.putInt(20,frameIndex);
                p[24]=(byte)(1|(i==0?4:0)|(i==ds-1?2:0)); p[26]=0x10; p[27]=(byte)((block<<4)|((blocks-1)<<6));
                int amount=Math.min(payloadSize,frame.length-offset); System.arraycopy(frame,offset,p,32,amount); offset+=amount; data[i]=p;
            }
            byte[][] recovery=Native.parity(data,parity,false); if(recovery==null) throw new IllegalStateException("FEC encode failed");
            for(int i=0;i<ds+parity;i++) {
                byte[] p=i<ds?data[i]:recovery[i-ds];
                ByteBuffer be=ByteBuffer.wrap(p); p[0]=(byte)0x90; be.putShort(2,(short)(videoSequence+i)); be.putInt(4,timestamp);
                ByteBuffer le=be.order(ByteOrder.LITTLE_ENDIAN); le.putInt(20,frameIndex); p[27]=(byte)((block<<4)|((blocks-1)<<6)); le.putInt(28,(i<<12)|(ds<<22)|(percentage<<4));
                if((session.encryption&2)!=0) {
                    byte[] iv=new byte[12]; ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(videoIv++); iv[11]='V';
                    byte[] sealed=Wire.gcm(session.key,iv,p,true); byte[] frameId=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(frameIndex).array();
                    p=Wire.concat(iv,frameId,Arrays.copyOfRange(sealed,sealed.length-16,sealed.length),Arrays.copyOf(sealed,sealed.length-16));
                }
                video.send(new DatagramPacket(p,p.length,peer));
                // Bound Wi-Fi bursts while retaining low latency.
                if(i%32==31) java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
            videoSequence+=ds+parity;
        }
        frameIndex++;
    }
    public synchronized void audio(byte[] opus) throws Exception {
        InetSocketAddress peer=audioPeer; if(!running.get()||peer==null||!session.playing||!session.controlReady) return;
        int sequence=audioSequence&65535;
        byte[] payload=(session.encryption&4)!=0?Wire.cbc(session.key,session.keyId,sequence,opus):opus;
        byte[] packet=new byte[12+payload.length]; ByteBuffer b=ByteBuffer.wrap(packet); packet[0]=(byte)0x80; packet[1]=97; b.putShort(2,(short)sequence); b.putInt(4,audioTimestamp); System.arraycopy(payload,0,packet,12,payload.length);
        audio.send(new DatagramPacket(packet,packet.length,peer)); audioBlock[sequence%4]=payload;
        if(sequence%4==3) {
            for(byte[] shard:audioBlock) if(shard==null||shard.length!=payload.length) throw new IllegalStateException("Opus CBR shard lengths differ");
            byte[][] fec=Native.parity(audioBlock,2,true); if(fec==null) throw new IllegalStateException("Audio FEC failed");
            for(int i=0;i<2;i++) {
                byte[] p=new byte[24+payload.length]; b=ByteBuffer.wrap(p); p[0]=(byte)0x80; p[1]=127; b.putShort(2,(short)(sequence+i+1)); p[12]=(byte)i; p[13]=97;
                b.putShort(14,(short)(sequence-3)); b.putInt(16,audioTimestamp-3*session.audioDuration); System.arraycopy(fec[i],0,p,24,payload.length); audio.send(new DatagramPacket(p,p.length,peer));
            }
        }
        audioSequence++; audioTimestamp+=session.audioDuration;
    }
    public void close() { running.set(false); video.close(); audio.close(); join(videoPing); join(audioPing); }
    private static void join(Thread t) { if(t==Thread.currentThread()) return; try { t.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
}
