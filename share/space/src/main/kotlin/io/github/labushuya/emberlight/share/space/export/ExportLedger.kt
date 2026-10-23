package io.github.labushuya.emberlight.share.space.export

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Zielordner eines Space in der Galerie (Paket 3.7, ADR 0022). Wird beim ersten Übernehmen festgelegt und danach
 * wiederverwendet. Der Name ist für alle Spaces eindeutig.
 */
@Entity(tableName = "space_export")
data class SpaceExportEntity(
    @PrimaryKey @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "folder_name") val folderName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Vermerk je übernommenem Medium (ADR 0022). Verhindert doppelte Übernahmen. Die Zeile bleibt, auch wenn die Datei in
 * der Galerie inzwischen gelöscht wurde: Der Vermerk sagt, dass dieses Medium übernommen wurde, nicht, dass die Kopie
 * noch da ist.
 */
@Entity(tableName = "export_media")
data class ExportedMediaEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "target_name") val targetName: String,
    @ColumnInfo(name = "target_uri") val targetUri: String,
    @ColumnInfo(name = "exported_at") val exportedAt: Long,
)

@Dao
interface ExportDao {
    @Query("SELECT * FROM space_export WHERE space_id = :spaceId")
    suspend fun folderOf(spaceId: String): SpaceExportEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(row: SpaceExportEntity)

    @Query("SELECT folder_name FROM space_export")
    suspend fun allFolderNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExported(row: ExportedMediaEntity)

    @Query("SELECT media_id FROM export_media WHERE space_id = :spaceId")
    suspend fun exportedIds(spaceId: String): List<String>

    @Query("SELECT media_id FROM export_media WHERE space_id = :spaceId")
    fun observeExportedIds(spaceId: String): Flow<List<String>>

    @Query("SELECT * FROM export_media WHERE space_id = :spaceId ORDER BY exported_at, media_id")
    suspend fun exportedOf(spaceId: String): List<ExportedMediaEntity>

    /** Beim Löschen des Space: Vermerke und Ordner-Zuordnung gehen mit ihm. Die Kopien in der Galerie bleiben. */
    @Query("DELETE FROM export_media WHERE space_id = :spaceId")
    suspend fun deleteExportedOf(spaceId: String): Int

    @Query("DELETE FROM space_export WHERE space_id = :spaceId")
    suspend fun deleteFolderOf(spaceId: String): Int
}
