package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.content.ContextWrapper
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Teilnehmer im Space. Der Hash des Zugangsschlüssels liegt hier, nie der Schlüssel selbst (ADR 0013). */
@Entity(tableName = "participant")
data class ParticipantEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "token_hash") val tokenHash: String,
)

/** Medium im Index. `state` ist `READY` oder `DELETING` (Bereinigung nach einem Absturz, ADR 0013, Löschen). */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["space_id", "sha256"], unique = true),
        Index(value = ["space_id", "created_at", "id"]),
    ],
)
data class MediaEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "original_name") val originalName: String,
    @ColumnInfo(name = "uploader_id") val uploaderId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "state") val state: String,
    /** `true`, wenn der Standort nicht entfernt werden konnte und der Upload ausdrücklich bestätigt wurde (ADR 0015). */
    @ColumnInfo(name = "location_kept", defaultValue = "0") val locationKept: Boolean = false,
    /** Version der Verarbeitung. `0` heißt vor Paket 3.1b (unverarbeitet), ab 3.1b gilt `LocationScrubber.PROCESSING_VERSION`. */
    @ColumnInfo(name = "processing_version", defaultValue = "0") val processingVersion: Int = 0,
)

/** Zeile der Medienliste, mit dem Anzeigenamen des Uploaders. */
data class MediaRow(
    val id: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val uploaderName: String?,
    val locationKept: Boolean,
)

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(media: MediaEntity)

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun findById(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE space_id = :spaceId AND sha256 = :sha256 AND state = 'READY' LIMIT 1")
    suspend fun findReadyBySha256(
        spaceId: String,
        sha256: String,
    ): MediaEntity?

    @Query(
        """
        SELECT m.id AS id, m.mime AS mime, m.size_bytes AS sizeBytes, m.created_at AS createdAt,
               m.location_kept AS locationKept, p.display_name AS uploaderName
        FROM media m LEFT JOIN participant p ON p.id = m.uploader_id
        WHERE m.space_id = :spaceId AND m.state = 'READY'
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun firstPage(
        spaceId: String,
        limit: Int,
    ): List<MediaRow>

    @Query(
        """
        SELECT m.id AS id, m.mime AS mime, m.size_bytes AS sizeBytes, m.created_at AS createdAt,
               m.location_kept AS locationKept, p.display_name AS uploaderName
        FROM media m LEFT JOIN participant p ON p.id = m.uploader_id
        WHERE m.space_id = :spaceId AND m.state = 'READY'
          AND (m.created_at < :afterCreatedAt OR (m.created_at = :afterCreatedAt AND m.id < :afterId))
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pageAfter(
        spaceId: String,
        afterCreatedAt: Long,
        afterId: String,
        limit: Int,
    ): List<MediaRow>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media WHERE space_id = :spaceId AND state = 'READY'")
    suspend fun usedBytes(spaceId: String): Long

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media WHERE space_id = :spaceId AND state = 'READY'")
    fun observeUsedBytes(spaceId: String): Flow<Long>

    /** Erste Seite der Medienliste als Flow, für die Anzeige im laufenden Space. */
    @Query(
        """
        SELECT m.id AS id, m.mime AS mime, m.size_bytes AS sizeBytes, m.created_at AS createdAt,
               m.location_kept AS locationKept, p.display_name AS uploaderName
        FROM media m LEFT JOIN participant p ON p.id = m.uploader_id
        WHERE m.space_id = :spaceId AND m.state = 'READY'
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT :limit
        """,
    )
    fun observeFirstPage(
        spaceId: String,
        limit: Int,
    ): Flow<List<MediaRow>>

    @Query("SELECT * FROM media WHERE space_id = :spaceId")
    suspend fun allOf(spaceId: String): List<MediaEntity>

    @Query("UPDATE media SET state = 'DELETING' WHERE id = :id")
    suspend fun markDeleting(id: String): Int

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface ParticipantDao {
    @Upsert
    suspend fun upsert(participant: ParticipantEntity)

    @Query("SELECT * FROM participant WHERE id = :id")
    suspend fun findById(id: String): ParticipantEntity?
}

/** Eigenschaften eines Space (Paket 3.2, ADR 0016). Die Laufzeit ist entweder eine Dauer oder ein festes Ende. */
@Entity(tableName = "space")
data class SpaceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "cover_sha256") val coverSha256: String?,
    @ColumnInfo(name = "lifetime_preset") val lifetimePreset: String?,
    @ColumnInfo(name = "lifetime_until") val lifetimeUntil: Long?,
    @ColumnInfo(name = "retention_days") val retentionDays: Int,
    @ColumnInfo(name = "storage_limit_bytes") val storageLimitBytes: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Start in UTC-Millisekunden, gesetzt beim Übergang nach ACTIVE (Paket 3.3). */
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    /** Geplantes Ende in UTC-Millisekunden (Paket 3.3). */
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    /** Tatsächliches Ende in UTC-Millisekunden, gesetzt beim Übergang nach ENDED (Paket 3.3). */
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
)

