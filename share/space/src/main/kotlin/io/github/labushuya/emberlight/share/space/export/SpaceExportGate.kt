package io.github.labushuya.emberlight.share.space.export

import io.github.labushuya.emberlight.share.space.SpaceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Schließt Übernahme und Löschung eines Space gegeneinander aus (Paket 3.7, ADR 0022).
 *
 * Die Übernahme hält den Schlüssel ihres Space für den ganzen Lauf. Die Löschung (`SpacePurge`)
 * setzt zuerst das Merkmal `purging`, damit die Übernahme nach der nächsten Datei abbricht, und wartet dann auf den
 * Schlüssel. So fällt nie eine Datei mitten im Schreiben weg, und eine laufende Übernahme macht keine Kopie mehr, sobald
 * der Space gelöscht wird.
 *
 * Die Sperre gilt nur im Prozess. Die App läuft in einem Prozess (ADR 0016, Vault).
 */
class SpaceExportGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    companion object {
        /** Die eine Sperre des Prozesses. Übernahme und Löschung nutzen sie, ohne dass jemand sie weiterreichen muss. */
        val shared: SpaceExportGate = SpaceExportGate()
    }

    suspend fun <T> exclusive(
        id: SpaceId,
        block: suspend () -> T,
    ): T = locks.computeIfAbsent(id.value) { Mutex() }.withLock { block() }
}
