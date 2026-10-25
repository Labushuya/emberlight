package io.github.labushuya.emberlight.netlab.capability

/**
 * Fähigkeiten des Geräts und Zustand der Netze, aufbereitet als reine Daten (Paket 4.0, Funktion A).
 *
 * Die Android-Seite (`android/DeviceProbe`) füllt diese Werte aus den Systemaufrufen. Hier wird nur ausgewertet.
 * Eine API, die auf dem Gerät nicht existiert, wird als [Flag.UNAVAILABLE] geführt, nie als „nein“ und nie geraten.
 */
data class DeviceFacts(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    /** `WifiManager.isStaApConcurrencySupported()`, API 30+. `null`, wenn die API auf diesem Gerät fehlt. */
    val staApConcurrency: Boolean?,
    /** `isStaConcurrencyForLocalOnlyConnectionsSupported()`, API 31+. `null`, wenn die API fehlt. */
    val staConcurrencyLocalOnly: Boolean?,
    /** `is5GHzBandSupported()`. `null`, wenn der Aufruf nicht möglich war. */
    val band5GHz: Boolean?,
    val networks: List<NetworkFact>,
    val permissions: List<PermissionFact>,
    /** Standortdienst eingeschaltet. `null`, wenn nicht ermittelbar. */
    val locationServicesEnabled: Boolean?,
)

enum class Transport { WIFI, CELLULAR, VPN, ETHERNET, OTHER }

/** Ein aktives Netz. [validated] entspricht `NET_CAPABILITY_VALIDATED`, [isDefault] dem Standardnetz. */
data class NetworkFact(
    val transports: Set<Transport>,
    val validated: Boolean,
    val isDefault: Boolean,
)

data class PermissionFact(
    val name: String,
    val granted: Boolean,
    /** Gilt nur auf bestimmten Android-Versionen. Nicht zutreffende Einträge zeigen „nicht zutreffend“. */
    val applies: Boolean,
)

/** Dreiwertig: ja, nein, oder die API gibt es auf diesem Gerät nicht. */
enum class Flag { YES, NO, UNAVAILABLE }

fun flagOf(value: Boolean?): Flag =
    when (value) {
        true -> Flag.YES
        false -> Flag.NO
        null -> Flag.UNAVAILABLE
    }

/**
 * Auswertung für Anzeige und Bericht. Die Werte bleiben typisiert, der Text kommt aus der Oberfläche bzw. dem Bericht.
 */
data class CapabilitySummary(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val staAp: Flag,
    val staLocalOnly: Flag,
    val band5GHz: Flag,
    val locationServices: Flag,
    val networks: List<NetworkFact>,
    val permissions: List<PermissionFact>,
) {
    /** Ein Netz mit WLAN-Transport ist aktiv. */
    val wifiActive: Boolean get() = networks.any { Transport.WIFI in it.transports }

    /** Mobile Daten sind aktiv und validiert. */
    val cellularValidated: Boolean
        get() = networks.any { Transport.CELLULAR in it.transports && it.validated }

    /** Ein VPN-Netz ist aktiv. */
    val vpnActive: Boolean get() = networks.any { Transport.VPN in it.transports }

    /** Das Standardnetz, für unbegrenzte Anfragen ohne Netz-Bindung. */
    val defaultNetwork: NetworkFact? get() = networks.firstOrNull { it.isDefault }
}

object Capabilities {
    fun summarize(facts: DeviceFacts): CapabilitySummary =
        CapabilitySummary(
            sdkInt = facts.sdkInt,
            manufacturer = facts.manufacturer,
            model = facts.model,
            staAp = flagOf(facts.staApConcurrency),
            staLocalOnly = flagOf(facts.staConcurrencyLocalOnly),
            band5GHz = flagOf(facts.band5GHz),
            locationServices = flagOf(facts.locationServicesEnabled),
            networks =
                facts.networks.sortedWith(
                    compareByDescending<NetworkFact> { it.isDefault }.thenBy { it.transports.size },
                ),
            permissions = facts.permissions.filter { it.applies },
        )

    /** Prüft, ob die Netz-Zusammenstellung für die Messung passt. Liefert Hinweise, keine Entscheidung. */
    fun notes(summary: CapabilitySummary): List<String> =
        buildList {
            if (summary.staAp ==
                Flag.NO
            ) {
                add("STA+AP-Betrieb wird vom Gerät nicht unterstützt (M1: bisheriges WLAN wird getrennt).")
            }
            if (summary.staAp == Flag.UNAVAILABLE) add("STA+AP-Betrieb: Abfrage auf diesem Android nicht verfügbar.")
            if (summary.staLocalOnly == Flag.NO) add("Gleichzeitige lokale Verbindung wird nicht unterstützt (M2).")
            if (summary.vpnActive) add("VPN aktiv: Ergebnisse können verfälscht sein (M5).")
            if (summary.locationServices ==
                Flag.NO
            ) {
                add("Standortdienst ist aus (LocalOnlyHotspot bis API 32 braucht ihn).")
            }
        }
}
