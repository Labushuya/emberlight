package io.github.labushuya.emberlight.share.space.retention

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.management.toSpaceRecord

/** Ergebnis einer Änderung der Frist. */
sealed interface RetentionChange {
    data class Changed(
        val untilMillis: Long,
    ) : RetentionChange

    /** Die Dauer liegt nicht im erlaubten Bereich (0 Tage oder 1 bis 90 Tage). */
    data object InvalidDays : RetentionChange

    /** Die Frist lässt sich nur am beendeten Space ändern, der nicht gerade gelöscht wird. */
    data object NotChangeable : RetentionChange

    data object NotFound : RetentionChange
}

/**
 * Aufbewahrung nach dem Ende und ihre Erinnerung in der Datenbank (Paket 3.6b, ADR 0021). Die Regeln stehen in
 * [RetentionRules], die Planung in [RetentionReminderPlanner]. Hier liegen nur die Schreibzugriffe und das Lesen für die
 * Planung. Alle Zeiten in UTC-Millisekunden, die Uhr kommt von außen.
 */
class RetentionRepository(
    private val database: SpacesDatabase,
    private val purge: SpacePurge,
) {
    private val spaces = database.spaces()

    /** Die Erinnerungen, die jetzt fällig sind, und der nächste Prüfzeitpunkt. Beendete und aktive Spaces zählen. */
    suspend fun plan(now: Long): RetentionPlan {
        val records = spaces.allEnded().map { it.toSpaceRecord() } + spaces.allActive().map { it.toSpaceRecord() }
        return RetentionReminderPlanner.plan(records, now)
    }

    /**
     * Legt eine neue Frist fest, gezählt ab [now] (siehe [RetentionRules.changedUntil]). Nur am beendeten Space, der nicht
     * gelöscht wird. Die Frist wird geprüft, bevor sie gespeichert wird.
     */
    suspend fun changeUntil(
        id: SpaceId,
        days: Int,
        now: Long,
    ): RetentionChange {
        if (!RetentionRules.isValidDays(days)) return RetentionChange.InvalidDays
        return database.withTransaction {
            val row = spaces.find(id.value) ?: return@withTransaction RetentionChange.NotFound
            if (row.status != SpaceStatus.ENDED.name || row.purging) {
                return@withTransaction RetentionChange.NotChangeable
            }
            val until = RetentionRules.changedUntil(now, days)
            spaces.setRetentionUntil(id.value, until, now)
            RetentionChange.Changed(until)
        }
    }

    /** Aktion „1 Tag länger aufbewahren“ aus der Benachrichtigung. Löscht nichts. */
    suspend fun extendByOneDay(
        id: SpaceId,
        now: Long,
    ): RetentionChange =
        database.withTransaction {
            val row = spaces.find(id.value) ?: return@withTransaction RetentionChange.NotFound
            if (row.status != SpaceStatus.ENDED.name || row.purging) {
                return@withTransaction RetentionChange.NotChangeable
            }
            val current = RetentionRules.effectiveUntil(row.endedAt ?: now, row.retentionDays, row.retentionUntil)
            val until = RetentionRules.extendedByOneDay(current, now)
            spaces.setRetentionUntil(id.value, until, now)
            RetentionChange.Changed(until)
        }

    /** Merkt die gesendete Erinnerung. Ein späteres Ändern der Frist plant sie neu. */
    suspend fun markReminded(
        id: SpaceId,
        plannedAt: Long,
    ) {
        spaces.setRemindedFor(id.value, plannedAt)
    }

    /** Löscht einen beendeten Space (siehe [SpacePurge]). */
    suspend fun purge(id: SpaceId): PurgeResult = purge.purge(id)

    /** Setzt unterbrochene Löschungen beim Start fort. */
    suspend fun resumePurges(): Int = purge.resumeAll()

    /** Lesen eines einzelnen Space für die Oberfläche. */
    suspend fun find(id: SpaceId): SpaceRecord? = spaces.find(id.value)?.toSpaceRecord()
}
