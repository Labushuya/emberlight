package io.github.labushuya.emberlight.share.export

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.core.net.toUri
import io.github.labushuya.emberlight.share.space.export.ExportFileMeta
import io.github.labushuya.emberlight.share.space.export.ExportHandle
import io.github.labushuya.emberlight.share.space.export.ExportNames
import io.github.labushuya.emberlight.share.space.export.ExportSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * Schreibt die Kopien in `Pictures/Emberlight/<Space>` (Paket 3.7, ADR 0022).
 *
 * - Ab Android 10 (API 29): Medienspeicher mit `RELATIVE_PATH` und `IS_PENDING`. Die Kopie ist ausstehend, bis die Prüfung
 *   bestanden hat. Bilder gehen in die Bilder-Tabelle, Videos in die Video-Tabelle. Beide Typen dürfen unter `Pictures/`
 *   liegen (Dokumentation „Shared storage: Media“, Verzeichnisse je Medientyp).
 * - Bis Android 9 (API 26 bis 28): direkte Datei unter `Pictures/`, dann Medienindex. Die Berechtigung dafür steht nur
 *   im Manifest dieses Moduls, mit `maxSdkVersion` 28.
 *
 * Verwirft die Kopie bei [discard] vollständig: Eintrag und Datei werden gelöscht. Das betrifft nur Kopien, die dieses
 * Modul angelegt hat, nie Dateien im Space.
 *
 * Die Q-Pfade (content-Adressen) entstehen nur ab Android 10, weil nur dort [create] eine solche Adresse liefert. Lint
 * erkennt diese Verzweigung nicht. Ströme gehen an den Aufrufer, der sie mit use schließt.
 */
@SuppressLint("InlinedApi", "NewApi", "Recycle")
class MediaStoreExportSink(
    context: Context,
) : ExportSink {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    override fun availableBytes(): Long {
        // Derselbe Datenträger wie Pictures/. Fehlt der externe Speicher, gibt es keinen Platz.
        val dir = app.getExternalFilesDir(null) ?: return 0L
        return StatFs(dir.path).availableBytes
    }

    override suspend fun folderExists(folderName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (legacy()) return@withContext legacyFolder(folderName).isDirectory
            relativePathRows(ExportNames.relativePath(folderName)).use { it.moveToFirst() }
        }

    override suspend fun fileExists(
        folderName: String,
        fileName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (legacy()) return@withContext File(legacyFolder(folderName), fileName).exists()
            displayNamesIn(ExportNames.relativePath(folderName)).any { it.equals(fileName, ignoreCase = true) }
        }

    override suspend fun create(meta: ExportFileMeta): ExportHandle =
        withContext(Dispatchers.IO) {
            if (legacy()) {
                val file = File(legacyFolder(meta.folderName), meta.fileName)
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) throw IOException("Die Datei existiert schon.")
                return@withContext ExportHandle(Uri.fromFile(file).toString(), meta.fileName)
            }
            val values =
                ContentValues().apply {
                    put(MediaColumns.DISPLAY_NAME, meta.fileName)
                    put(MediaColumns.MIME_TYPE, meta.mime)
                    put(MediaColumns.RELATIVE_PATH, ExportNames.relativePath(meta.folderName))
                    put(MediaColumns.IS_PENDING, 1)
                    meta.captureTimeMillis?.let { put(MediaColumns.DATE_TAKEN, it) }
                }
            val uri =
                resolver.insert(collectionFor(meta.mime), values)
                    ?: throw IOException("Der Eintrag konnte nicht angelegt werden.")
            ExportHandle(uri.toString(), displayNameOf(uri) ?: meta.fileName)
        }

    override suspend fun openOutput(handle: ExportHandle): OutputStream =
        withContext(Dispatchers.IO) {
            val uri = handle.uri.toUri()
            if (uri.scheme == "file") return@withContext FileOutputStream(File(requireNotNull(uri.path)))
            resolver.openOutputStream(uri, "w") ?: throw IOException("Die Kopie ist nicht beschreibbar.")
        }

    override suspend fun openInput(handle: ExportHandle): InputStream =
        withContext(Dispatchers.IO) {
            val uri = handle.uri.toUri()
            if (uri.scheme == "file") return@withContext File(requireNotNull(uri.path)).inputStream()
            resolver.openInputStream(uri) ?: throw IOException("Die Kopie ist nicht lesbar.")
        }

    override suspend fun publish(handle: ExportHandle) {
        val uri = handle.uri.toUri()
        if (uri.scheme == "file") {
            // Bis Android 9 zählt der Medienindex die Datei. Sichtbar wird sie nach dem Scan.
            scan(requireNotNull(uri.path))
            return
        }
        val values = ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }
        val rows = withContext(Dispatchers.IO) { resolver.update(uri, values, null, null) }
        if (rows != 1) throw IOException("Die Kopie konnte nicht freigegeben werden.")
    }

    override suspend fun discard(handle: ExportHandle) {
        val uri = handle.uri.toUri()
        withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).delete()
            } else {
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun legacy(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Medienspeicher-Sammlung nach Typ. Videos und Bilder haben je eine eigene Tabelle. */
    private fun collectionFor(mime: String): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
            else -> throw IllegalArgumentException("Medientyp nicht übernehmbar: $mime")
        }
    }

    private fun relativePathRows(relativePath: String): Cursor =
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaColumns._ID),
            "${MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        ) ?: emptyCursor()

    private fun displayNamesIn(relativePath: String): List<String> =
        resolver
            .query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaColumns.DISPLAY_NAME),
                "${MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
                generateSequence { if (cursor.moveToNext()) cursor.getString(index) else null }.toList()
            }.orEmpty()

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    @Suppress("DEPRECATION")
    private fun legacyFolder(folderName: String): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "${ExportNames.ROOT_FOLDER}/$folderName",
        )

    private suspend fun scan(path: String) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(app, arrayOf(path), null) { _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun emptyCursor(): Cursor = android.database.MatrixCursor(arrayOf(MediaColumns._ID))
}
