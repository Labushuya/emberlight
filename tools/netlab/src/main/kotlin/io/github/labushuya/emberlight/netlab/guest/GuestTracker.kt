package io.github.labushuya.emberlight.netlab.guest

/**
 * Zählt die Gäste des Testservers über ihre Client-Adressen (Paket 4.0, Funktion B6, Messpunkt M4).
 *
 * Ein Gast ist eine verschiedene Client-IP. Mehrere Geräte hinter einer Adresse zählen als eins. Die Uhr ist
 * injizierbar, damit die Auswertung ohne Wartezeit testbar bleibt.
 */
class GuestTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ignored: Set<String> = emptySet(),
) {
    private val lastSeen = LinkedHashMap<String, Long>()

    /** Vermerkt einen Kontakt. Adressen aus [ignored] (z. B. die eigene) zählen nicht. */
    @Synchronized
    fun touch(clientAddress: String) {
        if (clientAddress in ignored) return
        lastSeen[clientAddress] = clock()
    }

    @Synchronized
    fun snapshot(activeWindowMs: Long): GuestSnapshot {
        val now = clock()
        val entries =
            lastSeen.entries
                .map { GuestEntry(address = it.key, lastSeenMs = it.value, active = now - it.value <= activeWindowMs) }
                .sortedByDescending { it.lastSeenMs }
        return GuestSnapshot(total = entries.size, active = entries.count { it.active }, entries = entries)
    }

    @Synchronized
    fun reset() {
        lastSeen.clear()
    }
}

data class GuestEntry(
    val address: String,
    val lastSeenMs: Long,
    val active: Boolean,
)

/** [total]: alle Gäste seit Start. [active]: Gäste mit Kontakt innerhalb des Fensters. */
data class GuestSnapshot(
    val total: Int,
    val active: Int,
    val entries: List<GuestEntry>,
)
