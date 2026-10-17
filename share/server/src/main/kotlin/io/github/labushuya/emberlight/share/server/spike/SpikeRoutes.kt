package io.github.labushuya.emberlight.share.server.spike

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Spike-Limit für einen Upload (ADR 0012): 2 GiB. */
const val SPIKE_MAX_UPLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024

private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024
private const val BUFFER_BYTES = 64 * 1024
private const val FILE_SUFFIX = ".bin"
private const val PROGRESS_STEP_BYTES = 64L * 1024 * 1024
private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * Spike-Endpunkte (ADR 0012, nicht für main): `POST /spike/upload` streamt eine Datei nach [storageDir], ohne den
 * Dateinamen des Clients zu übernehmen. `GET /spike/files/{id}` liefert sie mit Range-Unterstützung aus.
 *
 * [onProgress] erhält die bisher geschriebene Byte-Menge in Schritten von 64 MiB und nach Abschluss.
 */
fun spikeRoutes(
    storageDir: File,
    maxUploadBytes: Long = SPIKE_MAX_UPLOAD_BYTES,
    onProgress: (Long) -> Unit = {},
): Route.() -> Unit =
    {
        post("/spike/upload") {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > maxUploadBytes + MULTIPART_OVERHEAD_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            when (val outcome = storeFirstFile(call.receiveMultipart(), storageDir, maxUploadBytes, onProgress)) {
                is StoreOutcome.Stored -> {
                    call.respondText(
                        """{"id":"${outcome.id}","size":${outcome.size}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }

                StoreOutcome.NoFile -> {
                    call.respond(HttpStatusCode.BadRequest)
                }

                StoreOutcome.TooLarge -> {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                }
            }
        }

        get("/spike/files/{id}") {
            val id = call.parameters["id"]
            val file = id?.takeIf { ID_PATTERN.matches(it) }?.let { File(storageDir, it + FILE_SUFFIX) }
            if (file == null || !file.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // LocalFileContent liefert Content-Length, das PartialContent-Plugin (in installEmberlightModule) beantwortet Range.
            call.respond(LocalFileContent(file, ContentType.Application.OctetStream))
        }
    }

private sealed interface StoreOutcome {
    data class Stored(
        val id: String,
        val size: Long,
    ) : StoreOutcome

    data object NoFile : StoreOutcome

    data object TooLarge : StoreOutcome
}

/**
 * Schreibt die erste Datei des Formulars. Weitere Teile werden verworfen. Die Größe zählt der Code selbst, weil
 * `formFieldLimit` nur Formularfelder begrenzt, nicht Dateiteile.
 */
private suspend fun storeFirstFile(
    parts: MultiPartData,
    storageDir: File,
    maxBytes: Long,
    onProgress: (Long) -> Unit,
): StoreOutcome {
    var outcome: StoreOutcome = StoreOutcome.NoFile
    parts.forEachPart { part ->
        if (outcome == StoreOutcome.NoFile && part is PartData.FileItem) {
            outcome = writeLimited(part, storageDir, maxBytes, onProgress)
        }
        part.release()
    }
    return outcome
}

private suspend fun writeLimited(
    part: PartData.FileItem,
    storageDir: File,
    maxBytes: Long,
    onProgress: (Long) -> Unit,
): StoreOutcome =
    withContext(Dispatchers.IO) {
        storageDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val target = File(storageDir, id + FILE_SUFFIX)
        var written = 0L
        var reported = 0L
        val channel = part.provider()
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            target.outputStream().use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) {
                        throw TooLargeUpload()
                    }
                    out.write(buffer, 0, read)
                    if (written - reported >= PROGRESS_STEP_BYTES) {
                        reported = written
                        onProgress(written)
                    }
                }
            }
            onProgress(written)
            StoreOutcome.Stored(id, written)
        } catch (tooLarge: TooLargeUpload) {
            target.delete()
            StoreOutcome.TooLarge
        } catch (failure: Throwable) {
            // Auch Abbruch (CancellationException) oder Ein-/Ausgabefehler: keine halbe Datei zurücklassen.
            target.delete()
            throw failure
        }
    }

private class TooLargeUpload : IOException("Upload überschreitet das Limit.")
