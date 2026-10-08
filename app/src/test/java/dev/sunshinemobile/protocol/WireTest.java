package dev.sunshinemobile.protocol;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;

public class WireTest {
    @Test public void gcmDomainsAndTamperRejection() throws Exception {
        byte[] key=Wire.unhex("00112233445566778899aabbccddeeff"),data=Wire.unhex("123456789abcdef0");
        byte[] iv=Wire.iv(0x12345678,'C','R');
        assertEquals("785634120000000000004352",Wire.hex(iv));
        byte[] encrypted=Wire.gcm(key,iv,data,true);
        assertArrayEquals(data,Wire.gcm(key,iv,encrypted,false));
        try { Wire.gcm(key,Wire.iv(0x12345678,'C','C'),encrypted,false); fail("Domain mismatch accepted"); } catch(AEADBadTagException expected) {}
        encrypted[0]^=1;
        try { Wire.gcm(key,iv,encrypted,false); fail("Tampered ciphertext accepted"); } catch(AEADBadTagException expected) {}
    }
    @Test public void avcNormalizationPreservesNalus() {
        byte[] annex=Wire.unhex("000000016742000000016801");
        byte[] lengths=Wire.unhex("000000026742000000026801");
        assertArrayEquals(annex,Avc.annexB(lengths)); assertArrayEquals(annex,Avc.annexB(annex));
        try { Avc.annexB(Wire.unhex("000000056742")); fail("Truncated NAL accepted"); } catch(IllegalArgumentException expected) {}
    }
    @Test public void rejectsAmbiguousQueryAndMalformedHex() throws Exception {
        try { HostServer.query("rikey=00&rikey=11"); fail("Duplicate key accepted"); } catch(java.io.IOException expected) {}
        for(String bad:new String[]{"0","zz","0g"}) { try { Wire.unhex(bad); fail("Invalid hex accepted"); } catch(IllegalArgumentException expected) {} }
        assertEquals("&lt;&amp;&quot;&apos;&gt;",Wire.escape("<&\"'>"));
    }
}
