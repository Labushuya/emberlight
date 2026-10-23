package io.github.labushuya.emberlight.share.export

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Minimaler Medienspeicher für Robolectric. Speichert Zeilen im Arbeitsspeicher und Inhalte als Dateien unter [root].
 * Unterstützt Einfügen, Ändern, Löschen, Abfragen nach `RELATIVE_PATH` und Lese- und Schreibzugriff. Der Anbieter wird
 * unter der Autorität von MediaStore registriert.
 */
internal class FakeMediaStoreProvider(
    private val root: File,
) : ContentProvider() {
    val rows = linkedMapOf<Long, MutableMap<String, Any?>>()
    private var counter = 0L
    private var owner: String? = null

    fun register(context: Context) {
        owner = context.packageName
        // Ohne Anbieter-Information meldet Android „authority does not match“. Die Autorität kommt aus dem Manifest.
        attachInfo(
            context,
            ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                packageName = context.packageName
                exported = true
            },
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, this)
    }

    /** Die Zeile zur Kennung, für Prüfungen in den Tests. */
    fun row(id: Long): Map<String, Any?>? = rows[id]

    override fun onCreate(): Boolean = true

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        counter++
        val row = mutableMapOf<String, Any?>(MediaColumns._ID to counter)
        values?.keySet()?.forEach { key -> row[key] = values.get(key) }
        // Wie der echte Medienspeicher: Der Besitzer ist die einfügende App, sofern der Aufrufer keinen anderen nennt.
        row.putIfAbsent(MediaColumns.OWNER_PACKAGE_NAME, owner)
        rows[counter] = row
        root.mkdirs()
        File(root, counter.toString()).createNewFile()
        return ContentUris.withAppendedId(uri, counter)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        values?.keySet()?.forEach { key -> row[key] = values.get(key) }
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val id = ContentUris.parseId(uri)
        if (rows.remove(id) == null) return 0
        File(root, id.toString()).delete()
        return 1
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.RELATIVE_PATH)
        val matrix = MatrixCursor(columns)
        val selected =
            if (uri.lastPathSegment?.matches(Regex("\\d+")) == true) {
                listOfNotNull(rows[ContentUris.parseId(uri)])
            } else {
                rows.values.filter { row ->
                    selection == null || row[MediaColumns.RELATIVE_PATH] == selectionArgs?.firstOrNull()
                }
            }
        selected.forEach { row -> matrix.addRow(columns.map { row[it] }) }
        return matrix
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val file = File(root, ContentUris.parseId(uri).toString())
        val flags =
            if (mode.startsWith("w")) {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
        return ParcelFileDescriptor.open(file, flags)
    }

    /** Inhalt einer Zeile, wie er gespeichert wurde. */
    fun contentOf(id: Long): ByteArray = File(root, id.toString()).readBytes()
}
