package dev.sunshinemobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Local network advertisement for a future GameStream-compatible host.
 *
 * This is deliberately NOT started until authenticated pairing and session
 * negotiation are implemented. Advertising a non-functional host would
 * mislead Moonlight clients.
 */
class GameStreamDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

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
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("SunshineMobile", "mDNS registration failed: $errorCode")
                synchronized(this@GameStreamDiscovery) { registration = null }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("SunshineMobile", "mDNS unregister failed: $errorCode")
            }
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registration = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        val listener = registration ?: return
        registration = null
        nsd.unregisterService(listener)
    }
}
