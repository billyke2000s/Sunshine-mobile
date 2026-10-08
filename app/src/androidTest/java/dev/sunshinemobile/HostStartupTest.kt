package dev.sunshinemobile

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import dev.sunshinemobile.protocol.Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.*

/** Exercises the Android app, real system consent, Keystore/Conscrypt and native capture. */
@RunWith(AndroidJUnit4::class)
class HostStartupTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun waitFor(description: String, timeout: Long=20000, condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            if(condition()) return
            SystemClock.sleep(100)
        }
        fail("$description\n${HostRuntime.diagnostics()}")
    }
    private fun click(text: String) {
        val pattern="(?i)"+Pattern.quote(text)
        var button=device.wait(Until.findObject(By.text(Pattern.compile(pattern))),3000)
        if(button==null) {
            device.wait(Until.hasObject(By.scrollable(true)),10000)
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textMatches(pattern))
            button=device.wait(Until.findObject(By.text(Pattern.compile(pattern))),10000)
        }
        val hierarchy=java.io.ByteArrayOutputStream()
        if(button==null) device.dumpWindowHierarchy(hierarchy)
        assertNotNull("Button missing: $text\n$hierarchy\n${HostRuntime.diagnostics()}",button)
        button.click()
    }
    private fun consent(approve: Boolean) {
        val pattern=if(approve) "(?i)start now|start|share screen|share" else "(?i)cancel|not now"
        val button=device.wait(Until.findObject(By.text(Pattern.compile(pattern))),15000)
        assertNotNull("System consent button missing\n${device.lastTraversedText}\n${HostRuntime.diagnostics()}",button)
        button.click()
    }
    private fun response(socket: Socket, path: String): String = socket.use {
        it.soTimeout=10000
        it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        it.getInputStream().bufferedReader().readText()
    }
    @Suppress("DEPRECATION")
    private fun discoverLikeMoonlight() {
        val nsd=context.getSystemService(android.net.nsd.NsdManager::class.java)
        val resolved=java.util.concurrent.atomic.AtomicReference<android.net.nsd.NsdServiceInfo>()
        val error=java.util.concurrent.atomic.AtomicReference<String>()
        val resolving=java.util.concurrent.atomic.AtomicBoolean()
        val done=java.util.concurrent.CountDownLatch(1)
        val listener=object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String)=Unit
            override fun onDiscoveryStopped(type: String)=Unit
            override fun onStopDiscoveryFailed(type: String,code: Int)=Unit
            override fun onStartDiscoveryFailed(type: String,code: Int) { error.set("Discovery failed: $code"); done.countDown() }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo)=Unit
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                if(!info.serviceName.startsWith("Sunshine Mobile") || !resolving.compareAndSet(false,true)) return
                nsd.resolveService(info,object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(info: android.net.nsd.NsdServiceInfo,code: Int) { error.set("Resolution failed: $code");done.countDown() }
                    override fun onServiceResolved(info: android.net.nsd.NsdServiceInfo) { resolved.set(info);done.countDown() }
                })
            }
        }
        val cm=context.getSystemService(android.net.ConnectivityManager::class.java)
        assertNotNull("Host must bind its sockets to the LAN",cm.boundNetworkForProcess)
        nsd.discoverServices("_nvstream._tcp.",android.net.nsd.NsdManager.PROTOCOL_DNS_SD,cm.boundNetworkForProcess,context.mainExecutor,listener)
        try {
            assertTrue("Moonlight-style discovery timed out\n${HostRuntime.diagnostics()}",done.await(25,java.util.concurrent.TimeUnit.SECONDS))
            assertNull(error.get())
            val info=resolved.get();assertNotNull(info)
            assertEquals("Actual resolved SRV record must advertise the HTTP listener",47989,info.port)
            val address=info.hostAddresses.firstOrNull { it is java.net.Inet4Address }
            assertNotNull("Resolved service needs an IPv4 LAN address",address)
            assertEquals(HostRuntime.lanAddress,address!!.hostAddress)
            assertTrue(response(Socket(address,info.port),"/serverinfo").contains("<hostname>Sunshine Mobile</hostname>"))
        } finally { nsd.stopServiceDiscovery(listener) }
    }
    @Test fun hostSurvivesDeniedConsentThenCapturesAndRestarts() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("Start host")
            consent(false)
            waitFor("Network host should start without projection") { HostRuntime.server!=null }
            assertFalse(HostRuntime.captureReady)
            assertTrue(response(Socket("127.0.0.1",47989),"/serverinfo").contains("<hostname>Sunshine Mobile</hostname>"))
            waitFor("mDNS must resolve the actual Moonlight service port") { HostRuntime.discoveryPort==47989 && HostRuntime.discoveryStatus.startsWith("Advertising") }
            discoverLikeMoonlight()
            val identity=AndroidIdentity.load(context)
            val certificate=identity.certificate.encoded
            certificate.let { assertArrayEquals(it,AndroidIdentity.load(context).certificate.encoded) }
            val signed=identity.sign("pairing-test".toByteArray())
            val verifier=java.security.Signature.getInstance("SHA256withRSA")
            verifier.initVerify(identity.certificate); verifier.update("pairing-test".toByteArray()); assertTrue(verifier.verify(signed))
            // Test-only local peer: exact certificate pin, with Android Keystore-backed
            // client and server keys. Production pairing remains the only external trust path.
            HostRuntime.server!!.let {
                val field=it.javaClass.getDeclaredField("identity"); field.isAccessible=true
                val serverIdentity=field.get(it) as dev.sunshinemobile.protocol.HostIdentity
                serverIdentity.add("instrumentation",identity.certificate)
            }
            val km=object : X509ExtendedKeyManager() {
                override fun getClientAliases(t: String?,i: Array<out Principal>?)=arrayOf("test")
                override fun chooseClientAlias(t: Array<out String>?,i: Array<out Principal>?,s: Socket?)="test"
                override fun getServerAliases(t: String?,i: Array<out Principal>?)=null
                override fun chooseServerAlias(t: String?,i: Array<out Principal>?,s: Socket?)=null
                override fun getCertificateChain(a: String?)=arrayOf(identity.certificate)
                override fun getPrivateKey(a: String?): PrivateKey=identity.key
            }
            val tm=object : X509TrustManager {
                override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
                override fun checkClientTrusted(c: Array<out X509Certificate>?,a: String?)=Unit
                override fun checkServerTrusted(c: Array<out X509Certificate>,a: String?) {
                    assertArrayEquals(certificate,c[0].encoded)
                }
            }
            val tls=SSLContext.getInstance("TLS").apply { init(arrayOf(km),arrayOf(tm),SecureRandom()) }
            fun https(path: String): String {
                val socket=tls.socketFactory.createSocket("127.0.0.1",47984) as SSLSocket
                socket.enabledProtocols=arrayOf("TLSv1.2")
                socket.startHandshake()
                return response(socket,path)
            }
            assertTrue(https("/applist").contains("Phone screen"))
            assertTrue(https("/launch?appid=1&corever=1&rikey="+Wire.hex(Wire.random(16))+"&rikeyid=123").contains("status_code=\"503\""))
            click("Enable screen sharing")
            consent(true)
            waitFor("Approved projection should become ready") { HostRuntime.captureReady }
            assertTrue(https("/launch?appid=1&corever=1&rikey="+Wire.hex(Wire.random(16))+"&rikeyid=124").contains("<gamesession>1</gamesession>"))
            val session=HostRuntime.server!!.session()!!
            session.width=640; session.height=360; session.fps=30; session.bitrate=2000
            session.play()
            waitFor("MediaProjection must deliver encoded frames",30000) { HostRuntime.encodedFrames>=3 }
            assertTrue(https("/cancel").contains("<cancel>1</cancel>"))
            assertTrue(HostRuntime.captureReady)
            click("Stop host")
            waitFor("Stop must clear the online host") { HostRuntime.server==null && !HostRuntime.captureReady }
            assertNull(context.getSystemService(android.net.ConnectivityManager::class.java).boundNetworkForProcess)
            // Service cleanup is asynchronous. Wait for released network listeners.
            waitFor("HTTP port must close") { try { Socket("127.0.0.1",47989).close(); false } catch(_: Exception) { true } }
            click("Start host")
            consent(false)
            waitFor("Restart must open listeners again") { HostRuntime.server!=null }
            assertArrayEquals(certificate,AndroidIdentity.load(context).certificate.encoded)
        } finally {
            context.stopService(Intent(context,CaptureService::class.java))
        }
    }
}
