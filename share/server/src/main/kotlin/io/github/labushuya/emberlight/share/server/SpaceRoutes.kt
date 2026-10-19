package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.DeleteOutcome
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaCursor
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.MediaListEntry
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.StorageWarning
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Uhr, Grenzen und Abfragezyklus des Uploads. Im Test ersetzbar, in der Produktion die Standardwerte. */
data class UploadTuning(
    val clock: MonotonicClock = MonotonicClock.System,
    val policy: TransferPolicy = TransferPolicy(),
    val pollMillis: Long = 1_000,
)

/**
 * Routen der Schnittstelle v1 (ADR 0014). Alle liegen unter `/api/v1` und hinter der Zugriffskette des Servers.
 * Der Server bedient genau einen Space. Die Pfade enthalten keine Space-ID.
 */
fun spaceRoutes(
    vault: SpaceVault,
    spaceName: String,
    gates: ServerGates,
    tuning: UploadTuning = UploadTuning(),
): Route.() -> Unit =
    {
        route("/api/v1") {
            get("/space") {
                call.routeTemplate("/api/v1/space")
                val usage = vault.usage()
                call.respondJson(
                    HttpStatusCode.OK,
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(spaceName),
                            "storageLimitBytes" to JsonPrimitive(usage.limitBytes),
                            "usedBytes" to JsonPrimitive(usage.usedBytes),
                            "warning" to JsonPrimitive(usage.warning),
                        ),
                    ),
                )
            }

            get("/media") {
                call.routeTemplate("/api/v1/media")
                val rawLimit = call.request.queryParameters["limit"]
                val limit =
                    if (rawLimit == null) {
                        SpaceVault.DEFAULT_PAGE
                    } else {
                        rawLimit.toIntOrNull()?.takeIf { it in 1..SpaceVault.MAX_PAGE }
                    }
                if (limit == null) {
                    call.respondError(HttpStatusCode.BadRequest, "bad_request")
                    return@get
                }
                val rawCursor = call.request.queryParameters["cursor"]
                val cursor =
                    if (rawCursor == null) {
                        null
                    } else {
                        MediaCursor.decode(rawCursor) ?: run {
                            call.respondError(HttpStatusCode.BadRequest, "bad_cursor")
                            return@get
                        }
                    }
                val page = vault.list(cursor, limit)
                call.respondJson(
                    HttpStatusCode.OK,
                    JsonObject(
                        mapOf(
                            "items" to JsonArray(page.items.map(::listItemJson)),
                            "nextCursor" to (page.nextCursor?.encode()?.let { JsonPrimitive(it) } ?: JsonNull),
                        ),
                    ),
                )
            }

            post("/media") {
                call.routeTemplate("/api/v1/media")
                val principal = call.principal()
                if (!gates.tryEnterUpload(principal.participantId)) {
                    call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
                    call.respondError(HttpStatusCode.TooManyRequests, "too_many_uploads")
                    return@post
                }
                try {
                    handleUpload(call, vault, tuning)
                } finally {
                    gates.leaveUpload(principal.participantId)
                }
            }

            get("/media/{id}/content") {
                call.routeTemplate("/api/v1/media/{id}/content")
                val id = MediaId.parse(call.parameters["id"].orEmpty())
                val file = id?.let { vault.contentOf(it) }
                if (file == null) {
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                    return@get
                }
                call.respondContent(
                    file = file.path.toFile(),
                    contentType = ContentType.parse(file.record.mime),
                    disposition = ContentDispositionHeader.attachment(file.record.originalName),
                )
            }

            delete("/media/{id}") {
                call.routeTemplate("/api/v1/media/{id}")
                val id = MediaId.parse(call.parameters["id"].orEmpty())
                if (id == null) {
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                    return@delete
                }
                when (vault.delete(call.principal(), id)) {
                    DeleteOutcome.Deleted -> call.respond(HttpStatusCode.NoContent)
                    DeleteOutcome.NotFound -> call.respondError(HttpStatusCode.NotFound, "not_found")
                    DeleteOutcome.Forbidden -> call.respondError(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
    }

private suspend fun handleUpload(
    call: io.ktor.server.application.ApplicationCall,
    vault: SpaceVault,
    tuning: UploadTuning,
) {
    if (call.request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
        call.respondError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        return
    }
    val name = UploadFilenameHeader.decode(call.request.headers[UploadFilenameHeader.NAME])
    if (name == null) {
        call.respondError(HttpStatusCode.BadRequest, "bad_filename")
        return
    }
    val rawLength = call.request.headers[HttpHeaders.ContentLength]
    val declared =
        if (rawLength == null) {
            null
        } else {
            rawLength.toLongOrNull()?.takeIf { it >= 0 } ?: run {
                call.respondError(HttpStatusCode.BadRequest, "bad_request")
                return
            }
        }
    val source = BodyChunkSource(call.receiveChannel(), tuning.clock, tuning.policy, tuning.pollMillis, declared)
    val result =
        try {
            vault.ingest(call.principal(), name, declared, source)
        } catch (abort: TransferAbortedException) {
            call.respondError(
                HttpStatusCode.RequestTimeout,
                "request_timeout",
                mapOf("reason" to JsonPrimitive(abort.violation.wireName)),
            )
            return
        } catch (incomplete: IncompleteBodyException) {
            call.respondError(HttpStatusCode.BadRequest, "incomplete_body")
            return
        }
    when (result) {
        is IngestResult.Created -> call.respondUploaded(result.record, result.warning, duplicate = false)
        is IngestResult.Duplicate -> call.respondUploaded(result.record, result.warning, duplicate = true)
        is IngestResult.Rejected -> call.respondRejected(result.reason)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondUploaded(
    record: io.github.labushuya.emberlight.share.space.MediaRecord,
    warning: StorageWarning?,
    duplicate: Boolean,
) {
    val status = if (duplicate) HttpStatusCode.OK else HttpStatusCode.Created
    respondJson(
        status,
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(record.id.value),
                "type" to JsonPrimitive(record.mime),
                "size" to JsonPrimitive(record.sizeBytes),
                "duplicate" to JsonPrimitive(duplicate),
                "warning" to (warning?.let { JsonPrimitive("storage_80") } ?: JsonNull),
            ),
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondRejected(reason: RejectReason) {
    when (reason) {
        RejectReason.UnsupportedType -> {
            respondError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        }

        is RejectReason.TooLarge -> {
            respondError(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                mapOf("limit" to JsonPrimitive(reason.limitBytes)),
            )
        }

        RejectReason.StorageFull -> {
            respondError(HttpStatusCode.InsufficientStorage, "storage_full")
        }

        RejectReason.DeviceStorageLow -> {
            respondError(HttpStatusCode.InsufficientStorage, "device_storage_low")
        }
    }
}

private fun listItemJson(entry: MediaListEntry): JsonElement =
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(entry.id.value),
            "uploader" to (entry.uploaderName?.let { JsonPrimitive(it) } ?: JsonNull),
            "type" to JsonPrimitive(entry.mime),
            "size" to JsonPrimitive(entry.sizeBytes),
            "createdAt" to JsonPrimitive(entry.createdAt),
        ),
    )
