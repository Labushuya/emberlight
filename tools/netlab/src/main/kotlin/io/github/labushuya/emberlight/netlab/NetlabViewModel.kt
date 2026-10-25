package io.github.labushuya.emberlight.netlab

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.labushuya.emberlight.netlab.android.DeviceProbe
import io.github.labushuya.emberlight.netlab.android.GuestClient
import io.github.labushuya.emberlight.netlab.android.HostSession
import io.github.labushuya.emberlight.netlab.android.HotspotHost
import io.github.labushuya.emberlight.netlab.capability.Capabilities
import io.github.labushuya.emberlight.netlab.capability.CapabilitySummary
import io.github.labushuya.emberlight.netlab.capability.NetworkChanges
import io.github.labushuya.emberlight.netlab.guest.GuestSnapshot
import io.github.labushuya.emberlight.netlab.payload.JoinPayload
import io.github.labushuya.emberlight.netlab.payload.PayloadError
import io.github.labushuya.emberlight.netlab.payload.WifiQr
import io.github.labushuya.emberlight.netlab.report.ConnectOutcome
import io.github.labushuya.emberlight.netlab.report.GuestCount
import io.github.labushuya.emberlight.netlab.report.GuestRun
import io.github.labushuya.emberlight.netlab.report.HostRun
import io.github.labushuya.emberlight.netlab.report.HotspotOutcome
import io.github.labushuya.emberlight.netlab.report.ProbeResult
import io.github.labushuya.emberlight.netlab.report.ReportBuilder
import io.github.labushuya.emberlight.netlab.report.ReportMeta
import io.github.labushuya.emberlight.netlab.report.Role
import io.github.labushuya.emberlight.netlab.report.RunSummary
import io.github.labushuya.emberlight.netlab.report.TimelineEvent
import io.github.labushuya.emberlight.netlab.report.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Zustand der Oberfläche. Texte entstehen erst in der Oberfläche, hier stehen nur Daten und Schlüssel. */
data class NetlabUiState(
    val role: Role = Role.NONE,
    val capabilities: CapabilitySummary? = null,
    val hostActive: Boolean = false,
    val hotspot: HotspotOutcome? = null,
    val hostPayload: JoinPayload? = null,
    val hostWifiQr: String? = null,
    val hostBrowserUrl: String? = null,
    val hostTimeline: List<TimelineEvent> = emptyList(),
    val guests: GuestSnapshot? = null,
    val guestPayload: JoinPayload? = null,
    val guestPayloadError: PayloadError? = null,
    val guestConnected: Boolean = false,
    val guestConnect: ConnectOutcome? = null,
    val guestTimeline: List<TimelineEvent> = emptyList(),
    val guestRun: GuestRun? = null,
    val busy: Boolean = false,
    val report: String? = null,
)

/**
 * Steuert Gastgeber- und Gast-Seite (Paket 4.0). Die Messungen laufen auf Hintergrund-Dispatchern, die Oberfläche
 * beobachtet [state]. Ein Lauf sammelt Zeitlinie, Gäste und Ergebnisse, aus denen der Bericht entsteht.
 */
class NetlabViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetlabUiState())
    val state: StateFlow<NetlabUiState> = _state.asStateFlow()

    private var hostStartedAt = 0L
    private var hostJob: Job? = null
    private var hostBeats = CopyOnWriteArrayList<Long>()
    private var hostEvents = CopyOnWriteArrayList<TimelineEvent>()
    private var hostRunStartedAt = 0L

    private var guestStartedAt = 0L
    private var guestEvents = CopyOnWriteArrayList<TimelineEvent>()
    private var guestWatchJob: Job? = null

    private val host = HotspotHost(application) { text -> hostEvent(text) }
    private val guest = GuestClient(application)

    fun refreshCapabilities() {
        val facts = DeviceProbe.collect(getApplication())
        val summary = Capabilities.summarize(facts)
        _state.update { it.copy(capabilities = summary) }
        Log.i(
            LOG_TAG,
            "Fähigkeiten: API ${facts.sdkInt}, ${facts.manufacturer} ${facts.model}, Netze ${summary.networks.size}",
        )
    }

    fun chooseRole(role: Role) {
        _state.update { it.copy(role = role) }
    }

    /** Startet den Hotspot, den Testserver und die Zeitlinie. Der Aufrufer prüft die Berechtigungen vorher. */
    fun startHost() {
        if (_state.value.hostActive || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            hostStartedAt = SystemClock.elapsedRealtime()
            hostRunStartedAt = hostStartedAt
            hostEvents = CopyOnWriteArrayList()
            hostBeats = CopyOnWriteArrayList()
            val token = JoinPayload.newToken()
            val outcome = host.start(token)
            val session: HostSession? = host.session
            val payload = session?.let { buildHostPayload(it, token) }
            _state.update {
                it.copy(
                    busy = false,
                    hostActive = outcome.started,
                    hotspot = outcome,
                    hostPayload = payload,
                    hostWifiQr = payload?.let { p -> WifiQr.encode(p.ssid, p.passphrase) },
                    hostBrowserUrl = payload?.let { p -> "http://${p.host}:${p.port}/browser" },
                    hostTimeline = hostEvents.toList(),
                )
            }
            Log.i(LOG_TAG, "Hotspot: gestartet=${outcome.started}, Schnittstelle=${outcome.interfaceName}")
            if (outcome.started) startHostWatch()
        }
    }

    fun stopHost() {
        hostJob?.cancel()
        hostJob = null
        val beats = hostBeats.toList()
        host.stop()
        hostEvent("Hotspot beendet")
        _state.update {
            it.copy(
                hostActive = false,
                hostPayload = null,
                hostWifiQr = null,
                hostBrowserUrl = null,
                hostTimeline = hostEvents.toList(),
            )
        }
        Log.i(LOG_TAG, "Hotspot gestoppt nach ${beats.size} Lebenszeichen")
    }

    /**
     * Liest den Beitritts-QR-Code (oder eingefügten Text). Ergebnis in [NetlabUiState.guestPayload] oder dem Fehler.
     */
    fun acceptJoinText(raw: String) {
        when (val result = JoinPayload.decode(raw)) {
            is JoinPayload.DecodeResult.Success -> {
                _state.update { it.copy(guestPayload = result.payload, guestPayloadError = null) }
            }

            is JoinPayload.DecodeResult.Failure -> {
                _state.update { it.copy(guestPayload = null, guestPayloadError = result.reason) }
            }
        }
    }

    /** Verbindet mit dem Hotspot (API 29 und neuer) oder wartet auf das manuell verbundene Netz (API 26 bis 28). */
    fun connectGuest() {
        val payload = _state.value.guestPayload ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, guestConnect = null) }
            guestStartedAt = SystemClock.elapsedRealtime()
            guestEvents = CopyOnWriteArrayList()
            startGuestWatch()
            val outcome =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    guest.connect(payload) { guestEvent(it) }
                } else {
                    guest.awaitManualNetwork(payload.host) { guestEvent(it) }
                }
            _state.update {
                it.copy(
                    busy = false,
                    guestConnected = outcome.ok,
                    guestConnect = outcome,
                    guestTimeline = guestEvents.toList(),
                )
            }
            Log.i(LOG_TAG, "Verbindung: ok=${outcome.ok}, Fehler=${outcome.error}")
        }
    }

    /** Führt die Gast-Messungen aus: gebundener Ping, Upload mit 10 MB, ungebundener Ping über das Standardnetz. */
    fun runGuestTests() {
        val payload = _state.value.guestPayload ?: return
        if (!_state.value.guestConnected) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result =
                withContext(Dispatchers.IO) {
                    val bound = guest.pingBound(payload.host, payload.port, payload.token)
                    val upload = guest.upload(payload.host, payload.port, payload.token, UPLOAD_BYTES)
                    val unbound = guest.pingDefault(payload.host, payload.port, payload.token)
                    val vpn =
                        DeviceProbe.networks(androidConnectivity()).any {
                            it.transports.contains(io.github.labushuya.emberlight.netlab.capability.Transport.VPN)
                        }
                    GuestResult(bound, upload, unbound, vpn)
                }
            val run =
                GuestRun(
                    connect = _state.value.guestConnect,
                    bound = listOf(result.bound),
                    upload = result.upload,
                    unbound = result.unbound,
                    timeline = guestEvents.toList(),
                    vpnActive = result.vpn,
                )
            _state.update { it.copy(busy = false, guestRun = run, guestTimeline = guestEvents.toList()) }
            Log.i(
                LOG_TAG,
                "Messung: gebunden=${result.bound.ok}, upload=${result.upload.ok}, ungebunden=${result.unbound.ok}",
            )
        }
    }

    fun disconnectGuest() {
        guestWatchJob?.cancel()
        guestWatchJob = null
        guest.disconnect()
        guestEvent("Verbindung getrennt")
        _state.update { it.copy(guestConnected = false, guestTimeline = guestEvents.toList()) }
    }

    /** Erzeugt den Bericht aus allen vorliegenden Daten und legt ihn im Zustand ab. Jede Zeile geht auch in den Log. */
    fun buildReport() {
        val current = _state.value
        val caps = current.capabilities ?: return
        val host =
            if (current.hotspot != null) {
                HostRun(
                    hotspot = current.hotspot,
                    timeline = current.hostTimeline,
                    heartbeatsMs = hostBeats.toList(),
                    runMs = SystemClock.elapsedRealtime() - hostRunStartedAt,
                )
            } else {
                null
            }
        val guests = current.guests?.let { GuestCount(total = it.total, active = it.active) }
        val summary = RunSummary(role = current.role, host = host, guest = current.guestRun, guests = guests)
        val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val text = ReportBuilder.build(ReportMeta(appVersion = appVersionName(), createdAt = createdAt), caps, summary)
        text.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(LOG_TAG, it) }
        _state.update { it.copy(report = text) }
    }

    fun closeReport() {
        _state.update { it.copy(report = null) }
    }

    /** Lebenszeichen, Zeitlinie und Gästezählung des Gastgebers, alle paar Sekunden. */
    private fun startHostWatch() {
        hostJob?.cancel()
        hostJob =
            viewModelScope.launch(Dispatchers.Main) {
                var previous: List<io.github.labushuya.emberlight.netlab.capability.NetworkFact>? = null
                var nextBeat = 0L
                while (isActive && host.session != null) {
                    val now = SystemClock.elapsedRealtime()
                    val networks = DeviceProbe.networks(androidConnectivity())
                    val changes = NetworkChanges.describe(previous, networks)
                    changes.forEach { hostEvent(it) }
                    previous = networks
                    if (now >= nextBeat) {
                        hostBeats += now - hostStartedAt
                        nextBeat = now + HEARTBEAT_MS
                    }
                    val snapshot = host.guests.snapshot(GUEST_ACTIVE_WINDOW_MS)
                    _state.update { it.copy(guests = snapshot, hostTimeline = hostEvents.toList()) }
                    delay(WATCH_INTERVAL_MS)
                }
            }
    }

    /** Netzzustand während des Gast-Laufs. Endet mit [disconnectGuest]. */
    private fun startGuestWatch() {
        guestWatchJob?.cancel()
        guestWatchJob =
            viewModelScope.launch(Dispatchers.Main) {
                var previous: List<io.github.labushuya.emberlight.netlab.capability.NetworkFact>? = null
                while (isActive) {
                    val networks = DeviceProbe.networks(androidConnectivity())
                    NetworkChanges.describe(previous, networks).forEach { guestEvent(it) }
                    previous = networks
                    delay(WATCH_INTERVAL_MS)
                }
            }
    }

    private fun buildHostPayload(
        session: HostSession,
        token: String,
    ): JoinPayload? {
        val outcome = session.outcome
        val ssid = outcome.ssid
        val pass = outcome.passphrase
        val address = outcome.hostAddress
        val port = session.port
        if (ssid == null || pass == null || address == null || port == null) return null
        if (!JoinPayload.isIpv4(address)) return null
        return JoinPayload(ssid = ssid, passphrase = pass, host = address, port = port, token = token)
    }

    private fun appVersionName(): String =
        runCatching {
            getApplication<Application>()
                .packageManager
                .getPackageInfo(
                    getApplication<Application>().packageName,
                    0,
                ).versionName
        }.getOrNull().orEmpty()

    private fun androidConnectivity() =
        getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)

    private fun hostEvent(text: String) {
        hostEvents += TimelineEvent(SystemClock.elapsedRealtime() - hostStartedAt, text)
        Log.i(LOG_TAG, "Gastgeber: $text")
    }

    private fun guestEvent(text: String) {
        guestEvents += TimelineEvent(SystemClock.elapsedRealtime() - guestStartedAt, text)
        Log.i(LOG_TAG, "Gast: $text")
    }

    private data class GuestResult(
        val bound: ProbeResult,
        val upload: TransferResult,
        val unbound: ProbeResult,
        val vpn: Boolean,
    )

    override fun onCleared() {
        host.stop()
        guest.disconnect()
    }

    companion object {
        const val LOG_TAG = "EmberlightNetlab"
        const val UPLOAD_BYTES = 10L * 1_000_000
        private const val HEARTBEAT_MS = 60_000L
        private const val WATCH_INTERVAL_MS = 3_000L
        private const val GUEST_ACTIVE_WINDOW_MS = 120_000L
    }
}
