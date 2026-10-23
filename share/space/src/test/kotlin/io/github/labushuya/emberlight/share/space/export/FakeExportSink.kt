package io.github.labushuya.emberlight.share.space.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Speicher im Arbeitsspeicher für die Übernahme. Hält Kopien, Veröffentlichungen und Verwerfungen fest und kann Fehler
 * einspielen: Fehler beim Anlegen einer Datei, beschädigte Kopien (Hash-Abweichung) und eine Hook vor dem Veröffentlichen.
 */
internal class FakeExportSink : ExportSink {
    var available: Long = Long.MAX_VALUE
    val folders = mutableSetOf<String>()
    val existing = mutableSetOf<String>()
    val failCreateFor = mutableSetOf<String>()
    val corruptFor = mutableSetOf<String>()
    var beforePublish: suspend (ExportHandle) -> Unit = {}

    val created = mutableListOf<ExportFileMeta>()
    val published = mutableListOf<ExportHandle>()
    val discarded = mutableListOf<ExportHandle>()
    val contents = mutableMapOf<String, ByteArray>()
    private val pending = mutableSetOf<String>()
    private var counter = 0

    override fun availableBytes(): Long = available

    override suspend fun folderExists(folderName: String): Boolean =
        folders.any {
            it.equals(folderName, ignoreCase = true)
        }

    override suspend fun fileExists(
        folderName: String,
        fileName: String,
    ): Boolean = existing.contains("$folderName/$fileName".lowercase())

    override suspend fun create(meta: ExportFileMeta): ExportHandle {
        if (meta.fileName in failCreateFor) throw IOException("Anlegen fehlgeschlagen (Test)")
        created += meta
        counter++
        val uri = "content://fake/$counter/${meta.folderName}/${meta.fileName}"
        pending += uri
        return ExportHandle(uri, meta.fileName)
    }

    override suspend fun openOutput(handle: ExportHandle): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val bytes = toByteArray()
                contents[handle.uri] =
                    if (handle.fileName in corruptFor && bytes.isNotEmpty()) flipFirst(bytes) else bytes
            }
        }

    override suspend fun openInput(handle: ExportHandle): InputStream =
        ByteArrayInputStream(contents[handle.uri] ?: throw IOException("Kopie fehlt (Test)"))

    override suspend fun publish(handle: ExportHandle) {
        beforePublish(handle)
        pending -= handle.uri
        published += handle
        existing += "${folderOf(handle)}/${handle.fileName}".lowercase()
    }

    override suspend fun discard(handle: ExportHandle) {
        pending -= handle.uri
        contents -= handle.uri
        discarded += handle
    }

    fun pendingCount(): Int = pending.size

    /** Ordner steht im Uri an Position 4: `content://fake/<zähler>/<ordner>/<datei>`. */
    private fun folderOf(handle: ExportHandle): String = handle.uri.split("/")[4]

    private fun flipFirst(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        copy[0] = (copy[0].toInt() xor 0x01).toByte()
        return copy
    }
}
