package dev.sunshinemobile.protocol;

import java.io.*;
import java.net.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Authenticated GameStream HTTP/HTTPS control plane and one exclusive capture session. */
public final class HostServer implements AutoCloseable {
    private final HostIdentity identity;
    public final Pairing pairing;
    private final HostSession.Capture capture;
    private final java.util.function.Consumer<String> status;
    private volatile HostSession session;
    private final Set<Socket> connections=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=new ThreadPoolExecutor(2,12,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));
    private final ScheduledExecutorService lifecycle=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running;
    private ServerSocket http,https;
    private RtspServer rtsp;
    public HostServer(HostIdentity identity,HostSession.Capture capture,java.util.function.Consumer<Pairing.Pending> pending,java.util.function.Consumer<String> status) { this.identity=identity; this.capture=capture; this.status=status; pairing=new Pairing(identity,pending); }
    public synchronized HostSession session() { return session; }
    public synchronized void start() throws Exception {
        if(running) return;
        try {
            http=new ServerSocket(47989,16,InetAddress.getByName("0.0.0.0"));
            https=identity.tls().getServerSocketFactory().createServerSocket(47984,16,InetAddress.getByName("0.0.0.0"));
            ((SSLServerSocket)https).setNeedClientAuth(true);
            ((SSLServerSocket)https).setEnabledProtocols(new String[]{"TLSv1.2"});
            rtsp=new RtspServer(this); running=true; rtsp.start(); accept(http,false); accept(https,true);
            lifecycle.scheduleAtFixedRate(()->{ HostSession s=session(); if(s!=null&&s.expired()) end(s,"Session timed out"); },1,1,TimeUnit.SECONDS);
            status.accept("Ready for Moonlight pairing");
        } catch(Exception e) { close(); throw e; }
    }
    private void accept(ServerSocket listener,boolean tls) {
        Thread t=new Thread(()->{ while(running) { try {
            Socket s=listener.accept(); connections.add(s);
            try { workers.execute(()->serve(s,tls)); } catch(RejectedExecutionException e) { connections.remove(s); s.close(); }
        } catch(IOException e) { if(running) status.accept("Listener failed: "+e.getClass().getSimpleName()); break; } }},tls?"gamestream-https":"gamestream-http"); t.setDaemon(true); t.start();
    }
    private void serve(Socket socket,boolean tls) {
        try(Socket s=socket) {
            s.setSoTimeout(10000); String owner=null;
            if(tls) { SSLSocket ssl=(SSLSocket)s; ssl.startHandshake(); X509Certificate c=(X509Certificate)ssl.getSession().getPeerCertificates()[0]; if(!identity.authorized(c)) return; owner=Wire.hex(Wire.sha(c.getEncoded())); }
            InputStream in=s.getInputStream(); String request=Wire.line(in,32768); if(request==null) return;
            String[] parts=request.split(" "); if(parts.length!=3||!parts[0].equals("GET")) { respond(s,Wire.error(405,"GET required")); return; }
            int total=0; String line; while((line=Wire.line(in,8192))!=null&&!line.isEmpty()) { total+=line.length(); if(total>16384) throw new IOException("Headers too large"); }
            URI uri=new URI(parts[1]); Map<String,String> q=query(uri.getRawQuery()); String path=uri.getPath();
            if("/serverinfo".equals(path)) { respond(s,serverInfo(s,tls)); return; }
            if("/pair".equals(path)) {
                String result=pairing.handle(q,s.getInetAddress(),tls);
                if(q.containsKey("clientpairingsecret")) status.accept(result.contains("<paired>1</paired>")?"Pairing complete — launch Phone screen in Moonlight":"Pairing rejected — retry from Moonlight");
                respond(s,result); return;
            }
            if("/unpair".equals(path) && !tls) { pairing.cancel(q.get("uniqueid"),s.getInetAddress()); respond(s,Wire.xml("<unpaired>1</unpaired>")); return; }
            if(!tls) { respond(s,Wire.error(401,"Use authenticated HTTPS")); return; }
            switch(path) {
                case "/applist": respond(s,Wire.xml("<App><AppTitle>Phone screen</AppTitle><ID>1</ID><IsHdrSupported>0</IsHdrSupported></App>")); break;
                case "/launch": case "/resume": respond(s,launch(q,s,owner,path)); break;
                case "/cancel": {
                    HostSession active=session(); if(active!=null&&!active.owner.equals(owner)) respond(s,Wire.error(599,"Session belongs to another client"));
                    else { if(active!=null) end(active,"Stopped by client"); respond(s,Wire.xml("<cancel>1</cancel>")); } break;
                }
                case "/appasset": {
                    byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS1cAAAAASUVORK5CYII="); send(s,"image/png",png); break;
                }
                default: respond(s,Wire.error(404,"Unknown endpoint"));
            }
        } catch(Exception e) { try { respond(socket,Wire.error(400,"Invalid request")); } catch(Exception ignored) {} }
        finally { connections.remove(socket); }
    }
    private synchronized String launch(Map<String,String> q,Socket s,String owner,String path) throws Exception {
        if(!capture.ready()) return Wire.error(503,"Enable screen sharing in Sunshine Mobile on the phone first");
        if(session!=null) return Wire.error(session.owner.equals(owner)?400:503,"Session already active");
        if(!"1".equals(q.getOrDefault("appid","1"))) return Wire.error(404,"Unknown app");
        if(!"0".equals(q.getOrDefault("hdrMode","0"))) return Wire.error(400,"SDR supported");
        if(Integer.parseInt(q.getOrDefault("corever","0"))<1) return Wire.error(400,"Encrypted RTSP required");
        if((Integer.parseInt(q.getOrDefault("surroundAudioInfo","196610"))&65535)!=2) return Wire.error(400,"Stereo audio required");
        byte[] key=Wire.unhex(q.get("rikey")); int keyId=(int)Long.parseLong(q.get("rikeyid"));
        HostSession[] created=new HostSession[1];
        created[0]=new HostSession(s.getInetAddress(),owner,key,keyId,capture,reason->{ try { lifecycle.execute(()->{ HostSession current=session(); if(current!=null&&current==created[0]) end(current,reason); }); } catch(RejectedExecutionException ignored) {} });
        session=created[0];
        String url="rtspenc://"+s.getLocalAddress().getHostAddress()+":48010";
        status.accept("Moonlight negotiating");
        return Wire.xml((path.equals("/resume")?"<resume>1</resume>":"<gamesession>1</gamesession>")+"<sessionUrl0>"+Wire.escape(url)+"</sessionUrl0>");
    }
    public synchronized void end(HostSession expected,String reason) { if(session!=expected) return; session=null; expected.close(); status.accept(reason+" — ready to reconnect"); }
    private String serverInfo(Socket s,boolean tls) {
        boolean busy=session()!=null;
        return Wire.xml("<hostname>Sunshine Mobile</hostname><uniqueid>"+identity.id+"</uniqueid><appversion>7.1.450.-1</appversion><GfeVersion>3.23.0.74</GfeVersion><HttpsPort>47984</HttpsPort><ExternalPort>47989</ExternalPort><MaxLumaPixelsHEVC>0</MaxLumaPixelsHEVC><ServerCodecModeSupport>1</ServerCodecModeSupport><mac>00:00:00:00:00:00</mac><LocalIP>"+s.getLocalAddress().getHostAddress()+"</LocalIP><PairStatus>"+(tls?1:0)+"</PairStatus><currentgame>"+(busy?1:0)+"</currentgame><state>SUNSHINE_SERVER_"+(busy?"BUSY":"FREE")+"</state>");
    }
    public static Map<String,String> query(String raw) throws Exception { Map<String,String> q=new HashMap<>(); if(raw!=null) for(String part:raw.split("&")) { String[] pair=part.split("=",2); String k=URLDecoder.decode(pair[0],"UTF-8"); if(q.put(k,pair.length>1?URLDecoder.decode(pair[1],"UTF-8"):"")!=null) throw new IOException("Duplicate parameter"); } return q; }
    private static void respond(Socket s,String body) throws IOException { send(s,"application/xml; charset=utf-8",body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void send(Socket s,String contentType,byte[] bytes) throws IOException { OutputStream o=s.getOutputStream(); o.write(("HTTP/1.1 200 OK\r\nContent-Type: "+contentType+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); o.write(bytes); o.flush(); }
    public synchronized void close() {
        running=false; pairing.close(); if(rtsp!=null) rtsp.close();
        for(ServerSocket listener:new ServerSocket[]{http,https}) if(listener!=null) try { listener.close(); } catch(IOException ignored) {}
        for(Socket s:connections) try { s.close(); } catch(IOException ignored) {}
        if(session!=null) { HostSession old=session; session=null; old.close(); } workers.shutdownNow(); lifecycle.shutdownNow();
    }
}
