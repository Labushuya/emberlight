package io.github.labushuya.emberlight.share.space.retention

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import kotlinx.coroutines.CancellationException

/** Ergebnis einer Löschung. */
sealed interface PurgeResult {
    /** Der Space ist gelöscht (Status `DELETED`), oder es gab ihn schon nicht mehr. */
    data object Purged : PurgeResult

    /** Nur beendete Spaces werden gelöscht. Ein aktiver oder entworfener Space bleibt unberührt. */
    data object NotEnded : PurgeResult
}

/** Ergebnis des Entfernens aus der Liste (Paket 3.6c). */
sealed interface RemoveResult {
    /** Der Eintrag ist ganz entfernt, oder es gab ihn schon nicht mehr. */
    data object Removed : RemoveResult

    /** Nur ein gelöschter Space lässt sich aus der Liste entfernen. Ein laufender oder beendeter Space bleibt. */
    data object NotDeleted : RemoveResult
}

/**
 * Die eine Lösch-Funktion für beendete Spaces (Paket 3.6b, ADR 0021; Paket 3.6c: Status `DELETED`). „Jetzt löschen“, die
 * automatische Löschung nach Fristende und das Fortsetzen nach einem Abbruch rufen sie gleichermaßen auf.
 *
 * Reihenfolge, damit ein Abbruch nichts verliert: Zuerst wird das Merkmal `purging` gesetzt. Dann Vault freigeben,
 * Medien und Vorschauen entfernen (das Titelbild bleibt), dann in einer Transaktion Medien, Teilnehmer, Pausen und der
 * Übergang nach `DELETED` (Frist, Einladung und Port fallen weg). Bricht der Prozess dazwischen ab, bleibt der Space mit
 * `purging` stehen. [resumeAll] setzt ihn beim nächsten Start fort.
 *
 * [remove] entfernt den Eintrag aus der Liste samt Titelbild. Erst die Zeile, dann das Verzeichnis. Bricht es dazwischen
 * ab, räumt die Bereinigung beim nächsten Start das Verzeichnis weg (ADR 0016).
 *
 * Der Schlüssel der Einladung liegt im Android-Keystore und ist für alle Spaces gemeinsam. Mit dem Übergang verschwindet
 * das verschlüsselte Geheimnis aus der Zeile (ADR 0018).
 */
class SpacePurge(
    private val database: SpacesDatabase,
    private val covers: SpaceCoverStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val forgetVault: suspend (SpaceId) -> Unit,
) {
    private val spaces = database.spaces()

    suspend fun purge(id: SpaceId): PurgeResult {
        val row = spaces.find(id.value) ?: return PurgeResult.Purged
        if (row.status == SpaceStatus.DELETED.name && !row.purging) return PurgeResult.Purged
        if (!row.purging && row.status != SpaceStatus.ENDED.name) return PurgeResult.NotEnded
        if (!row.purging) spaces.markPurging(id.value)
        // Eine offene Vorschau-Vault darf danach nichts mehr schreiben. Ihr Eintrag wird vergessen, bevor Dateien gehen.
        forgetVault(id)
        covers.deleteMediaOf(id)
        database.withTransaction {
            spaces.deleteMediaOf(id.value)
            database.participants().deleteOf(id.value)
            database.pauses().deleteOf(id.value)
            spaces.markDeleted(id.value, nowMillis())
        }
        // Nach dem Übergang nochmals räumen: Eine Teildatei aus einem Upload, der im Strom lief, bleibt sonst bis zum Start.
        clearLeftovers(id)
        return PurgeResult.Purged
    }

    /**
     * Räumt den Ordner eines gelöschten Space auf (siehe [SpaceCoverStore.clearLeftovers]). Ein Fehler lässt den Rest unberührt,
     * der nächste Start versucht es erneut.
     */
    private suspend fun clearLeftovers(id: SpaceId) {
        try {
            covers.clearLeftovers(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            RetentionLog.leftoversFailed(id, failed)
        }
    }

    /** Entfernt einen gelöschten Space ganz aus der Liste, samt Titelbild und Verzeichnis. */
    suspend fun remove(id: SpaceId): RemoveResult {
        val row = spaces.find(id.value) ?: return RemoveResult.Removed
        if (row.status != SpaceStatus.DELETED.name || row.purging) return RemoveResult.NotDeleted
        database.withTransaction {
            spaces.delete(id.value)
        }
        covers.deleteSpace(id)
        return RemoveResult.Removed
    }

    /**
     * Setzt alle unterbrochenen Löschungen fort. Gibt die Zahl der fortgesetzten Spaces zurück. Vorher räumt es die Ordner
     * aller gelöschten Spaces auf: Ein Absturz mitten in einem Upload nach der Löschung kann dort eine Teildatei
     * `tmp-*.part` hinterlassen (ADR 0021, Nachtrag 3.6d-2). Für gelöschte Spaces öffnet sich keine Vault, die sie aufräumen würde.
     */
    suspend fun resumeAll(): Int {
        spaces.allDeleted().forEach { row -> clearLeftovers(SpaceId(row.id)) }
        val pending = spaces.allPurging()
        // Ein Fehler bei einem Space hält die übrigen nicht auf. Er bleibt `purging` und kommt beim nächsten Lauf dran.
        pending.forEach { row ->
            val id = SpaceId(row.id)
            try {
                purge(id)
                RetentionLog.purgeSucceeded(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                RetentionLog.purgeFailed(id, failed)
            }
        }
        return pending.size
    }
}
