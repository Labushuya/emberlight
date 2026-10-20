package io.github.labushuya.emberlight.share.space.management

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Ergebnis eines Speichervorgangs. Die Oberfläche zeigt die Fehler am Feld an. */
sealed interface SaveResult {
    data class Saved(
        val record: SpaceRecord,
    ) : SaveResult

    data class Invalid(
        val errors: Set<SettingsError>,
    ) : SaveResult

    /** Der Space ist kein Entwurf mehr (Bearbeiten nur im Entwurf, ab Paket 3.3 gilt das für aktive Spaces erst recht), oder die ID ist schon vergeben. */
    data object NotEditable : SaveResult

    data object NotFound : SaveResult
}

/**
 * Anlegen, Lesen, Bearbeiten und Löschen von Spaces (Paket 3.2). Die Eigenschaften liegen in Room (`space`), das Titelbild
 * im Space-Speicher. Der Speicher gehört nur zu einem Space: Löschen entfernt Datenbankeinträge und Verzeichnis gemeinsam.
 *
 * Bearbeiten ist nur im Entwurf erlaubt. Ein Space mit Status [SpaceStatus.ACTIVE] nimmt keine Änderung mehr an (Paket 3.3).
 *
 * Beim ersten Zugriff je Prozess räumt das Repository verwaiste Verzeichnisse im Space-Ordner auf ([spacesDir], siehe
 * [OrphanSpaceDirectories]). Der Lauf liegt vor jeder Schreibung, daher kann er keinen Entwurf dieser Sitzung treffen.
 *
 * [freeSpace] liefert den freien Speicher des Geräts. Daraus folgt die harte Obergrenze des Speicherlimits (Paket 3.2).
 */
