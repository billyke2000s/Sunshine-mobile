package dev.sunshinemobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** LAN advertisement started only after all authenticated host listeners are ready. */
class GameStreamDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    private var multicast: android.net.wifi.WifiManager.MulticastLock? = null

    @Synchronized
    fun start(hostName: String, port: Int = 47989) {
        check(registration == null) { "Already advertising" }
        val info = NsdServiceInfo().apply {
            serviceName = hostName
            serviceType = "_nvstream._tcp."
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i("SunshineMobile", "mDNS registered: ${serviceInfo.serviceName}")
                HostRuntime.discoveryStatus="Advertising ${serviceInfo.serviceName} on port ${serviceInfo.port}"
                HostRuntime.update(HostRuntime.status)
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("SunshineMobile", "mDNS registration failed: $errorCode")
                synchronized(this@GameStreamDiscovery) { registration = null }
                HostRuntime.discoveryStatus="Registration failed ($errorCode); use the phone’s IP address"
                HostRuntime.update(HostRuntime.status)
                releaseMulticast()
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("SunshineMobile", "mDNS unregister failed: $errorCode")
            }
        }
        registration = listener
        try {
            multicast=wifi.createMulticastLock("SunshineMobile:discovery").apply { setReferenceCounted(false); acquire() }
            HostRuntime.discoveryStatus="Registering _nvstream._tcp"
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registration = null
            releaseMulticast()
            HostRuntime.discoveryStatus="Unavailable: ${e.message}; use the phone’s IP address"
            HostRuntime.update(HostRuntime.status)
        }
    }

    @Synchronized
    fun stop() {
        releaseMulticast()
        val listener = registration ?: return
        registration = null
        nsd.unregisterService(listener)
    }
    @Synchronized
    private fun releaseMulticast() { multicast?.let { if(it.isHeld) it.release() }; multicast=null }
}
