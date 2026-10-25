package io.github.labushuya.emberlight.netlab.report

import io.github.labushuya.emberlight.netlab.capability.Capabilities
import io.github.labushuya.emberlight.netlab.capability.CapabilitySummary
import io.github.labushuya.emberlight.netlab.capability.Flag
import io.github.labushuya.emberlight.netlab.capability.NetworkFact
import io.github.labushuya.emberlight.netlab.capability.Transport

/** Metadaten des Berichts. Der Zeitpunkt kommt von außen, damit der Aufbau deterministisch bleibt. */
data class ReportMeta(
    val appVersion: String,
    val createdAt: String,
)

/**
 * Baut den Messbericht als Text (Paket 4.0, Funktion D). Gliederung nach M1–M9 aus docs/KONZEPT-M4.md, Abschnitt 13.
 *
 * Datenschutz: Keine Kennung, keine Geräte-Seriennummer, keine Adresse außer der Hotspot-IP. SSID und Passwort stehen
 * nie im Klartext im Bericht. Der Token steht nie im Bericht, weil er nicht Teil der Daten ist.
 */
object ReportBuilder {
    fun build(
        meta: ReportMeta,
        caps: CapabilitySummary,
        run: RunSummary,
    ): String =
        buildString {
            appendLine("emberlight Netlab, Messbericht")
            appendLine("Erstellt: ${meta.createdAt}")
            appendLine("App-Version: ${meta.appVersion}")
            appendLine(
                "Gerät: ${caps.manufacturer} ${caps.model}, Android API ${caps.sdkInt}, ${stageLabel(caps.sdkInt)}",
            )
            appendLine("Rolle: ${roleLabel(run.role)}")
            appendLine()
            appendSection("Fähigkeiten", capabilityLines(caps))
            appendSection("M1 Gastgeber: Hotspot, mobile Daten und WLAN gleichzeitig", m1(caps, run))
            appendSection("M2 Gast: WifiNetworkSpecifier, Netz-Bindung, mobile Daten parallel", m2(run))
            appendSection(
                "M3 Browser-Gast im Hotspot",
                listOf("Manuell. Anleitung in tools/netlab/README.md, Abschnitt M3."),
            )
            appendSection("M4 Maximale Gästezahl", m4(run))
            appendSection("M5 VPN-Apps", m5(caps, run))
            appendSection("M6 Gerätematrix", m6(caps, run))
            appendSection("M7 Browser-Grenze", listOf("Manuell. Anleitung in tools/netlab/README.md, Abschnitt M7."))
            appendSection("M8 Lange Laufzeit", m8(run))
            appendSection("M9 Ende und Netzwechsel", m9(run))
        }.trimEnd() + "\n"

    /** SSID maskiert: erster Buchstabe und Länge. Das Passwort zeigt nur die Länge. */
    fun maskSsid(ssid: String): String = if (ssid.isEmpty()) "(leer)" else "${ssid.first()}*** (${ssid.length} Zeichen)"

    fun maskPassphrase(passphrase: String): String = "******** (${passphrase.length} Zeichen)"

    fun stageLabel(sdkInt: Int): String =
        when {
            sdkInt >= 29 -> "Stufe 1 (App, WifiNetworkSpecifier)"
            sdkInt >= 26 -> "Stufe 2 (App eingeschränkt, manuelle Verbindung)"
            else -> "nicht unterstützt (unter API 26)"
        }

    private fun roleLabel(role: Role): String =
        when (role) {
            Role.HOST -> "Gastgeber"
            Role.GUEST -> "Gast"
            Role.NONE -> "keine Messung"
        }

    private fun StringBuilder.appendSection(
        title: String,
        lines: List<String>,
    ) {
        appendLine(title)
        if (lines.isEmpty()) appendLine("  (keine Angaben)") else lines.forEach { appendLine("  $it") }
        appendLine()
    }

    private fun capabilityLines(caps: CapabilitySummary): List<String> =
        buildList {
            add("STA+AP-Betrieb: ${flagText(caps.staAp)}")
            add("Gleichzeitige lokale Verbindung (STA-Concurrency): ${flagText(caps.staLocalOnly)}")
            add("5-GHz-Band für Hotspot: ${flagText(caps.band5GHz)}")
            add("Standortdienst: ${flagText(caps.locationServices)}")
            if (caps.networks.isEmpty()) add("Aktive Netze: keine")
            caps.networks.forEach { add("Netz: ${networkText(it)}") }
            caps.permissions.forEach { add("Berechtigung ${it.name}: ${if (it.granted) "erteilt" else "fehlt"}") }
            addAll(Capabilities.notes(caps))
        }

