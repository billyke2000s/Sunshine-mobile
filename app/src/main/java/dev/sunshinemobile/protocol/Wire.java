package dev.sunshinemobile.protocol;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Wire {
    private Wire() {}
    public static byte[] random(int n) { byte[] b=new byte[n]; new SecureRandom().nextBytes(b); return b; }
    public static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b) s.append(String.format(Locale.ROOT,"%02x",v&255)); return s.toString(); }
    public static byte[] unhex(String s) {
        if(s==null || s.length()>32768 || (s.length()&1)!=0) throw new IllegalArgumentException("Invalid hex");
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++) { int h=Character.digit(s.charAt(i*2),16),l=Character.digit(s.charAt(i*2+1),16); if(h<0||l<0) throw new IllegalArgumentException("Invalid hex"); b[i]=(byte)((h<<4)|l); } return b;
    }
    public static byte[] concat(byte[]... arrays) { ByteArrayOutputStream o=new ByteArrayOutputStream(); for(byte[] b:arrays) o.write(b,0,b.length); return o.toByteArray(); }
    public static byte[] sha(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch(GeneralSecurityException e) { throw new IllegalStateException(e); } }
    public static byte[] ecb(byte[] key, byte[] data, boolean enc) throws GeneralSecurityException {
        if(data.length%16!=0) throw new GeneralSecurityException("Invalid block length");
        Cipher c=Cipher.getInstance("AES/ECB/NoPadding"); c.init(enc?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES")); return c.doFinal(data);
    }
    public static byte[] iv(int seq,char origin,char stream) { byte[] iv=new byte[12]; ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putInt(seq); iv[10]=(byte)origin; iv[11]=(byte)stream; return iv; }
    public static byte[] gcm(byte[] key,byte[] iv,byte[] data,boolean encrypt) throws GeneralSecurityException {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); return c.doFinal(data);
    }
    public static byte[] cbc(byte[] key,int ivBase,int sequence,byte[] opus) throws GeneralSecurityException {
        byte[] iv=new byte[16]; ByteBuffer.wrap(iv).putInt(ivBase+sequence);
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv)); return c.doFinal(opus);
    }
    public static byte[] read(InputStream in,int n) throws IOException { if(n<0||n>65536) throw new IOException("Message too large"); byte[] b=new byte[n]; int p=0; while(p<n) { int r=in.read(b,p,n-p); if(r<0) throw new EOFException(); p+=r; } return b; }
    public static String line(InputStream in,int max) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); int c; while((c=in.read())!=-1) { if(c=='\n') return b.toString("US-ASCII").replace("\r",""); if(b.size()>=max) throw new IOException("Line too long"); b.write(c); } if(b.size()==0) return null; throw new EOFException(); }
    public static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
    public static String xml(String inner) { return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">"+inner+"</root>"; }
    public static String error(int code,String msg) { return "<root status_code=\""+code+"\" status_message=\""+escape(msg)+"\"/>"; }
}
