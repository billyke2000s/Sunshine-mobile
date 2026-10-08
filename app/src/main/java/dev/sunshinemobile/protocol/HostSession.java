package dev.sunshinemobile.protocol;

import java.net.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HostSession implements AutoCloseable {
    public interface Capture { void start(HostSession s); void stop(HostSession s); void requestIdr(); default boolean ready() { return true; } }
    public final InetAddress remote;
    public final String owner,ping=Wire.hex(Wire.random(8)),rtspId=Wire.hex(Wire.random(8));
    public final byte[] key;
    public final int keyId,connectData=ByteBuffer.wrap(Wire.random(4)).getInt();
    public volatile int width=1280,height=720,fps=60,bitrate=10000,packetSize=1024,minFec=2,audioDuration=5,encryption=1;
    public volatile boolean playing,controlReady;
    public volatile MediaTransport transport;
    private final Capture capture;
    private final java.util.function.Consumer<String> failure;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile long lastActivity=System.nanoTime();
    private final Thread controlThread;
    private final long control;
    public final AtomicInteger rtspTx=new AtomicInteger();
    private final Set<Integer> rtspSeen=new HashSet<>();
    private final Set<Integer> controlSeen=new HashSet<>();
    private long maxControl=-1,maxRtsp=-1;
    public HostSession(InetAddress remote,String owner,byte[] key,int keyId,Capture capture,java.util.function.Consumer<String> failure) throws Exception {
        if(key.length!=16) throw new IllegalArgumentException("Invalid session key");
        this.remote=remote; this.owner=owner; this.key=key; this.keyId=keyId; this.capture=capture; this.failure=failure;
        control=Native.controlCreate(remote.getHostAddress(),47999,connectData); if(control==0) throw new IllegalStateException("ENet bind failed");
        try { transport=new MediaTransport(this); } catch(Exception e) { Native.controlDestroy(control); throw e; }
        controlThread=new Thread(this::controlLoop,"gamestream-control"); controlThread.setDaemon(true); controlThread.start();
    }
    public void touch() { lastActivity=System.nanoTime(); }
    public boolean expired() { return System.nanoTime()-lastActivity>TimeUnit.SECONDS.toNanos(20); }
    public boolean isClosed() { return closed.get(); }
    public synchronized boolean acceptRtsp(int seq) { long n=Integer.toUnsignedLong(seq); if(n+64<maxRtsp || !rtspSeen.add(seq)) return false; maxRtsp=Math.max(maxRtsp,n); rtspSeen.removeIf(v->Integer.toUnsignedLong(v)+64<maxRtsp); return true; }
    public synchronized void configure(String sdp) {
        if(playing) throw new IllegalArgumentException("Already playing");
        Map<String,Integer> a=new HashMap<>(); for(String line:sdp.split("\r?\n")) if(line.startsWith("a=")) { int pos=line.indexOf(':'); if(pos>2) { try { a.put(line.substring(2,pos),Integer.parseInt(line.substring(pos+1).trim())); } catch(NumberFormatException ignored) {} } }
        width=get(a,"x-nv-video[0].clientViewportWd",1280,320,1920); height=get(a,"x-nv-video[0].clientViewportHt",720,240,1080); fps=get(a,"x-nv-video[0].maxFPS",60,1,60);
        if((width&1)!=0 || (height&1)!=0) throw new IllegalArgumentException("Even dimensions required");
        bitrate=get(a,"x-ml-video.configuredBitrateKbps",a.getOrDefault("x-nv-vqos[0].bw.maximumBitrateKbps",10000),500,50000);
        packetSize=get(a,"x-nv-video[0].packetSize",1024,256,1392); minFec=get(a,"x-nv-vqos[0].fec.minRequiredFecPackets",2,0,2);
        audioDuration=get(a,"x-nv-aqos.packetDuration",5,5,20); if(audioDuration!=5&&audioDuration!=10&&audioDuration!=20) throw new IllegalArgumentException("Unsupported audio duration");
        if(a.getOrDefault("x-nv-audio.surround.numChannels",2)!=2 || a.getOrDefault("x-nv-vqos[0].bitStreamFormat",0)!=0 || a.getOrDefault("x-nv-video[0].dynamicRangeMode",0)!=0) throw new IllegalArgumentException("H264 SDR stereo required");
        encryption=a.getOrDefault("x-ss-general.encryptionEnabled",0); if((encryption&~7)!=0) throw new IllegalArgumentException("Unsupported encryption flags");
        if((a.getOrDefault("x-nv-general.featureFlags",0)&0x20)!=0) encryption|=4;
        if((encryption&1)==0) throw new IllegalArgumentException("Control v2 encryption required");
        touch();
    }
    private static int get(Map<String,Integer>a,String k,int fallback,int min,int max) { int v=a.getOrDefault(k,fallback); if(v<min||v>max) throw new IllegalArgumentException("Unsupported "+k); return v; }
    public synchronized void play() { if(closed.get()) throw new IllegalStateException("Session closed"); if(!playing) { playing=true; capture.start(this); } touch(); }
    private void controlLoop() {
        try { while(!closed.get()) {
            byte[] event=Native.controlPoll(control,100); if(event==null) continue;
            if(event[0]==2) { fail("Client disconnected"); return; }
            if(event[0]!=3) continue;
            byte[] p=Arrays.copyOfRange(event,1,event.length); if(p.length<28) continue;
            ByteBuffer b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN); if(b.getShort(0)!=1 || (b.getShort(2)&65535)!=p.length-4) continue;
            int seq=b.getInt(4); long n=Integer.toUnsignedLong(seq); if(n+64<maxControl || controlSeen.contains(seq)) continue;
            byte[] sealed=Wire.concat(Arrays.copyOfRange(p,24,p.length),Arrays.copyOfRange(p,8,24));
            byte[] plain;
            try { plain=Wire.gcm(key,Wire.iv(seq,'C','C'),sealed,false); } catch(GeneralSecurityException e) { continue; }
            if(plain.length<4) continue; b=ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN); int type=b.getShort(0)&65535,size=b.getShort(2)&65535; if(size!=plain.length-4) continue;
            controlSeen.add(seq); maxControl=Math.max(maxControl,n); controlSeen.removeIf(v->Integer.toUnsignedLong(v)+64<maxControl);
            touch(); controlReady=true;
            if(type==0x0302 || type==0x0301 || type==0x0307) capture.requestIdr();
        } } catch(Exception e) { if(!closed.get()) fail("Control failed: "+e.getClass().getSimpleName()); }
        finally { Native.controlDestroy(control); }
    }
    public void fail(String reason) { if(!closed.get()) failure.accept(reason); }
    public void close() {
        if(!closed.compareAndSet(false,true)) return; playing=false; controlReady=false; capture.stop(this); transport.close();
        if(Thread.currentThread()!=controlThread) try { controlThread.join(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
