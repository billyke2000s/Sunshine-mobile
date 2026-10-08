package dev.sunshinemobile

import android.os.Handler
import android.os.Looper
import dev.sunshinemobile.protocol.HostServer
import dev.sunshinemobile.protocol.Pairing

object HostRuntime {
    @Volatile var server: HostServer? = null
    @Volatile var pending: Pairing.Pending? = null
    @Volatile var status = "Host stopped"
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun update(text: String) { status = text; main.post { listeners.forEach { it() } } }
    fun pairing(p: Pairing.Pending) { pending = p; update("Pair request from ${p.address}. Enter the PIN shown in Moonlight.") }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
}
