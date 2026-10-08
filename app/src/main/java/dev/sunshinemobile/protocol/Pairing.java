package dev.sunshinemobile.protocol;

import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;

/** GameStream generation-7 pairing, matching Moonlight Android PairingManager. */
public final class Pairing implements AutoCloseable {
    public static final class Pending {
        public final String id, address;
        public final CompletableFuture<String> pin=new CompletableFuture<>();
        Pending(String id,String address) { this.id=id; this.address=address; }
    }
    private static final class Attempt {
        final String id,address; final X509Certificate cert; final long expires=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
        final Pending pending; byte[] key,secret,challenge,clientHash; int phase;
        Attempt(String id,String address,X509Certificate c) { this.id=id; this.address=address; cert=c; pending=new Pending(id,address); }
    }
    private final HostIdentity identity;
    private final java.util.function.Consumer<Pending> notify;
    private Attempt attempt;
    private long lastAttempt;
    public Pairing(HostIdentity identity,java.util.function.Consumer<Pending> notify) { this.identity=identity; this.notify=notify; }
    public synchronized void approve(String id,String pin) { if(attempt!=null && attempt.id.equals(id) && pin.matches("[0-9]{4}") && attempt.expires>System.nanoTime()) attempt.pending.pin.complete(pin); }
    public String handle(Map<String,String> q,InetAddress remote,boolean authenticated) throws Exception {
        if("pairchallenge".equals(q.get("phrase"))) return authenticated?Wire.xml("<paired>1</paired>"):Wire.error(401,"Client authentication required");
        String id=q.get("uniqueid"),address=remote.getHostAddress();
        if(id==null||id.length()>128) return Wire.error(400,"Missing client ID");
        if("getservercert".equals(q.get("phrase"))) {
            byte[] salt=Wire.unhex(q.get("salt")); if(salt.length!=16) return Wire.error(400,"Invalid salt");
            X509Certificate cert=HostIdentity.parse(Wire.unhex(q.get("clientcert")));
            if(!"RSA".equals(cert.getPublicKey().getAlgorithm()) && !"EC".equals(cert.getPublicKey().getAlgorithm())) return Wire.error(400,"Unsupported certificate");
            Attempt a;
            synchronized(this) {
                expire(); if(attempt!=null || System.nanoTime()-lastAttempt<TimeUnit.SECONDS.toNanos(3)) return Wire.xml("<paired>0</paired>");
                a=new Attempt(id,address,cert); attempt=a; lastAttempt=System.nanoTime();
            }
            notify.accept(a.pending);
            try {
                String pin=a.pending.pin.get(120,TimeUnit.SECONDS);
                synchronized(this) { if(attempt!=a) return Wire.error(408,"Pairing expired"); a.key=Arrays.copyOf(Wire.sha(Wire.concat(salt,pin.getBytes(java.nio.charset.StandardCharsets.UTF_8))),16); a.phase=1; }
                return Wire.xml("<paired>1</paired><plaincert>"+Wire.hex(identity.pem)+"</plaincert>");
            } catch(Exception e) { synchronized(this) { if(attempt==a) attempt=null; } return Wire.error(408,"PIN approval expired"); }
        }
        synchronized(this) {
            expire(); Attempt a=attempt;
            if(a==null||!a.id.equals(id)||!a.address.equals(address)) return Wire.error(400,"No pairing attempt");
            try {
                if(q.containsKey("clientchallenge") && a.phase==1) {
                    byte[] enc=Wire.unhex(q.get("clientchallenge")); if(enc.length!=16) throw new GeneralSecurityException("Challenge length");
                    byte[] challenge=Wire.ecb(a.key,enc,false); a.secret=Wire.random(16); a.challenge=Wire.random(16); a.phase=2;
                    return Wire.xml("<paired>1</paired><challengeresponse>"+Wire.hex(Wire.ecb(a.key,Wire.concat(Wire.sha(Wire.concat(challenge,identity.certificate.getSignature(),a.secret)),a.challenge),true))+"</challengeresponse>");
                }
                if(q.containsKey("serverchallengeresp") && a.phase==2) {
                    byte[] enc=Wire.unhex(q.get("serverchallengeresp")); if(enc.length!=32) throw new GeneralSecurityException("Response length");
                    a.clientHash=Wire.ecb(a.key,enc,false); a.phase=3;
                    return Wire.xml("<paired>1</paired><pairingsecret>"+Wire.hex(Wire.concat(a.secret,identity.sign(a.secret)))+"</pairingsecret>");
                }
                if(q.containsKey("clientpairingsecret") && a.phase==3) {
                    byte[] b=Wire.unhex(q.get("clientpairingsecret")); if(b.length<=16) throw new GeneralSecurityException("Signature length");
                    byte[] secret=Arrays.copyOf(b,16),sig=Arrays.copyOfRange(b,16,b.length);
                    Signature verify=Signature.getInstance("RSA".equals(a.cert.getPublicKey().getAlgorithm())?"SHA256withRSA":"SHA256withECDSA"); verify.initVerify(a.cert); verify.update(secret);
                    boolean ok=verify.verify(sig) && MessageDigest.isEqual(a.clientHash,Wire.sha(Wire.concat(a.challenge,a.cert.getSignature(),secret)));
                    attempt=null; if(ok) identity.add(id,a.cert); return Wire.xml("<paired>"+(ok?1:0)+"</paired>");
                }
                throw new GeneralSecurityException("Out of order pairing");
            } catch(Exception e) { attempt=null; return Wire.xml("<paired>0</paired>"); }
        }
    }
    private void expire() { if(attempt!=null && attempt.expires<System.nanoTime()) { attempt.pending.pin.cancel(false); attempt=null; } }
    public synchronized void cancel(String id,InetAddress remote) {
        if(attempt!=null && attempt.id.equals(id) && attempt.address.equals(remote.getHostAddress())) { attempt.pending.pin.cancel(false); attempt=null; }
    }
    public synchronized void close() { if(attempt!=null) attempt.pending.pin.cancel(false); attempt=null; }
}
