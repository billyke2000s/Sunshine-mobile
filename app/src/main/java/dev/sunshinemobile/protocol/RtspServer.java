package dev.sunshinemobile.protocol;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Sunshine encrypted RTSP framing (AES-GCM with CR/HR domain-separated IVs). */
public final class RtspServer implements AutoCloseable {
    private final HostServer host;
    private final ServerSocket listener;
    private volatile boolean running;
    private final ExecutorService workers=new ThreadPoolExecutor(1,4,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private HostSession configured;
    private final Set<String> setup=new HashSet<>();
    public RtspServer(HostServer host) throws IOException { this.host=host; listener=new ServerSocket(48010,16,InetAddress.getByName("0.0.0.0")); }
    public void start() {
        running=true; Thread t=new Thread(()->{ while(running) try { Socket s=listener.accept(); sockets.add(s); try { workers.execute(()->serve(s)); } catch(RejectedExecutionException e) { sockets.remove(s); s.close(); } } catch(IOException e) { break; } },"gamestream-rtsp"); t.setDaemon(true); t.start();
    }
    private void serve(Socket socket) {
        try(Socket s=socket) {
            s.setSoTimeout(8000); HostSession session=host.session(); if(session==null||!session.remote.equals(s.getInetAddress())) return;
            InputStream in=s.getInputStream(); byte[] head=Wire.read(in,24); ByteBuffer b=ByteBuffer.wrap(head);
            int typeAndLength=b.getInt(),seq=b.getInt(),len=typeAndLength&0x7fffffff;
            if(typeAndLength>=0||len>32768) return;
            byte[] sealed=Wire.concat(Wire.read(in,len),Arrays.copyOfRange(head,8,24));
            byte[] plain=Wire.gcm(session.key,Wire.iv(seq,'C','R'),sealed,false);
            if(!session.acceptRtsp(seq)||session!=host.session()) return;
            String response=dispatch(session,new String(plain,java.nio.charset.StandardCharsets.US_ASCII));
            int tx=session.rtspTx.incrementAndGet(); byte[] enc=Wire.gcm(session.key,Wire.iv(tx,'H','R'),response.getBytes(java.nio.charset.StandardCharsets.US_ASCII),true);
            byte[] out=ByteBuffer.allocate(24+enc.length-16).putInt(0x80000000|(enc.length-16)).putInt(tx).put(enc,enc.length-16,16).put(enc,0,enc.length-16).array();
            s.getOutputStream().write(out); s.getOutputStream().flush();
        } catch(Exception ignored) { /* Authentication failures never change session state. */ }
        finally { sockets.remove(socket); }
    }
    private synchronized String dispatch(HostSession s,String message) {
        String[] split=message.split("\r\n\r\n",2),lines=split[0].split("\r\n"); String[] request=lines[0].split(" ");
        Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER); for(int i=1;i<lines.length;i++) { int colon=lines[i].indexOf(':'); if(colon>0) headers.put(lines[i].substring(0,colon),lines[i].substring(colon+1).trim()); }
        String cseq=headers.getOrDefault("CSeq","0"),extra="",body=""; int code=200;
        if(!cseq.matches("[0-9]{1,9}")||request.length!=3) return response(400,"0","","");
        if(configured!=s) { configured=s; setup.clear(); }
        s.touch();
        try {
            switch(request[0]) {
                case "OPTIONS": extra="Public: OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY, TEARDOWN\r\n"; break;
                case "DESCRIBE": body="a=x-ss-general.featureFlags:0\r\na=x-ss-general.encryptionSupported:7\r\na=x-ss-general.encryptionRequested:1\r\na=x-nv-video[0].refPicInvalidation:0\r\na=rtpmap:97 opus/48000/2\r\n"; break;
                case "SETUP": {
                    String type=request[1].contains("=audio")?"audio":request[1].contains("=video")?"video":request[1].contains("=control")?"control":"";
                    if(type.isEmpty()) { code=404; break; } setup.add(type);
                    int port=type.equals("audio")?48000:type.equals("video")?47998:47999;
                    extra="Session: "+s.rtspId+";timeout=90\r\nTransport: server_port="+port+"\r\n"+(type.equals("control")?"X-SS-Connect-Data: "+Integer.toUnsignedString(s.connectData):"X-SS-Ping-Payload: "+s.ping)+"\r\n"; break;
                }
                case "ANNOUNCE": {
                    String data=split.length==2?split[1]:"";
                    if(Integer.parseInt(headers.getOrDefault("Content-Length","0"))!=data.length()||!setup.containsAll(Arrays.asList("audio","video","control"))) { code=400; break; }
                    s.configure(data); setup.add("configured"); break;
                }
                case "PLAY": if(!setup.contains("configured")) code=455; else s.play(); break;
                case "TEARDOWN": host.end(s,"RTSP teardown"); break;
                default: code=405;
            }
        } catch(Exception e) { code=400; }
        return response(code,cseq,extra,body);
    }
    private static String response(int code,String cseq,String extra,String body) { return "RTSP/1.0 "+code+(code==200?" OK":" Error")+"\r\nCSeq: "+cseq+"\r\n"+extra+"Content-Length: "+body.length()+"\r\n\r\n"+body; }
    public void close() { running=false; try { listener.close(); } catch(IOException ignored) {} for(Socket s:sockets) try { s.close(); } catch(IOException ignored) {} workers.shutdownNow(); }
}
