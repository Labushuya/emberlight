package io.github.labushuya.emberlight.netlab.net

/** Ein IPv4-Kandidat einer Netzschnittstelle. */
data class AddressCandidate(
    val interfaceName: String,
    val address: String,
)

/**
 * Wählt aus allen privaten IPv4-Adressen die des Hotspots (Paket 4.0, Funktion B2).
 *
 * Android verrät die Schnittstelle des LocalOnlyHotspot nicht direkt. Die Auswahl folgt deshalb Anhaltspunkten, die bei
 * den gängigen Geräten zutreffen: Schnittstellen mit `ap`, `swlan` oder `softap` im Namen, und die Adresse endet auf
 * `.1` (typische Gateway-Adresse des Hotspots). Mobilfunk-Schnittstellen werden ausgeschlossen. Das Ergebnis bleibt
 * eine Vermutung, deshalb zeigt der Bericht alle Kandidaten. Der Nutzer prüft sie.
 */
object HotspotAddressPicker {
    private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "v4-", "clat", "tun", "ipsec", "dummy", "lo")
    private val HOTSPOT_NAME_PREFIXES = listOf("ap", "swlan", "softap")

    /** Liefert die Kandidaten, sortiert nach Wahrscheinlichkeit. Der erste Eintrag ist die Wahl. */
    fun rank(candidates: List<AddressCandidate>): List<AddressCandidate> =
        candidates
            .filter { !isCellular(it.interfaceName) && isPrivateIpv4(it.address) }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<AddressCandidate>> { score(it.value) }.thenBy { it.index })
            .map { it.value }

    /** Die gewählte Adresse, `null` wenn kein passender Kandidat vorhanden ist. */
    fun pick(candidates: List<AddressCandidate>): AddressCandidate? = rank(candidates).firstOrNull()

    internal fun score(candidate: AddressCandidate): Int {
        val name = candidate.interfaceName.lowercase()
        var points = 0
        if (HOTSPOT_NAME_PREFIXES.any { name.startsWith(it) }) points += 4
        if (candidate.address.endsWith(".1")) points += 2
        if (name.startsWith("wlan")) points += 1
        return points
    }

    private fun isCellular(name: String): Boolean {
        val lower = name.lowercase()
        return CELLULAR_PREFIXES.any { lower.startsWith(it) }
    }

    /** Nur private IPv4-Bereiche (RFC 1918). Keine Loopback- oder öffentlichen Adressen. */
    internal fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }
}
