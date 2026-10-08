package dev.sunshinemobile

import android.os.Handler
import android.os.Looper
import dev.sunshinemobile.protocol.HostServer
import dev.sunshinemobile.protocol.Pairing

object HostRuntime {
    @Volatile var server: HostServer? = null
    @Volatile var pending: Pairing.Pending? = null
    @Volatile var captureReady = false
    @Volatile var starting = false
    @Volatile var discoveryStatus = "Not advertised"
    @Volatile var discoveryPort = 0
    @Volatile var lanAddress = ""
    @Volatile var networkStatus = "Not selected"
    @Volatile var networkDetails = ""
    @Volatile var encodedFrames = 0L
    private val events = java.util.ArrayDeque<String>()
    @Volatile var status = "Host stopped"
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun update(text: String) {
        status = text
        synchronized(events) {
            events.addLast("${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())} $text")
            while(events.size>60) events.removeFirst()
        }
        android.util.Log.i("SunshineMobile",text)
        main.post { listeners.forEach { it() } }
    }
    fun fail(stage: String, error: Throwable) {
        android.util.Log.e("SunshineMobile",stage,error)
        update("$stage: ${error.javaClass.simpleName}: ${error.message ?: "No details"}")
    }
    fun diagnostics(): String = "Sunshine Mobile ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nHost online: ${server!=null}\nCapture ready: $captureReady\nLocal network: $networkStatus\nDiscovery: $discoveryStatus\nResolved discovery port: $discoveryPort\n$networkDetails\n" + synchronized(events) { events.joinToString("\n") }
    fun pairing(p: Pairing.Pending) { pending = p; update("Pair request from ${p.address}. Enter the PIN shown in Moonlight.") }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
}
