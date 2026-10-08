package dev.sunshinemobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sunshinemobile.protocol.HostIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

object AndroidIdentity {
    fun load(context: Context): HostIdentity {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Conscrypt delegates TLS RSA operations to the Keystore using pre-hashed
        // messages. The v1 key omitted NONE, which can reject TLS authentication.
        val alias = "sunshine-host-v2"
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Sunshine Mobile"))
                    .setCertificateSerialNumber(BigInteger(1, dev.sunshinemobile.protocol.Wire.random(16)))
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4102444800000L))
                    .setUserAuthenticationRequired(false).build())
            }.generateKeyPair()
        }
        val id = prefs.getString("hostId", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("hostId", it).commit()) { "Cannot persist host identity" }
        }
        val clients = mutableMapOf<String, X509Certificate>()
        prefs.all.forEach { (k,v) -> if (k.startsWith("client:") && v is String) {
            clients[k.removePrefix("client:")] = HostIdentity.parse(android.util.Base64.decode(v, android.util.Base64.DEFAULT))
        } }
        return HostIdentity(id, store.getKey(alias,null) as PrivateKey, store.getCertificate(alias) as X509Certificate, clients) { next ->
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("client:") }.forEach { editor.remove(it) }
            next.forEach { (key, cert) -> editor.putString("client:$key", android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)) }
            check(editor.commit()) { "Cannot persist paired client" }
        }
    }
}
