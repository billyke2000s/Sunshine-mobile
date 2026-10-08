package dev.sunshinemobile

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Registration and a separate resolution check are deliberately distinct. */
class GameStreamDiscovery(context: Context) {
    private val nsd=context.applicationContext.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener?=null
    private val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    private var multicast: android.net.wifi.WifiManager.MulticastLock?=null
    @Synchronized
    fun start(hostName: String, network: Network?, httpPort: Int=47989) {
        check(registration==null) { "Already advertising" }
        require(httpPort in 1..65535)
        val info=NsdServiceInfo().apply {
            serviceName=hostName; serviceType="_nvstream._tcp."
            setPort(httpPort)
            if(Build.VERSION.SDK_INT>=33) setNetwork(network)
        }
        HostRuntime.discoveryPort=0
        val listener=object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@GameStreamDiscovery) {
                    if(registration!==this) return
                    // Android registration callbacks may contain only the assigned name.
                    // Their getPort()==0 is NOT the SRV port published on the LAN.
                    Log.i("SunshineMobile","mDNS registered ${serviceInfo.serviceName}; requested TCP $httpPort; callback port ${serviceInfo.port}")
                    HostRuntime.discoveryStatus="Registered ${serviceInfo.serviceName}, TCP $httpPort; checking local resolution"
                    HostRuntime.update(HostRuntime.status)
                    resolve(serviceInfo.serviceName,network,httpPort,this)
                }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo,errorCode: Int) {
                synchronized(this@GameStreamDiscovery) {
                    if(registration!==this) return
                    registration=null; releaseMulticast()
                    HostRuntime.discoveryStatus="Registration failed ($errorCode); add ${HostRuntime.lanAddress} in Moonlight"
                    HostRuntime.update(HostRuntime.status)
                }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo)=Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo,errorCode: Int) {
                Log.w("SunshineMobile","mDNS unregister failed: $errorCode")
            }
        }
        registration=listener
        try {
            multicast=wifi.createMulticastLock("SunshineMobile:discovery").apply { setReferenceCounted(false); acquire() }
            HostRuntime.discoveryStatus="Registering _nvstream._tcp on selected LAN"
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener)
        } catch(e: Exception) {
            registration=null; releaseMulticast()
            HostRuntime.discoveryStatus="Unavailable: ${e.message}; add ${HostRuntime.lanAddress} in Moonlight"
            HostRuntime.update(HostRuntime.status)
        }
    }
    @Suppress("DEPRECATION")
    private fun resolve(name: String,network: Network?,expectedPort: Int,owner: NsdManager.RegistrationListener) {
        val query=NsdServiceInfo().apply {
            serviceName=name; serviceType="_nvstream._tcp."
            if(Build.VERSION.SDK_INT>=33) setNetwork(network)
        }
        try { nsd.resolveService(query,object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(this@GameStreamDiscovery) {
                    if(registration!==owner) return
                    HostRuntime.discoveryPort=info.port
                    val addresses=if(Build.VERSION.SDK_INT>=34) info.hostAddresses else listOfNotNull(info.host)
                    val address=addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                    HostRuntime.discoveryStatus=if(info.port==expectedPort && address!=null)
                        "Advertising $name at $address:${info.port}; local resolution confirmed"
                    else "Local resolution invalid (port ${info.port}, IPv4 $address); add ${HostRuntime.lanAddress} in Moonlight"
                    HostRuntime.update("Discovery: ${HostRuntime.discoveryStatus}")
                }
            }
            override fun onResolveFailed(info: NsdServiceInfo,errorCode: Int) {
                synchronized(this@GameStreamDiscovery) {
                    if(registration!==owner) return
                    HostRuntime.discoveryStatus="Registered $name, TCP $expectedPort; local resolution failed ($errorCode). Add ${HostRuntime.lanAddress} in Moonlight"
                    HostRuntime.update("Discovery: ${HostRuntime.discoveryStatus}")
                }
            }
        }) } catch(e: Exception) {
            HostRuntime.discoveryStatus="Registered $name, TCP $expectedPort; resolution error: ${e.message}"
            HostRuntime.update("Discovery: ${HostRuntime.discoveryStatus}")
        }
    }
    @Synchronized fun stop() {
        val listener=registration; registration=null
        releaseMulticast()
        HostRuntime.discoveryPort=0
        if(listener!=null) try { nsd.unregisterService(listener) } catch(_: Exception) {}
    }
    private fun releaseMulticast() { multicast?.let { if(it.isHeld) it.release() }; multicast=null }
}
