package dev.sunshinemobile

import android.content.Context
import android.net.*
import android.os.Handler
import java.net.Inet4Address

/** Select the physical LAN for every host socket, including the native ENet socket. */
class LanNetwork(context: Context, private val handler: Handler, private val changed: (Network?) -> Unit) {
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var running=false
    private var previous: Network?=null
    var selected: Network?=null
        private set
    private var signature=""
    private val lost=mutableSetOf<Network>()
    private val callback=object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { lost.remove(network); refresh() }
        override fun onLost(network: Network) { lost.add(network); refresh() }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { refresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
    }
    @Synchronized fun start(): Network? {
        previous=cm.boundNetworkForProcess
        running=true
        select(false)
        val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        cm.registerNetworkCallback(request,callback,handler)
        return selected
    }
    private fun refresh() {
        if(running) try { select(true) } catch(e: Exception) { HostRuntime.fail("Local network selection failed",e) }
    }
    @Suppress("DEPRECATION")
    @Synchronized private fun select(notify: Boolean) {
        if(!running) return
        val networks=cm.allNetworks.filter { n ->
            val c=cm.getNetworkCapabilities(n)
            n !in lost && c!=null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                addresses(cm.getLinkProperties(n)).isNotEmpty()
        }.sortedBy { if(cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) 0 else 1 }
        val network=selected?.takeIf { it in networks } ?: networks.firstOrNull()
        val properties=network?.let { cm.getLinkProperties(it) }
        val ipv4=addresses(properties)
        val next="${network}:${properties?.interfaceName}:$ipv4"
        if(next==signature) return
        check(cm.bindProcessToNetwork(network)) { "Android refused the selected LAN; check VPN lockdown/network settings" }
        selected=network; signature=next
        val type=if(network!=null && cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) "Wi-Fi" else "Ethernet"
        HostRuntime.lanAddress=ipv4.firstOrNull() ?: ""
        HostRuntime.networkStatus=if(network==null) "No Wi-Fi/Ethernet IPv4 network" else "$type ${properties?.interfaceName}: ${ipv4.joinToString()}"
        HostRuntime.networkDetails=cm.allNetworks.joinToString("\n") { n ->
            val c=cm.getNetworkCapabilities(n);val p=cm.getLinkProperties(n)
            "$n interface=${p?.interfaceName} VPN=${c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} IPv4=${addresses(p).joinToString()}"
        }
        HostRuntime.update("Local network: ${HostRuntime.networkStatus}")
        if(notify) changed(network)
    }
    private fun addresses(properties: LinkProperties?)=properties?.linkAddresses.orEmpty()
        .map { it.address }.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { it.hostAddress!! }
    @Synchronized fun stop() {
        running=false
        try { cm.unregisterNetworkCallback(callback) } catch(_: Exception) {}
        if(cm.boundNetworkForProcess==selected) cm.bindProcessToNetwork(previous)
        selected=null
    }
}
