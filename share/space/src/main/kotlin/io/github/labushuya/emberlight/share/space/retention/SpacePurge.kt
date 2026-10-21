package io.github.labushuya.emberlight.share.space.retention

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceStatus

/** Ergebnis einer Löschung. */
sealed interface PurgeResult {
    /** Der Space ist vollständig entfernt, oder es gab ihn schon nicht mehr. */
    data object Purged : PurgeResult

    /** Nur beendete Spaces werden gelöscht. Ein aktiver oder entworfener Space bleibt unberührt. */
    data object NotEnded : PurgeResult
}

/**
 * Die eine Lösch-Funktion für beendete Spaces (Paket 3.6b, ADR 0021). „Jetzt löschen“ und das spätere automatische Löschen
 * nach Fristende (Paket 3.6c) rufen sie gleichermaßen auf.
 *
 * Reihenfolge, damit ein Abbruch nichts verliert: Zuerst wird das Merkmal `purging` gesetzt. Dann Speicher und Vorschauen
 * (Dateien, idempotent), dann in einer Transaktion Medien, Teilnehmer, Pausen und der Space-Eintrag. Bricht der Prozess
 * dazwischen ab, bleibt der Space mit `purging` stehen. [resumeAll] setzt ihn beim nächsten Start fort. Ein Space ohne
 * Eintrag ist fertig.
 *
 * Der Schlüssel der Einladung liegt im Android-Keystore und ist für alle Spaces gemeinsam. Mit dem Eintrag verschwindet nur
 * das verschlüsselte Geheimnis (ADR 0018).
 */
class SpacePurge(
    private val database: SpacesDatabase,
    private val covers: SpaceCoverStore,
    private val forgetVault: suspend (SpaceId) -> Unit,
) {
    private val spaces = database.spaces()

    suspend fun purge(id: SpaceId): PurgeResult {
        val row = spaces.find(id.value) ?: return PurgeResult.Purged
        if (!row.purging && row.status != SpaceStatus.ENDED.name) return PurgeResult.NotEnded
        if (!row.purging) spaces.markPurging(id.value)
        // Eine offene Vorschau-Vault darf danach nichts mehr schreiben. Ihr Eintrag wird vergessen, bevor Dateien gehen.
        forgetVault(id)
        covers.deleteSpace(id)
        database.withTransaction {
            spaces.deleteMediaOf(id.value)
            database.participants().deleteOf(id.value)
            database.pauses().deleteOf(id.value)
            spaces.delete(id.value)
        }
        return PurgeResult.Purged
    }

    /** Setzt alle unterbrochenen Löschungen fort. Gibt die Zahl der fortgesetzten Spaces zurück. */
    suspend fun resumeAll(): Int {
        val pending = spaces.allPurging()
        pending.forEach { purge(SpaceId(it.id)) }
        return pending.size
    }
}