/**
 * Pause des Dienstes durch das System (Paket 3.3, ADR 0017). Beginn ist die Wand-Uhr des letzten Herzschlags vor der
 * Lücke, die Dauer misst `elapsedRealtime`. [deepSleepMs] ist die Differenz zu `uptimeMillis` und dient nur als Zusatzinfo.
 */
@Entity(
    tableName = "pause_event",
    indices = [Index(value = ["space_id", "started_at"])],
)
data class PauseEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "deep_sleep_ms") val deepSleepMs: Long,
    @ColumnInfo(name = "acknowledged", defaultValue = "0") val acknowledged: Boolean = false,
)

@Dao
interface PauseDao {
    @Insert
    suspend fun insert(event: PauseEventEntity): Long

    @Query("SELECT * FROM pause_event WHERE space_id = :spaceId ORDER BY started_at DESC, id DESC")
    fun observeOf(spaceId: String): Flow<List<PauseEventEntity>>

    @Query("UPDATE pause_event SET acknowledged = 1 WHERE space_id = :spaceId AND acknowledged = 0")
    suspend fun acknowledgeAll(spaceId: String): Int

    @Query("DELETE FROM pause_event WHERE space_id = :spaceId")
    suspend fun deleteOf(spaceId: String): Int
}

@Dao
interface SpaceDao {
    @Upsert
    suspend fun upsert(space: SpaceEntity)

    @Query("SELECT * FROM space WHERE id = :id")
    suspend fun find(id: String): SpaceEntity?

    /** Alle bekannten Kennungen, für die Bereinigung verwaister Verzeichnisse. */
    @Query("SELECT id FROM space")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM space WHERE id = :id")
    fun observe(id: String): Flow<SpaceEntity?>

