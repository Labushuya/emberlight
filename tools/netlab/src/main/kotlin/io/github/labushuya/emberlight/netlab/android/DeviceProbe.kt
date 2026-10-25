package io.github.labushuya.emberlight.netlab.android

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.labushuya.emberlight.netlab.capability.DeviceFacts
import io.github.labushuya.emberlight.netlab.capability.NetworkFact
import io.github.labushuya.emberlight.netlab.capability.PermissionFact
import io.github.labushuya.emberlight.netlab.capability.Transport
import io.github.labushuya.emberlight.netlab.net.AddressCandidate
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Liest Fähigkeiten, Netze, Berechtigungen und Schnittstellen aus dem System (Paket 4.0, Funktion A).
 * Eine API, die auf dem Gerät fehlt, bleibt `null` und wird nicht vermutet.
 */
internal object DeviceProbe {
    private const val PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val PERMISSION_NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"
    private const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val PERMISSION_CAMERA = "android.permission.CAMERA"

    /** Pflichtberechtigungen für den Hotspot je API-Stand. Die Liste steuert auch die Anzeige. */
    fun permissionNames(sdkInt: Int): List<String> =
        buildList {
            if (sdkInt <= Build.VERSION_CODES.S_V2) add(PERMISSION_FINE_LOCATION)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(PERMISSION_NEARBY_WIFI)
            if (sdkInt >= LOCAL_NETWORK_API) add(PERMISSION_LOCAL_NETWORK)
            add(PERMISSION_CAMERA)
        }

    fun hasPermission(
        context: Context,
        name: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    /** Berechtigungen mit Stand und dem Hinweis, ob sie auf diesem API-Stand zutreffen. */
    private fun permissionFacts(
        context: Context,
        sdk: Int,
    ): List<PermissionFact> =
        listOf(
            permissionFact(context, "ACCESS_FINE_LOCATION", sdk <= Build.VERSION_CODES.S_V2),
            permissionFact(context, "NEARBY_WIFI_DEVICES", sdk >= Build.VERSION_CODES.TIRAMISU),
            permissionFact(context, "ACCESS_LOCAL_NETWORK", sdk >= LOCAL_NETWORK_API),
            permissionFact(context, "CAMERA", applies = true),
        )

    private fun permissionFact(
        context: Context,
        short: String,
        applies: Boolean,
    ): PermissionFact =
        PermissionFact(
            name = short,
            granted = hasPermission(context, "android.permission.$short"),
            applies = applies,
        )

    fun collect(context: Context): DeviceFacts {
        val sdk = Build.VERSION.SDK_INT
        val wifi = context.getSystemService(WifiManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val location = context.getSystemService(LocationManager::class.java)
        return DeviceFacts(
            sdkInt = sdk,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            staApConcurrency =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
                ) {
                    wifi?.isStaApConcurrencySupported
                } else {
                    null
                },
            staConcurrencyLocalOnly =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S
                ) {
                    wifi?.isStaConcurrencyForLocalOnlyConnectionsSupported
                } else {
                    null
                },
            band5GHz = wifi?.is5GHzBandSupported,
            networks = networks(connectivity),
            permissions = permissionFacts(context, sdk),
            locationServicesEnabled =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    location?.isLocationEnabled
                } else {
                    null
                },
        )
    }

    /** Alle aktiven Netze mit Transporten, Validierung und Standardnetz. Netze ohne Transport werden ausgelassen. */
    @Suppress("DEPRECATION")
    fun networks(connectivity: ConnectivityManager?): List<NetworkFact> {
        if (connectivity == null) return emptyList()
        val defaultNetwork = connectivity.activeNetwork
        return connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val transports = transportsOf(capabilities)
            if (transports.isEmpty()) return@mapNotNull null
            NetworkFact(
                transports = transports,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isDefault = network == defaultNetwork,
            )
        }
    }

    fun transportsOf(capabilities: NetworkCapabilities): Set<Transport> =
        buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
            if (isEmpty() && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(Transport.OTHER)
        }

    /** Alle privaten IPv4-Adressen der aktiven Schnittstellen. Die Auswahl trifft `HotspotAddressPicker`. */
    fun interfaceCandidates(): List<AddressCandidate> =
        try {
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { runCatching { it.isUp }.getOrDefault(false) && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses
                        .toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { AddressCandidate(iface.name, it.hostAddress.orEmpty()) }
                }
        } catch (_: java.net.SocketException) {
            emptyList()
        }

    /** Erste Android-Version mit der Berechtigung ACCESS_LOCAL_NETWORK (Android 17, API 37). */
    private const val LOCAL_NETWORK_API = 37
}
