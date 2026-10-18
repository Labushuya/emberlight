package io.github.labushuya.emberlight.share.server

import io.ktor.http.HttpHeaders
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Ergebnis eines Uploads. */
sealed interface UploadResult {
    /** Datei gespeichert. [file] liegt im Zielordner und trägt eine zufällige ID als Namen. */
    data class Stored(
        val file: File,
        val size: Long,
    ) : UploadResult

    /** Das Formular enthält keinen Dateiteil. */
    data object NoFile : UploadResult

    /** Die Datei überschreitet das Limit. Eine Teildatei ist dann bereits gelöscht. */
    data object TooLarge : UploadResult
}

/** Platz für Formularfelder und Multipart-Rahmen neben der Datei selbst. */
private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024
private const val BUFFER_BYTES = 64 * 1024
private const val FILE_SUFFIX = ".bin"

/**
 * Nimmt die erste Datei eines Multipart-Formulars entgegen und schreibt sie streamend nach [targetDir] (ADR 0012).
 *
 * - Der Dateiname des Clients wird nie übernommen. Der Zielname ist eine zufällige UUID mit Endung `.bin`.
 * - [maxBytes] zählt der Code selbst beim Lesen. `formFieldLimit` allein begrenzt nur den Gesamtrahmen und setzt
 *   Ktor sonst bei 50 MiB eine Grenze.
 * - Ein `Content-Length` über dem Limit wird vor dem Lesen abgelehnt. Die Antwort (413) gibt der Aufrufer aus.
 * - Schlägt das Schreiben fehl oder überschreitet der Strom das Limit, bleibt keine Teildatei zurück.
 *
 * [targetDir] muss im app-internen Speicher liegen. Der Aufrufer legt ihn fest.
 */
suspend fun ApplicationCall.receiveSingleFile(
    targetDir: File,
    maxBytes: Long,
): UploadResult {
    require(maxBytes > 0) { "Das Upload-Limit muss positiv sein." }
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes + MULTIPART_OVERHEAD_BYTES) return UploadResult.TooLarge
    val multipart = receiveMultipart(formFieldLimit = maxBytes + MULTIPART_OVERHEAD_BYTES)
    return storeFirstFile(multipart, targetDir, maxBytes)
}

private suspend fun storeFirstFile(
    parts: MultiPartData,
    targetDir: File,
    maxBytes: Long,
): UploadResult {
    var outcome: UploadResult = UploadResult.NoFile
    parts.forEachPart { part ->
        if (outcome == UploadResult.NoFile && part is PartData.FileItem) {
            outcome = writeLimited(part, targetDir, maxBytes)
        }
        part.release()
    }
    return outcome
}

private suspend fun writeLimited(
    part: PartData.FileItem,
    targetDir: File,
    maxBytes: Long,
): UploadResult =
    withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val target = File(targetDir, UUID.randomUUID().toString() + FILE_SUFFIX)
        var written = 0L
        val channel = part.provider()
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            target.outputStream().use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) throw TooLargeUpload()
                    out.write(buffer, 0, read)
                }
            }
            UploadResult.Stored(target, written)
        } catch (tooLarge: TooLargeUpload) {
            target.delete()
            UploadResult.TooLarge
        } catch (failure: Throwable) {
            // Auch Abbruch (CancellationException) oder Ein-/Ausgabefehler: keine halbe Datei zurücklassen.
            target.delete()
            throw failure
        }
    }

private class TooLargeUpload : IOException("Upload überschreitet das Limit.")
