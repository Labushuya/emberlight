package io.github.labushuya.emberlight.netlab.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import io.github.labushuya.emberlight.netlab.net.ErrorClassifier
import io.github.labushuya.emberlight.netlab.net.SubnetCheck
import io.github.labushuya.emberlight.netlab.payload.JoinPayload
import io.github.labushuya.emberlight.netlab.report.ConnectOutcome
import io.github.labushuya.emberlight.netlab.report.ErrorKind
import io.github.labushuya.emberlight.netlab.report.ProbeResult
import io.github.labushuya.emberlight.netlab.report.TransferResult
import io.github.labushuya.emberlight.netlab.server.NetlabServer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Gast-Seite (Paket 4.0, Funktionen C2, C3). Verbindet mit dem Hotspot des Gastgebers und misst über das gebundene
 * und das Standardnetz.
 *
 * Ab Android 10 (API 29) über `WifiNetworkSpecifier`, ohne Nutzerschritt außer der Bestätigung von Android. Ab API 26
 * bis 28 ist die Verbindung manuell: Der Nutzer verbindet sich in den WLAN-Einstellungen, [awaitManualNetwork] erkennt
 * das Netz an der Adresse im Hotspot-Subnetz.
 */
internal class GuestClient(
    context: Context,
) {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Netz des Hotspots, sobald verbunden. Ohne Verbindung `null`. */
    var network: Network? = null
        private set

    /** Verbindung über `WifiNetworkSpecifier` (API 29 und neuer). Liefert den Ausgang, keine Ausnahme. */
    suspend fun connect(
        payload: JoinPayload,
        onEvent: (String) -> Unit,
    ): ConnectOutcome {
        disconnect()
        val manager = connectivity ?: return failedConnect(ErrorKind.OTHER, "Verbindungsdienst fehlt")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return failedConnect(ErrorKind.OTHER, "Automatische Verbindung ab Android 10")
        }
        val startedAt = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { continuation ->
            val specifier =
                WifiNetworkSpecifier
                    .Builder()
                    .setSsid(payload.ssid)
                    .setWpa2Passphrase(payload.passphrase)
                    .build()
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
            val listener =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(available: Network) {
                        network = available
                        onEvent("Hotspot verbunden")
                        if (continuation.isActive) {
                            val millis = SystemClock.elapsedRealtime() - startedAt
                            continuation.resume(
                                ConnectOutcome(ok = true, millis = millis, error = ErrorKind.NONE, reason = null),
                            )
                        }
                    }

                    override fun onUnavailable() {
                        network = null
                        onEvent("Verbindung nicht zustande gekommen")
                        if (continuation.isActive) {
                            continuation.resume(
                                ConnectOutcome(
                                    ok = false,
                                    millis = null,
                                    error = ErrorKind.UNAVAILABLE_NETWORK,
                                    reason = "Zeitüberschreitung oder abgelehnt",
                                ),
                            )
                        }
                    }

                    override fun onLost(lost: Network) {
                        if (network == lost) network = null
                        onEvent("Hotspot-Verbindung verloren")
                    }
                }
            callback = listener
            manager.requestNetwork(request, listener, CONNECT_TIMEOUT_MS)
            continuation.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(listener) } }
        }
    }

    /**
     * Ab API 26 bis 28: wartet auf ein WLAN, dessen Adresse im Subnetz des Hotspots liegt. Der Nutzer verbindet sich
     * vorher von Hand. Die Prüfung über die Adresse kommt ohne WLAN-Namen aus. Der Name würde ab API 27
     * Standortzugriff verlangen.
     */
    suspend fun awaitManualNetwork(
        hostAddress: String,
        onEvent: (String) -> Unit,
    ): ConnectOutcome {
        disconnect()
        val manager = connectivity ?: return failedConnect(ErrorKind.OTHER, "Verbindungsdienst fehlt")
        val startedAt = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { continuation ->
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            val listener =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(
                        candidate: Network,
                        linkProperties: android.net.LinkProperties,
                    ) {
                        val addresses = linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress }
                        if (network == null && SubnetCheck.sameSlash24(hostAddress, addresses)) {
                            network = candidate
                            onEvent("Hotspot-Netz erkannt")
                            if (continuation.isActive) {
                                val millis = SystemClock.elapsedRealtime() - startedAt
                                continuation.resume(
                                    ConnectOutcome(ok = true, millis = millis, error = ErrorKind.NONE, reason = null),
                                )
                            }
                        }
                    }

                    override fun onLost(lost: Network) {
                        if (network == lost) network = null
                        onEvent("Hotspot-Verbindung verloren")
                    }
                }
            callback = listener
            manager.registerNetworkCallback(request, listener)
            continuation.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(listener) } }
        }
    }

    /** Trennt die Verbindung und gibt das Netz frei. Ohne Verbindung ohne Wirkung. */
    fun disconnect() {
        val listener = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(listener) }
        network = null
    }

    /** `GET /ping` über das gebundene Netz des Hotspots. */
    fun pingBound(
        host: String,
        port: Int,
        token: String,
    ): ProbeResult {
        val bound =
            network
                ?: return ProbeResult(
                    "gebunden /ping",
                    ok = false,
                    millis = null,
                    error = ErrorKind.UNAVAILABLE_NETWORK,
                )
        return probe("gebunden /ping", URL("http://$host:$port/ping"), token, viaNetwork = bound)
    }

    /** `GET /ping` über das Standardnetz, ohne Bindung. Zeigt, ob ein Browser ohne Bindung scheitern würde. */
    fun pingDefault(
        host: String,
        port: Int,
        token: String,
    ): ProbeResult = probe("ungebunden /ping", URL("http://$host:$port/ping"), token, viaNetwork = null)

    /** Lädt [bytes] per POST hoch und misst die Dauer. Der Upload geht über das gebundene Netz. */
    fun upload(
        host: String,
        port: Int,
        token: String,
        bytes: Long,
    ): TransferResult {
        val bound =
            network ?: return TransferResult(ok = false, bytes = 0, millis = 0, error = ErrorKind.UNAVAILABLE_NETWORK)
        val connection =
            try {
                bound.openConnection(URL("http://$host:$port/upload")) as HttpURLConnection
            } catch (e: java.io.IOException) {
                return TransferResult(ok = false, bytes = 0, millis = 0, error = ErrorClassifier.classify(e))
            }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = UPLOAD_TIMEOUT_MS
            connection.setRequestProperty(NetlabServer.TOKEN_HEADER, token)
            connection.setFixedLengthStreamingMode(bytes)
            val chunk = ByteArray(CHUNK_BYTES)
            val startedAt = System.nanoTime()
            connection.outputStream.use { out ->
                var remaining = bytes
                while (remaining > 0) {
                    val size = minOf(chunk.size.toLong(), remaining).toInt()
                    out.write(chunk, 0, size)
                    remaining -= size
                }
            }
            val status = connection.responseCode
            connection.inputStream.use { it.readBytes() }
            val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            if (status == HttpURLConnection.HTTP_OK) {
                TransferResult(ok = true, bytes = bytes, millis = millis, error = ErrorKind.NONE)
            } else {
                TransferResult(ok = false, bytes = bytes, millis = millis, error = ErrorKind.HTTP_STATUS)
            }
        } catch (e: java.io.IOException) {
            TransferResult(ok = false, bytes = 0, millis = 0, error = ErrorClassifier.classify(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun probe(
        label: String,
        url: URL,
        token: String,
        viaNetwork: Network?,
    ): ProbeResult {
        val startedAt = System.nanoTime()
        val connection =
            try {
                (viaNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            } catch (e: java.io.IOException) {
                return ProbeResult(label, ok = false, millis = null, error = ErrorClassifier.classify(e))
            }
        return try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.setRequestProperty(NetlabServer.TOKEN_HEADER, token)
            val status = connection.responseCode
            connection.inputStream.use { it.readBytes() }
            val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            if (status == HttpURLConnection.HTTP_OK) {
                ProbeResult(label, ok = true, millis = millis, error = ErrorKind.NONE, httpStatus = status)
            } else {
                ProbeResult(label, ok = false, millis = millis, error = ErrorKind.HTTP_STATUS, httpStatus = status)
            }
        } catch (e: java.io.IOException) {
            ProbeResult(label, ok = false, millis = null, error = ErrorClassifier.classify(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun failedConnect(
        error: ErrorKind,
        reason: String,
    ): ConnectOutcome = ConnectOutcome(ok = false, millis = null, error = error, reason = reason)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val TIMEOUT_MS = 10_000
        const val PROBE_TIMEOUT_MS = 5_000
        const val UPLOAD_TIMEOUT_MS = 60_000
        const val CHUNK_BYTES = 64 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
