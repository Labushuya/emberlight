package io.github.labushuya.emberlight.netlab.report

import io.github.labushuya.emberlight.netlab.capability.Capabilities
import io.github.labushuya.emberlight.netlab.capability.DeviceFacts
import io.github.labushuya.emberlight.netlab.capability.NetworkFact
import io.github.labushuya.emberlight.netlab.capability.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportBuilderTest {
    private val caps =
        Capabilities.summarize(
            DeviceFacts(
                sdkInt = 34,
                manufacturer = "Acme",
                model = "Phone 1",
                staApConcurrency = true,
                staConcurrencyLocalOnly = null,
                band5GHz = true,
                networks = listOf(NetworkFact(setOf(Transport.CELLULAR), validated = true, isDefault = true)),
                permissions = emptyList(),
                locationServicesEnabled = true,
            ),
        )
    private val meta = ReportMeta(appVersion = "4.0-test", createdAt = "2026-10-25 12:00")
    private val passphrase = "geheimesPasswort"
    private val ssid = "EL-Netz-Geheim"

    private val hotspot =
        HotspotOutcome(
            started = true,
            failure = null,
            ssid = ssid,
            passphrase = passphrase,
            securityType = "WPA2-PSK",
            bands = listOf("5 GHz"),
            hostAddress = "192.168.49.1",
            interfaceName = "ap0",
            candidates = listOf("ap0: 192.168.49.1"),
        )

    private val host =
        HostRun(
            hotspot = hotspot,
            timeline = listOf(TimelineEvent(0, "WLAN verbunden"), TimelineEvent(5_000, "WLAN getrennt")),
            heartbeatsMs = listOf(0, 60_000, 120_000, 300_000),
            runMs = 300_000,
        )

    private val guest =
        GuestRun(
            connect = ConnectOutcome(ok = true, millis = 4200, error = ErrorKind.NONE, reason = null),
            bound = listOf(ProbeResult("gebunden /ping", ok = true, millis = 12, error = ErrorKind.NONE)),
            upload = TransferResult(ok = true, bytes = 10_000_000, millis = 2_000, error = ErrorKind.NONE),
            unbound = ProbeResult("ungebunden /ping", ok = false, millis = null, error = ErrorKind.TIMEOUT),
            timeline = listOf(TimelineEvent(1_000, "Mobilfunk validiert"), TimelineEvent(9_000, "Verbindung verloren")),
            vpnActive = false,
        )

    private val text =
        ReportBuilder.build(meta, caps, RunSummary(Role.HOST, host, guest, GuestCount(total = 3, active = 2)))

    @Test
    fun containsAllMeasurementSections() {
        listOf("M1 ", "M2 ", "M3 ", "M4 ", "M5 ", "M6 ", "M7 ", "M8 ", "M9 ").forEach { id ->
            assertTrue(text.contains(id), "Abschnitt $id fehlt")
        }
    }

    @Test
    fun masksSsidAndPassphrase() {
        assertFalse(text.contains(ssid))
        assertFalse(text.contains(passphrase))
        assertTrue(text.contains("SSID: E*** (14 Zeichen)"))
        assertTrue(text.contains("Passwort: ******** (16 Zeichen)"))
    }

    @Test
    fun reportsWifiLossAndLongestPause() {
        assertTrue(text.contains("WLAN wurde durch den Hotspot getrennt"))
        assertTrue(text.contains("Längste Pause zwischen zwei Lebenszeichen: 3 min 0 s"))
        assertTrue(text.contains("Grenze überschritten"))
    }

    @Test
    fun reportsUploadThroughput() {
        assertTrue(text.contains("Upload 10 MB: ok, 2000 ms, 40.0 Mbit/s"))
    }

    @Test
    fun unboundFailureIsShownWithReason() {
        assertTrue(text.contains("Ungebunden (Standardnetz): ungebunden /ping: fehlgeschlagen (Zeitüberschreitung)"))
    }

    @Test
    fun reportsDeviceAndStageWithoutPersonalData() {
        assertTrue(text.contains("Gerät: Acme Phone 1, Android API 34, Stufe 1 (App, WifiNetworkSpecifier)"))
        assertTrue(text.contains("Gleichzeitige lokale Verbindung (STA-Concurrency): nicht verfügbar"))
    }

    @Test
    fun guestCountAppearsInM4() {
        assertTrue(text.contains("Gäste seit Start: 3"))
        assertTrue(text.contains("Davon aktiv (Kontakt in den letzten Minuten): 2"))
    }

    @Test
    fun lostConnectionIsExplainedInM9() {
        assertTrue(text.contains("Verlust um 00:00:09: Verbindung verloren"))
    }

    @Test
    fun failedHotspotNamesCause() {
        val failed = host.copy(hotspot = hotspot.copy(started = false, failure = HotspotFailure.INCOMPATIBLE_MODE))
        val report = ReportBuilder.build(meta, caps, RunSummary(Role.HOST, failed, null, null))
        assertTrue(report.contains("Hotspot: fehlgeschlagen (inkompatibler Modus, Tethering ist aktiv)"))
        assertTrue(report.contains("Keine Gast-Messung in diesem Lauf."))
    }

    @Test
    fun maskingHandlesEmptyAndShortValues() {
        assertEquals("(leer)", ReportBuilder.maskSsid(""))
        assertEquals("A*** (1 Zeichen)", ReportBuilder.maskSsid("A"))
        assertEquals("******** (0 Zeichen)", ReportBuilder.maskPassphrase(""))
    }
}