    /** Der aktive Space, höchstens einer (Paket 3.3: nur ein aktiver Space gleichzeitig). */
    @Query("SELECT * FROM space WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun findActive(): SpaceEntity?

    @Query("SELECT * FROM space WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActive(): Flow<SpaceEntity?>

    @Query("SELECT * FROM space WHERE status = 'ACTIVE'")
    suspend fun allActive(): List<SpaceEntity>

    @Query("SELECT * FROM space ORDER BY created_at DESC, id")
    fun observeAll(): Flow<List<SpaceEntity>>

    @Query("DELETE FROM space WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM media WHERE space_id = :spaceId")
    suspend fun deleteMediaOf(spaceId: String): Int
}

/**
 * Index des Space in Room (ADR 0014, Persistenz). Die Datei heißt `spaces.db` und liegt unter `noBackupFilesDir`, nie
 * unter `databases/`, damit sie aus Backups und Geräte-Übertragungen herausfällt.
 */
@Database(
    entities = [MediaEntity::class, ParticipantEntity::class, SpaceEntity::class, PauseEventEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class SpacesDatabase : RoomDatabase() {
    abstract fun media(): MediaDao

    abstract fun participants(): ParticipantDao

    abstract fun spaces(): SpaceDao

    abstract fun pauses(): PauseDao

    companion object {
        const val FILE_NAME = "spaces.db"
        const val SPACES_DIRECTORY = "spaces"

        /** Ordner der Space-Daten unter `noBackupFilesDir`. Der einzige Ort, den App und Server gemeinsam nutzen. */
        fun spacesDirectory(context: Context): File = File(context.noBackupFilesDir, SPACES_DIRECTORY)

        @Volatile
        private var shared: SpacesDatabase? = null

        /**
         * Die eine Instanz für den ganzen Prozess (Paket 3.3). App und Server greifen über sie auf `spaces.db` zu. Zwei
         * Instanzen auf derselben Datei gelten nicht als sauber (ADR 0016). Die Hilt-Bindung in `feature:space` liefert
         * dieselbe Instanz. Sie wird nie geschlossen, sie lebt mit dem Prozess.
         */
        fun shared(context: Context): SpacesDatabase =
            shared ?: synchronized(this) {
                shared
                    ?: open(
                        context.applicationContext,
                        spacesDirectory(context.applicationContext),
                    ).also { shared = it }
            }

        /**
         * Version 3 → 4 (Paket 3.3): Lebenszyklus-Spalten an `space` (Start, geplantes und tatsächliches Ende) und die
         * Tabelle `pause_event`. Additiv, bestehende Daten bleiben unberührt.
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `space` ADD COLUMN `started_at` INTEGER")
                    db.execSQL("ALTER TABLE `space` ADD COLUMN `expires_at` INTEGER")
                    db.execSQL("ALTER TABLE `space` ADD COLUMN `ended_at` INTEGER")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `pause_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`space_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
                            "`duration_ms` INTEGER NOT NULL, `deep_sleep_ms` INTEGER NOT NULL, " +
                            "`acknowledged` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_pause_event_space_id_started_at` " +
                            "ON `pause_event` (`space_id`, `started_at`)",
                    )
                }
            }

        /**
         * Version 2 → 3 (Paket 3.2): Tabelle `space` mit den Eigenschaften des Space. Additiv, bestehende Medien bleiben
         * unberührt. Die Definition entspricht dem Schema, das Room aus [SpaceEntity] ableitet.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `space` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                            "`color_index` INTEGER NOT NULL, `cover_sha256` TEXT, `lifetime_preset` TEXT, " +
                            "`lifetime_until` INTEGER, `retention_days` INTEGER NOT NULL, " +
                            "`storage_limit_bytes` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }

        /**
         * Version 1 → 2 (Paket 3.1b): Markierung `location_kept` und Verarbeitungsversion. Bestehende Medien bekommen
         * `0`, also ohne Einwilligung und ohne Verarbeitung nach 3.1b. Die Migration ist additiv und geht ohne Datenverlust.
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE media ADD COLUMN location_kept INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE media ADD COLUMN processing_version INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * Öffnet den Index unter [spacesDir]. Der absolute Pfad überschreibt den Standardort `databases/`. Der Pfad
         * kommt vom Aufrufer, [Context] liefert nur den Room-Builder und keine Pfade.
         */
        fun open(
            context: Context,
            spacesDir: File,
        ): SpacesDatabase {
            spacesDir.mkdirs()
            return Room
                .databaseBuilder(IndexLocationContext(context, spacesDir), SpacesDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}

/**
 * Legt den Index in [directory] ab. Room fragt den Dateipfad über `getDatabasePath` ab. Der absolute Dateiname im
 * Builder ist dafür nicht verlässlich: Unter Robolectric auf Windows wurde der Pfad an das Cache-Verzeichnis angehängt
 * (ADR 0014, offener Punkt Room-Pfad, Ergebnis 18.10.2026). Diese Hülle umgeht das deterministisch.
 */
private class IndexLocationContext(
    base: Context,
    private val directory: File,
) : ContextWrapper(base) {
    override fun getDatabasePath(name: String): File = File(directory, name)
}
