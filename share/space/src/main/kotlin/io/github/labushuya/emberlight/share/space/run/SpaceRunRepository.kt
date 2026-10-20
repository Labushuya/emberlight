package io.github.labushuya.emberlight.share.space.run

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.MediaRow
import io.github.labushuya.emberlight.share.space.PauseEventEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.management.toSpaceRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Ergebnis des Starts. Bei Erfolg steht der aktive Space bereit, sonst der Grund. */
sealed interface StartResult {
    data class Started(
        val record: SpaceRecord,
    ) : StartResult

    data class Refused(
        val problem: StartProblem,
    ) : StartResult
}

/**
 * Lebenszyklus eines Space im Betrieb (Paket 3.3): Start, Ende, Pausen. Entwürfe bleiben Sache von
 * [io.github.labushuya.emberlight.share.space.management.SpaceRepository]. Alle Übergänge laufen in einer Transaktion,
 * damit es nie zwei aktive Spaces gibt.
 *
 * Der Dienst ruft diese Klasse nur über die prozessweit geteilte Datenbank auf ([SpacesDatabase.shared]).
 */
class SpaceRunRepository(
    private val database: SpacesDatabase,
    private val clock: RunClock,
) {
    private val spaces = database.spaces()
    private val pauses = database.pauses()

    /** Der aktive Space oder `null`. Die Oberfläche zeigt damit den laufenden Space. */
    fun observeActive(): Flow<SpaceRecord?> = spaces.observeActive().map { row -> row?.toSpaceRecord() }

    suspend fun findActive(): SpaceRecord? = spaces.findActive()?.toSpaceRecord()

    /** Pausen eines Space, neueste zuerst. */
    fun observePauses(id: SpaceId): Flow<List<PauseEvent>> =
        pauses.observeOf(id.value).map { rows ->
            rows.map { row ->
                PauseEvent(
                    startedAtMillis = row.startedAt,
                    durationMillis = row.durationMs,
                    deepSleepMillis = row.deepSleepMs,
                    acknowledged = row.acknowledged,
                )
            }
        }

    /** Belegter Speicher des Space in Bytes, live. */
    fun observeUsedBytes(id: SpaceId): Flow<Long> = database.media().observeUsedBytes(id.value)

    /** Erste Seite der Medienliste des Space, live. */
    fun observeMedia(
        id: SpaceId,
        limit: Int,
    ): Flow<List<MediaRow>> = database.media().observeFirstPage(id.value, limit)

    /**
     * Startet einen Entwurf. [freeBytes] ist der freie Speicher des Geräts. Der Start setzt Status `ACTIVE`, den Beginn
     * und das geplante Ende. Die Prüfung von WLAN und Einrichtung liegt beim Aufrufer.
     */
    suspend fun start(
        id: SpaceId,
        freeBytes: Long,
    ): StartResult =
        database.withTransaction {
            val row = spaces.find(id.value)
            val record = row?.toSpaceRecord()
            val now = clock.wallMillis()
            val problem =
                SpaceRunPolicy.startProblem(
                    status = record?.status,
                    storageLimitBytes = record?.settings?.storageLimitBytes ?: 0L,
                    freeBytes = freeBytes,
                    lifetime = record?.settings?.lifetime,
                    nowWallMillis = now,
                    anotherActive = spaces.findActive() != null,
                )
            if (row == null || record == null || problem != null) {
                StartResult.Refused(problem ?: StartProblem.NOT_FOUND)
            } else {
                val started =
                    row.copy(
                        status = SpaceStatus.ACTIVE.name,
                        startedAt = now,
                        expiresAt = SpaceRunPolicy.expiresAt(record.settings.lifetime, now),
                        updatedAt = now,
                    )
                spaces.upsert(started)
                StartResult.Started(started.toSpaceRecord())
            }
        }

    /** Beendet den aktiven Space. Liefert `false`, wenn der Space nicht aktiv ist. */
    suspend fun end(
        id: SpaceId,
        endedAtMillis: Long,
    ): Boolean =
        database.withTransaction {
            val row = spaces.find(id.value)
            if (row == null || row.status != SpaceStatus.ACTIVE.name) {
                false
            } else {
                spaces.upsert(
                    row.copy(
                        status = SpaceStatus.ENDED.name,
                        endedAt = endedAtMillis,
                        updatedAt = clock.wallMillis(),
                    ),
                )
                true
            }
        }

    /** Macht einen Start rückgängig, der nicht zustande kam (etwa kein WLAN beim Dienst). Der Space ist wieder Entwurf. */
    suspend fun abortStart(id: SpaceId): Boolean =
        database.withTransaction {
            val row = spaces.find(id.value)
            if (row == null || row.status != SpaceStatus.ACTIVE.name) {
                false
            } else {
                spaces.upsert(
                    row.copy(
                        status = SpaceStatus.DRAFT.name,
                        startedAt = null,
                        expiresAt = null,
                        updatedAt = clock.wallMillis(),
                    ),
                )
                true
            }
        }

    /** Hält eine Pause fest. Der Dienst ruft das bei jeder erkannten Lücke auf. */
    suspend fun recordPause(
        id: SpaceId,
        event: PauseEvent,
    ) {
        pauses.insert(
            PauseEventEntity(
                spaceId = id.value,
                startedAt = event.startedAtMillis,
                durationMs = event.durationMillis,
                deepSleepMs = event.deepSleepMillis,
            ),
        )
    }

    /** Markiert alle Pausen des Space als gesehen, wenn der Hinweis bestätigt wurde. */
    suspend fun acknowledgePauses(id: SpaceId): Int = pauses.acknowledgeAll(id.value)
}