    private fun m1(
        caps: CapabilitySummary,
        run: RunSummary,
    ): List<String> {
        val host = run.host ?: return listOf("Keine Gastgeber-Messung in diesem Lauf.")
        val lines = mutableListOf<String>()
        lines += "STA+AP-Betrieb laut Gerät: ${flagText(caps.staAp)}"
        lines += hotspotLines(host.hotspot)
        lines += "Laufzeit: ${formatDuration(host.runMs)}"
        lines += "Verlauf:"
        host.timeline.forEach { lines += "  ${formatTime(it.atMs)} ${it.text}" }
        lines += "Ergebnis: ${wifiVerdict(host.timeline)}"
        return lines
    }

    private fun m2(run: RunSummary): List<String> {
        val guest = run.guest ?: return listOf("Keine Gast-Messung in diesem Lauf.")
        val lines = mutableListOf<String>()
        val connect = guest.connect
        if (connect == null) {
            lines += "Verbindungsaufbau: nicht ausgeführt"
        } else {
            lines += "Verbindungsaufbau: ${if (connect.ok) "ok" else "fehlgeschlagen (${errorText(connect.error)})"}" +
                (connect.millis?.let { ", $it ms" } ?: "")
            connect.reason?.let { lines += "Hinweis Android: $it" }
        }
        guest.bound.forEach { lines += probeText(it) }
        guest.upload?.let { lines += transferText(it) }
        guest.unbound?.let { lines += "Ungebunden (Standardnetz): ${probeText(it)}" }
        guest.vpnActive?.let { lines += "VPN aktiv: ${if (it) "ja" else "nein"}" }
        if (guest.timeline.isNotEmpty()) {
            lines += "Verlauf der Netze:"
            guest.timeline.forEach { lines += "  ${formatTime(it.atMs)} ${it.text}" }
        }
        return lines
    }

    private fun m4(run: RunSummary): List<String> {
        val guests = run.guests ?: return listOf("Keine Gästezählung in diesem Lauf.")
        return listOf(
            "Gäste seit Start: ${guests.total}",
            "Davon aktiv (Kontakt in den letzten Minuten): ${guests.active}",
            "Grenze laut App: keine Angabe. Android nennt die Gästezahl nicht (erwartet, siehe Konzept, Abschnitt 13).",
        )
    }

    private fun m5(
        caps: CapabilitySummary,
        run: RunSummary,
    ): List<String> {
        val vpn = run.guest?.vpnActive ?: caps.vpnActive
        return listOf(
            "VPN während der Messung: ${if (vpn) "ja" else "nein"}",
            "Je App (a) mit LAN-Ausnahme und (b) ohne Ausnahme, Hotspot und WLAN: manuell, Ergebnis je App eintragen.",
        )
    }

    private fun m6(
        caps: CapabilitySummary,
        run: RunSummary,
    ): List<String> {
        val hotspot = run.host?.hotspot
        val started =
            when {
                hotspot == null -> "nicht versucht"
                hotspot.started -> "gestartet"
                else -> "fehlgeschlagen (${failureText(hotspot.failure)})"
            }
        return listOf(
            "Gerät: ${caps.manufacturer} ${caps.model}, API ${caps.sdkInt}",
            "Stufe: ${stageLabel(caps.sdkInt)}",
            "LocalOnlyHotspot: $started",
        )
    }

    private fun m8(run: RunSummary): List<String> {
        val host = run.host ?: return listOf("Keine Gastgeber-Messung in diesem Lauf.")
        val beats = host.heartbeatsMs
        val longestGapMs = beats.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        return listOf(
            "Laufzeit: ${formatDuration(host.runMs)}",
            "Lebenszeichen: ${beats.size}",
            "Längste Pause zwischen zwei Lebenszeichen: ${formatDuration(longestGapMs)}",
            "Grenze laut Konzept: 2 Minuten (Schwelle aus PLAN-M3).",
            "Verdikt: ${if (longestGapMs <= 2 * 60_000L) "innerhalb der Grenze" else "Grenze überschritten"}",
        )
    }

    private fun m9(run: RunSummary): List<String> {
        val guest = run.guest ?: return listOf("Keine Gast-Messung in diesem Lauf.")
        val lost = guest.timeline.filter { it.text.contains("verloren") }
        if (lost.isEmpty()) return listOf("Kein Verbindungsverlust im Verlauf beobachtet.")
        return lost.map { "Verlust um ${formatTime(it.atMs)}: ${it.text}" } +
            "Erklärung für den Gast: Der Hotspot wurde beendet oder das Netz ist weg (Konzept, ADR 0014)."
    }

