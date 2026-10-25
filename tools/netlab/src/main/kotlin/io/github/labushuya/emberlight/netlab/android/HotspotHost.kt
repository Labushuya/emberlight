package io.github.labushuya.emberlight.netlab.android

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.util.size
import io.github.labushuya.emberlight.netlab.guest.GuestTracker
import io.github.labushuya.emberlight.netlab.net.HotspotAddressPicker
import io.github.labushuya.emberlight.netlab.report.HotspotFailure
import io.github.labushuya.emberlight.netlab.report.HotspotOutcome
import io.github.labushuya.emberlight.netlab.server.NetlabServer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.BindException
import java.net.InetAddress
import kotlin.coroutines.resume

/** Ergebnis des Starts: Ausgang des Hotspots und, falls der Testserver läuft, der Port. */
internal data class HostSession(
    val outcome: HotspotOutcome,
    val port: Int?,
)

/**
 * Gastgeber-Seite (Paket 4.0, Funktionen B1, B2, B4, B6): startet den LocalOnlyHotspot, ermittelt die Adresse der
 * Hotspot-Schnittstelle und bindet den Testserver nur an diese Adresse. Die Zählung der Gäste läuft über [guests].
 *
 * Der Hotspot wird nicht über die Tethering-Einstellungen gestartet, sondern als LocalOnlyHotspot. SSID und Passwort
 * vergibt Android. Die App kann beides nicht festlegen.
 */
internal class HotspotHost(
    private val context: Context,
    private val onEvent: (String) -> Unit,
) {
    private val wifi: WifiManager? = context.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: NetlabServer? = null

    val guests = GuestTracker()

    /** Lebt, solange der Hotspot läuft. */
    var session: HostSession? = null
        private set

    /**
     * Startet den Hotspot und wartet auf den Rückruf. Fehler kommen als [HotspotOutcome] mit Ursache zurück, nie als
     * Ausnahme. Der Testserver startet mit dem gegebenen [token].
     */
    suspend fun start(token: String): HotspotOutcome {
        stop()
        guests.reset()
        val manager = wifi ?: return failed(HotspotFailure.GENERIC)
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = started
                        val outcome = describe(started, token)
                        if (continuation.isActive) continuation.resume(outcome)
                    }

                    override fun onFailed(reason: Int) {
                        val failure = failureOf(reason)
                        onEvent("Hotspot fehlgeschlagen: ${failure.name}")
                        if (continuation.isActive) continuation.resume(failed(failure))
                    }

                    override fun onStopped() {
                        reservation = null
                        server?.stop()
                        server = null
                        session = null
                        onEvent("Hotspot durch das System beendet")
                    }
                }
            try {
                manager.startLocalOnlyHotspot(callback, handler)
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(failed(HotspotFailure.GENERIC))
            } catch (_: IllegalStateException) {
                if (continuation.isActive) continuation.resume(failed(HotspotFailure.GENERIC))
            }
        }
    }

    /** Beendet Testserver und Hotspot. Ohne laufenden Hotspot ohne Wirkung. */
    fun stop() {
        server?.stop()
        server = null
        reservation?.close()
        reservation = null
        session = null
    }

    private fun describe(
        started: WifiManager.LocalOnlyHotspotReservation,
        token: String,
    ): HotspotOutcome {
        val config = configOf(started)
        val candidates = DeviceProbe.interfaceCandidates()
        val choice = HotspotAddressPicker.pick(candidates)
        var port: Int? = null
        var server: NetlabServer? = null
        if (choice != null) {
            val address = InetAddress.getByName(choice.address)
            val netlab = NetlabServer(token = token, tracker = guests)
            port = bindServer(netlab, address)
            server = if (port != null) netlab else null
        }
        this.server = server
        session = null
        val outcome =
            HotspotOutcome(
                started = true,
                failure = null,
                ssid = config.ssid,
                passphrase = config.passphrase,
                securityType = config.securityType,
                bands = config.bands,
                hostAddress = choice?.address,
                interfaceName = choice?.interfaceName,
                candidates = candidates.map { "${it.interfaceName}: ${it.address}" },
            )
        session = HostSession(outcome, port)
        onEvent(
            if (choice == null) {
                "Hotspot läuft, aber keine Adresse erkannt"
            } else {
                "Hotspot läuft auf ${choice.interfaceName} (${choice.address})"
            },
        )
        return outcome
    }

    /** Bindet mit dem Standardport, bei Belegung mit einem freien Port. `null`, wenn gar nichts bindet. */
    private fun bindServer(
        netlab: NetlabServer,
        address: InetAddress,
    ): Int? =
        try {
            netlab.start(address, DEFAULT_PORT)
            netlab.boundAddress?.port
        } catch (_: BindException) {
            try {
                netlab.start(address, 0)
                netlab.boundAddress?.port
            } catch (_: java.io.IOException) {
                null
            }
        } catch (_: java.io.IOException) {
            null
        }

    private data class Config(
        val ssid: String?,
        val passphrase: String?,
        val securityType: String?,
        val bands: List<String>?,
    )

    @Suppress("DEPRECATION")
    private fun configOf(started: WifiManager.LocalOnlyHotspotReservation): Config =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val soft = started.softApConfiguration
            Config(
                ssid = soft.ssid,
                passphrase = soft.passphrase,
                securityType = securityName(soft.securityType),
                bands = bandNames(soft),
            )
        } else {
            val legacy = started.wifiConfiguration
            Config(
                ssid = legacy?.SSID?.removeSurrounding("\""),
                passphrase = legacy?.preSharedKey?.removeSurrounding("\""),
                securityType = null,
                bands = null,
            )
        }

    private fun securityName(type: Int): String =
        when (type) {
            SoftApConfiguration.SECURITY_TYPE_OPEN -> "offen"
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "WPA2-PSK"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA2/WPA3-Übergang"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "WPA3-SAE"
            else -> "Typ $type"
        }

    /** Bänder aus den Kanalangaben des Hotspots. `null`, wenn Android keine liefert. */
    private fun bandNames(config: SoftApConfiguration): List<String>? {
        // Kanalangaben gibt es ab API 36. Darunter bleibt das Band unbekannt, statt zu raten.
        if (Build.VERSION.SDK_INT < BAND_INFO_API) return null
        val channels = config.channels
        return (0 until channels.size).map { index ->
            when (channels.keyAt(index)) {
                SoftApConfiguration.BAND_2GHZ -> "2,4 GHz"
                SoftApConfiguration.BAND_5GHZ -> "5 GHz"
                SoftApConfiguration.BAND_6GHZ -> "6 GHz"
                SoftApConfiguration.BAND_60GHZ -> "60 GHz"
                else -> "unbekannt"
            }
        }
    }

    private fun failureOf(reason: Int): HotspotFailure =
        when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> HotspotFailure.GENERIC
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> HotspotFailure.NO_CHANNEL
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> HotspotFailure.INCOMPATIBLE_MODE
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> HotspotFailure.TETHERING_DISALLOWED
            else -> HotspotFailure.UNKNOWN
        }

    private fun failed(failure: HotspotFailure): HotspotOutcome =
        HotspotOutcome(
            started = false,
            failure = failure,
            ssid = null,
            passphrase = null,
            securityType = null,
            bands = null,
            hostAddress = null,
            interfaceName = null,
            candidates = emptyList(),
        )

    companion object {
        /** Standardport des Testservers. Ist er belegt, wählt das System einen freien. */
        const val DEFAULT_PORT = 8787
        private const val BAND_INFO_API = 36
    }
}
