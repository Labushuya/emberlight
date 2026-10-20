package io.github.labushuya.emberlight.share.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

/**
 * IPv4-Adresse der WLAN-Schnittstelle mit Präfixlänge und Standard-Gateway. Daraus ergibt sich das Subnetz des Servers.
 * Das Gateway gehört zur Erkennung „dasselbe Netz“ (ADR 0017, WLAN).
 */
data class WifiAddress(
    val ipv4: String,
    val prefixLength: Int,
    val gateway: String? = null,
) {
    val subnet: Ipv4Subnet get() = Ipv4Subnet(ipv4, prefixLength)
}

/**
 * Liefert die WLAN-Adresse. Abstrakt, damit Netzwechsel im Test ohne Gerät ausgelöst werden können (ADR 0013, Netz).
 */
interface WifiNetworkProvider {
    /** Aktuelle IPv4-Adresse des WLAN oder `null`, wenn kein WLAN mit Adresse steht. */
    fun currentAddress(): WifiAddress?

    /**
     * Zustand über die Zeit. Der erste Wert ist der aktuelle Stand. Danach kommt jeder Wechsel der Adresse oder der
     * Verlust des WLAN als `null`.
     */
    fun changes(): Flow<WifiAddress?>
}

/** Wählt die IPv4-Adresse aus den Adressen einer Schnittstelle. Loopback und Link-Local zählen nicht. */
internal fun pickIpv4(addresses: Iterable<Pair<java.net.InetAddress, Int>>): WifiAddress? =
    addresses
        .firstOrNull { (address, _) ->
            address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
        }?.let { (address, prefix) -> WifiAddress(address.hostAddress.orEmpty(), prefix) }

/** Implementierung über [ConnectivityManager]. Braucht `ACCESS_NETWORK_STATE` (ADR 0013, Android-API-Fakten). */
class ConnectivityWifiNetworkProvider(
    context: Context,
) : WifiNetworkProvider {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // allNetworks ist seit API 31 veraltet. Der Server braucht das WLAN auch dann, wenn es nicht das Standardnetz ist
    // (etwa bei aktiven Mobildaten). Das Standardnetz (activeNetwork) würde es dort verfehlen.
    @Suppress("DEPRECATION")
    override fun currentAddress(): WifiAddress? {
        val network =
            connectivity.allNetworks.firstOrNull { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
        return connectivity.getLinkProperties(network)?.let(::addressOf)
    }

    override fun changes(): Flow<WifiAddress?> =
        callbackFlow {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
                        trySend(addressOf(linkProperties))
                    }

                    override fun onLost(network: Network) {
                        trySend(null)
                    }
                }
            trySend(currentAddress())
            connectivity.registerNetworkCallback(request, callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()

    private fun addressOf(properties: LinkProperties): WifiAddress? {
        val address =
            pickIpv4(properties.linkAddresses.map { link: LinkAddress -> link.address to link.prefixLength })
                ?: return null
        val gateway =
            properties.routes
                .firstOrNull { route -> route.isDefaultRoute && route.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
        return address.copy(gateway = gateway)
    }
}
