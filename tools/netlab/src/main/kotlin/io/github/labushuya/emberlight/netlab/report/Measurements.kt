package io.github.labushuya.emberlight.netlab.report

// Messergebnisse als reine Daten. Die Android-Seite füllt sie, der Bericht (`ReportBuilder`) wertet sie aus.
// Alle Zeiten sind Millisekunden seit dem Start des jeweiligen Laufs, sofern nicht anders angegeben.

/** Fehlerursache aus dem Rückruf von `startLocalOnlyHotspot`. */
enum class HotspotFailure { GENERIC, NO_CHANNEL, INCOMPATIBLE_MODE, TETHERING_DISALLOWED, UNKNOWN }

/** Ergebnis des Hotspot-Starts auf Gastgeber-Seite (Funktion B1, B2). */
data class HotspotOutcome(
    val started: Boolean,
    val failure: HotspotFailure?,
    val ssid: String?,
    val passphrase: String?,
    val securityType: String?,
    /** Band-Angaben, z. B. "2,4 GHz", "5 GHz". `null`, wenn Android sie nicht liefert. */
    val bands: List<String>?,
    /** IPv4 auf der Hotspot-Schnittstelle, `null` wenn nicht ermittelt. */
    val hostAddress: String?,
    /** Name der Schnittstelle, auf der der Server gebunden ist. */
    val interfaceName: String?,
    /** Alle IPv4-Kandidaten mit Schnittstellennamen, damit der Nutzer die Wahl prüfen kann. */
    val candidates: List<String>,
)

/** Ein Zeitpunkt mit Zustandsbeschreibung, z. B. „WLAN verbunden“ oder „mobile Daten validiert“. */
data class TimelineEvent(
    val atMs: Long,
    val text: String,
)

/** Fehlerart bei einem Verbindungsversuch. Die Ursache steht im Bericht, nie der Rohtext einer Exception. */
enum class ErrorKind { TIMEOUT, REFUSED, UNKNOWN_HOST, NO_ROUTE, UNAVAILABLE_NETWORK, HTTP_STATUS, IO, OTHER, NONE }

/** Ergebnis eines einzelnen Abrufs (Ping oder Upload). */
data class ProbeResult(
    val label: String,
    val ok: Boolean,
    val millis: Long?,
    val error: ErrorKind,
    val httpStatus: Int? = null,
)

/** Upload mit Durchsatz. */
data class TransferResult(
    val ok: Boolean,
    val bytes: Long,
    val millis: Long,
    val error: ErrorKind,
) {
    /** Megabit je Sekunde, aus Bytes und Dauer. `null` bei Null-Dauer. */
    val mbitPerSecond: Double?
        get() = if (millis <= 0) null else bytes * 8.0 / 1_000_000.0 / (millis / 1000.0)
}

/** Verbindungsaufbau auf Gast-Seite (Funktion C2, Messpunkt M2). */
data class ConnectOutcome(
    val ok: Boolean,
    val millis: Long?,
    val error: ErrorKind,
    /** Fehler aus Android, z. B. abgelehnte Nutzerbestätigung. Als Kurztext, ohne personenbezogene Daten. */
    val reason: String?,
)

/** Zustand des Gastes während der Verbindung (Funktion C3). */
data class GuestRun(
    val connect: ConnectOutcome?,
    val bound: List<ProbeResult>,
    val upload: TransferResult?,
    val unbound: ProbeResult?,
    val timeline: List<TimelineEvent>,
    val vpnActive: Boolean?,
)

/** Eine Zeile der Gästeliste (M4). */
data class GuestCount(
    val total: Int,
    val active: Int,
)

/** Gesamtergebnis eines Laufs, aus dem der Bericht entsteht. */
data class RunSummary(
    val role: Role,
    val host: HostRun?,
    val guest: GuestRun?,
    val guests: GuestCount?,
)

enum class Role { HOST, GUEST, NONE }

/** Ergebnis der Gastgeber-Seite. [heartbeatsMs] sind die Zeitpunkte der Lebenszeichen (M8). */
data class HostRun(
    val hotspot: HotspotOutcome?,
    val timeline: List<TimelineEvent>,
    val heartbeatsMs: List<Long>,
    val runMs: Long,
)
