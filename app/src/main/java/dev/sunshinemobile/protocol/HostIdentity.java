package dev.sunshinemobile.protocol;

import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Certificate identity is persisted by AndroidIdentity; HTTPS pins exact paired leaf identities. */
public final class HostIdentity {
    public final String id;
    public final PrivateKey key;
    public final X509Certificate certificate;
    public final byte[] pem;
    private final Map<String,X509Certificate> clients=new HashMap<>();
    private final java.util.function.Consumer<Map<String,X509Certificate>> persist;
    public HostIdentity(String id,PrivateKey key,X509Certificate cert,Map<String,X509Certificate> saved,java.util.function.Consumer<Map<String,X509Certificate>> persist) {
        this.id=id; this.key=key; certificate=cert; this.persist=persist; clients.putAll(saved);
        pem=("-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(encoded(cert))+"\n-----END CERTIFICATE-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static byte[] encoded(X509Certificate c) { try { return c.getEncoded(); } catch(CertificateEncodingException e) { throw new IllegalStateException(e); } }
    public static X509Certificate parse(byte[] b) throws CertificateException { return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(b)); }
    public synchronized boolean authorized(X509Certificate c) { if(c==null) return false; for(X509Certificate stored:clients.values()) if(MessageDigest.isEqual(encoded(c),encoded(stored))) return true; return false; }
    public synchronized void add(String id,X509Certificate c) { Map<String,X509Certificate> next=new HashMap<>(clients); next.put(id,c); persist.accept(next); clients.clear(); clients.putAll(next); }
    public synchronized void clearClients() { persist.accept(Collections.emptyMap()); clients.clear(); }
    public synchronized int clientCount() { return clients.size(); }
    public byte[] sign(byte[] b) throws GeneralSecurityException { Signature s=Signature.getInstance("SHA256withRSA"); s.initSign(key); s.update(b); return s.sign(); }
    public SSLContext tls() throws GeneralSecurityException,java.io.IOException {
        KeyManager km=new X509ExtendedKeyManager() {
            public String[] getClientAliases(String type,Principal[] issuers) { return null; }
            public String chooseClientAlias(String[] types,Principal[] issuers,java.net.Socket socket) { return null; }
            public String[] getServerAliases(String type,Principal[] issuers) { return type.startsWith("RSA")?new String[]{"host"}:null; }
            public String chooseServerAlias(String type,Principal[] issuers,java.net.Socket socket) { return type.startsWith("RSA")?"host":null; }
            public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{certificate}; }
            public PrivateKey getPrivateKey(String alias) { return key; }
        };
        SSLContext ctx=SSLContext.getInstance("TLS"); ctx.init(new KeyManager[]{km},new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){ return new X509Certificate[0]; }
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException { throw new CertificateException("Server role only"); }
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException { if(chain.length==0 || !authorized(chain[0])) throw new CertificateException("Unpaired certificate"); }
        }},new SecureRandom()); return ctx;
    }
}