    private fun hotspotLines(outcome: HotspotOutcome?): List<String> {
        if (outcome == null) return listOf("Hotspot: nicht versucht")
        if (!outcome.started) return listOf("Hotspot: fehlgeschlagen (${failureText(outcome.failure)})")
        return buildList {
            add("Hotspot: gestartet")
            outcome.ssid?.let { add("SSID: ${maskSsid(it)}") }
            outcome.passphrase?.let { add("Passwort: ${maskPassphrase(it)}") }
            outcome.securityType?.let { add("Sicherheit: $it") }
            outcome.bands?.let { add("Band: ${if (it.isEmpty()) "unbekannt" else it.joinToString(", ")}") }
            add("Hotspot-Adresse: ${outcome.hostAddress ?: "nicht ermittelt"}")
            add("Schnittstelle: ${outcome.interfaceName ?: "nicht ermittelt"}")
            if (outcome.candidates.size > 1) add("Weitere Kandidaten: ${outcome.candidates.joinToString("; ")}")
        }
    }

    private fun wifiVerdict(timeline: List<TimelineEvent>): String {
        val wifiLost = timeline.any { it.text.contains("WLAN getrennt") }
        return if (wifiLost) "WLAN wurde durch den Hotspot getrennt (kein paralleles WLAN)" else "WLAN blieb verbunden"
    }

    private fun probeText(result: ProbeResult): String {
        val status =
            if (result.ok) {
                "ok"
            } else {
                "fehlgeschlagen (${errorText(
                    result.error,
                )}${result.httpStatus?.let { ", HTTP $it" } ?: ""})"
            }
        return "${result.label}: $status" + (result.millis?.let { ", $it ms" } ?: "")
    }

    private fun transferText(result: TransferResult): String {
        val mbit = result.mbitPerSecond?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "n/a"
        val status = if (result.ok) "ok" else "fehlgeschlagen (${errorText(result.error)})"
        return "Upload ${result.bytes / 1_000_000} MB: $status, ${result.millis} ms, $mbit Mbit/s"
    }

    private fun networkText(network: NetworkFact): String {
        val transports = network.transports.sortedBy { it.ordinal }.joinToString("+") { transportName(it) }
        val flags =
            buildList {
                if (network.validated) add("validiert") else add("nicht validiert")
                if (network.isDefault) add("Standardnetz")
            }
        return "$transports (${flags.joinToString(", ")})"
    }

    private fun transportName(transport: Transport): String =
        when (transport) {
            Transport.WIFI -> "WLAN"
            Transport.CELLULAR -> "Mobilfunk"
            Transport.VPN -> "VPN"
            Transport.ETHERNET -> "Ethernet"
            Transport.OTHER -> "Sonstige"
        }

    private fun flagText(flag: Flag): String =
        when (flag) {
            Flag.YES -> "ja"
            Flag.NO -> "nein"
            Flag.UNAVAILABLE -> "nicht verfügbar"
        }

    private fun failureText(failure: HotspotFailure?): String =
        when (failure) {
            HotspotFailure.GENERIC -> "allgemeiner Fehler"
            HotspotFailure.NO_CHANNEL -> "kein Kanal frei"
            HotspotFailure.INCOMPATIBLE_MODE -> "inkompatibler Modus, Tethering ist aktiv"
            HotspotFailure.TETHERING_DISALLOWED -> "Tethering ist vom Gerät gesperrt"
            HotspotFailure.UNKNOWN, null -> "unbekannter Fehler"
        }

    private fun errorText(error: ErrorKind): String =
        when (error) {
            ErrorKind.TIMEOUT -> "Zeitüberschreitung"
            ErrorKind.REFUSED -> "Verbindung abgelehnt"
            ErrorKind.UNKNOWN_HOST -> "Adresse nicht auflösbar"
            ErrorKind.NO_ROUTE -> "keine Route ins Netz"
            ErrorKind.UNAVAILABLE_NETWORK -> "Netz nicht verfügbar"
            ErrorKind.HTTP_STATUS -> "HTTP-Fehler"
            ErrorKind.IO -> "Netzfehler"
            ErrorKind.OTHER -> "anderer Fehler"
            ErrorKind.NONE -> "kein Fehler"
        }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }
}
