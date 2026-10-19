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

/**
 * Index des Space in Room (ADR 0014, Persistenz). Die Datei heißt `spaces.db` und liegt unter `noBackupFilesDir`, nie
 * unter `databases/`, damit sie aus Backups und Geräte-Übertragungen herausfällt.
 */
@Database(entities = [MediaEntity::class, ParticipantEntity::class], version = 2, exportSchema = false)
abstract class SpacesDatabase : RoomDatabase() {
    abstract fun media(): MediaDao

    abstract fun participants(): ParticipantDao

    companion object {
        const val FILE_NAME = "spaces.db"

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
                .addMigrations(MIGRATION_1_2)
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