class SpaceRepository(
    private val database: SpacesDatabase,
    private val covers: SpaceCoverStore,
    private val spacesDir: File,
    private val freeSpace: StorageProbe,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.spaces()
    private val startupLock = Mutex()

    @Volatile
    private var swept = false

    /** Obergrenze des Speicherlimits auf diesem Gerät, jetzt gemessen. Ein Space braucht mindestens 0,5 GiB davon. */
    fun storageCeilingBytes(): Long = SpaceRules.maxStorageBytes(freeSpace.usableBytes())

    fun observeAll(): Flow<List<SpaceRecord>> =
        flow {
            ensureSwept()
            emitAll(dao.observeAll().map { rows -> rows.map { it.toRecord() } })
        }

    fun observe(id: SpaceId): Flow<SpaceRecord?> =
        flow {
            ensureSwept()
            emitAll(dao.observe(id.value).map { row -> row?.toRecord() })
        }

    suspend fun find(id: SpaceId): SpaceRecord? {
        ensureSwept()
        return dao.find(id.value)?.toRecord()
    }

    /** Legt einen neuen Entwurf unter [id] an. Die ID kommt von der Oberfläche, damit das Titelbild schon vorher ablegbar ist. */
    suspend fun create(
        id: SpaceId,
        settings: SpaceSettings,
        coverSha256: String?,
    ): SaveResult {
        ensureSwept()
        if (dao.find(id.value) != null) return SaveResult.NotEditable
        return write(id, settings, coverSha256, createdAt = null)
    }

    /** Ändert einen Entwurf. Ein Space, der kein Entwurf ist, bleibt unverändert. */
    suspend fun update(
        id: SpaceId,
        settings: SpaceSettings,
        coverSha256: String?,
    ): SaveResult {
        ensureSwept()
        val existing = dao.find(id.value)?.toRecord() ?: return SaveResult.NotFound
        if (existing.status != SpaceStatus.DRAFT) return SaveResult.NotEditable
        return write(id, settings, coverSha256, createdAt = existing.createdAt)
    }

    /**
     * Löscht den Space: Medien- und Space-Einträge in einer Transaktion, danach der gesamte Speicher (Titelbild, Medien,
     * Verzeichnis). Liefert `false`, wenn es den Space nicht gibt.
     */
    suspend fun delete(id: SpaceId): Boolean {
        ensureSwept()
        if (dao.find(id.value) == null) return false
        database.withTransaction {
            dao.deleteMediaOf(id.value)
            database.pauses().deleteOf(id.value)
            dao.delete(id.value)
        }
        covers.deleteSpace(id)
        return true
    }

    /**
     * Verwirft einen Entwurf, der nie gespeichert wurde (Abbruch im Formular). Der Speicher unter [id] wird entfernt. Gibt
     * es einen gespeicherten Space mit dieser ID, bleibt alles unberührt.
     */
    suspend fun discardUnsaved(id: SpaceId) {
        ensureSwept()
        if (dao.find(id.value) == null) covers.deleteSpace(id)
    }

    /** Titelbild übernehmen (siehe [SpaceCoverStore.store]). Das Ergebnis gilt nur für diesen Space. */
    suspend fun storeCover(
        id: SpaceId,
        input: InputStream,
    ): CoverResult {
        ensureSwept()
        return covers.store(id, input)
    }

    /** Pfad eines Titelbilds für die Anzeige. */
    fun coverFile(
        id: SpaceId,
        sha256: String,
    ): File = covers.file(id, sha256)

    /** Entfernt Titelbilder, die nicht mehr gebraucht werden (zum Beispiel ein ersetzter Entwurf). */
    suspend fun retainCovers(
        id: SpaceId,
        keep: Set<String>,
    ) {
        covers.retainOnly(id, keep)
    }

    private suspend fun write(
        id: SpaceId,
        settings: SpaceSettings,
        coverSha256: String?,
        createdAt: Long?,
    ): SaveResult {
        val normalized = settings.copy(name = SpaceRules.normalizeName(settings.name))
        val now = nowMillis()
        // Erneute Prüfung beim Speichern: Der Speicher des Geräts kann sich seit dem Öffnen des Formulars geändert haben.
        val ceiling = withContext(Dispatchers.IO) { storageCeilingBytes() }
        val errors = SpaceRules.validate(normalized, now, ceiling).toMutableSet()
        if (coverSha256 != null && !withContext(Dispatchers.IO) { covers.exists(id, coverSha256) }) {
            errors += SettingsError.COVER_MISSING
        }
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)

        val row =
            SpaceEntity(
                id = id.value,
                name = normalized.name,
                colorIndex = normalized.colorIndex,
                coverSha256 = coverSha256,
                lifetimePreset = (normalized.lifetime as? Lifetime.Preset)?.preset?.name,
                lifetimeUntil = (normalized.lifetime as? Lifetime.Until)?.epochMillis,
                retentionDays = normalized.retentionDays,
                storageLimitBytes = normalized.storageLimitBytes,
                status = SpaceStatus.DRAFT.name,
                createdAt = createdAt ?: now,
                updatedAt = now,
            )
        // Einladung und Port gehören nicht zum Formular. Ein Speichern darf sie nicht löschen (Paket 3.4).
        val previous = dao.find(id.value)
        dao.upsert(
            row.copy(
                inviteHash = previous?.inviteHash,
                inviteCreatedAt = previous?.inviteCreatedAt,
                inviteRotatedAt = previous?.inviteRotatedAt,
                port = previous?.port,
            ),
        )
        // Nach dem Speichern bleibt nur das Titelbild, das der Space nun verweist. Ein ersetztes Bild verschwindet.
        covers.retainOnly(id, setOfNotNull(coverSha256))
        return SaveResult.Saved(row.toRecord())
    }

    /** Einmal je Prozess vor dem ersten Zugriff: verwaiste Verzeichnisse entfernen (siehe [OrphanSpaceDirectories]). */
    private suspend fun ensureSwept() {
        if (swept) return
        startupLock.withLock {
            if (swept) return
            withContext(Dispatchers.IO) {
                OrphanSpaceDirectories.sweep(spacesDir, dao.allIds().toSet())
            }
            swept = true
        }
    }

    private fun SpaceEntity.toRecord(): SpaceRecord = toSpaceRecord()
}

/** Zeile der Tabelle `space` als [SpaceRecord]. Gemeinsam für Entwurf (Paket 3.2) und Betrieb (Paket 3.3). */
internal fun SpaceEntity.toSpaceRecord(): SpaceRecord =
    SpaceRecord(
        id = SpaceId(id),
        settings =
            SpaceSettings(
                name = name,
                colorIndex = colorIndex,
                lifetime = lifetimeFrom(lifetimePreset, lifetimeUntil),
                retentionDays = retentionDays,
                storageLimitBytes = storageLimitBytes,
            ),
        coverSha256 = coverSha256,
        status = SpaceStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        expiresAt = expiresAt,
        endedAt = endedAt,
    )

private fun lifetimeFrom(
    preset: String?,
    until: Long?,
): Lifetime =
    if (preset != null) {
        Lifetime.Preset(LifetimePreset.valueOf(preset))
    } else {
        Lifetime.Until(checkNotNull(until) { "Laufzeit ohne Dauer und ohne Ende." })
    }
