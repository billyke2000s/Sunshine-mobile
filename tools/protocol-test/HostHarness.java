import dev.sunshinemobile.protocol.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;

/** Runs the production protocol classes with deterministic synthetic capture, never Android stubs. */
public final class HostHarness {
    public static void main(String[] args) throws Exception {
        Path dir=Paths.get(args[0]);
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(dir.resolve("host.pk8"))));
        X509Certificate cert=HostIdentity.parse(Files.readAllBytes(dir.resolve("host.crt")));
        Map<String,X509Certificate> clients=new HashMap<>();
        if(Files.exists(dir.resolve("client.der"))) clients.put("test-client",HostIdentity.parse(Files.readAllBytes(dir.resolve("client.der"))));
        HostIdentity identity=new HostIdentity("7fdbfa1b-129b-4b4b-9786-8fd9c673d08b",key,cert,clients,next->{ try { if(!next.isEmpty()) Files.write(dir.resolve("client.der"),next.values().iterator().next().getEncoded()); } catch(Exception e) { throw new RuntimeException(e); } });
        byte[] frame=Files.readAllBytes(dir.resolve("frame.h264"));
        HostSession.Capture capture=new HostSession.Capture() {
            public void start(HostSession s) {
                Thread t=new Thread(()->{
                    long opus=Native.opusCreate(s.audioDuration*48);
                    short[] pcm=new short[s.audioDuration*48*2];
                    long start=System.nanoTime(),video=0,audio=0;
                    try { while(!s.isClosed()) {
                        long now=(System.nanoTime()-start)/1000;
                        if(now>=video) { s.transport.video(frame,now,true); video+=1_000_000/s.fps; }
                        if(now>=audio) {
                            for(int i=0;i<pcm.length/2;i++) { short sample=(short)(Math.sin((audio/1_000_000.0+i/48000.0)*440*2*Math.PI)*8000); pcm[i*2]=sample; pcm[i*2+1]=sample; }
                            s.transport.audio(Native.opusEncode(opus,pcm,s.audioDuration*48)); audio+=s.audioDuration*1000;
                        }
                        Thread.sleep(1);
                    } } catch(Exception e) { s.fail("Synthetic capture: "+e); } finally { Native.opusDestroy(opus); }
                },"synthetic-capture"); t.setDaemon(true); t.start();
            }
            public void stop(HostSession s) {}
            public void requestIdr() {}
        };
        final HostServer[] h=new HostServer[1];
        h[0]=new HostServer(identity,capture,p->{new Thread(()->{try {Thread.sleep(50); h[0].pairing.approve(p.id,"1234");}catch(Exception e){throw new RuntimeException(e);}}).start();},System.out::println);
        h[0].start(); Runtime.getRuntime().addShutdownHook(new Thread(h[0]::close));
        Files.writeString(dir.resolve("ready"),"ready");
        Thread.currentThread().join();
    }
}
