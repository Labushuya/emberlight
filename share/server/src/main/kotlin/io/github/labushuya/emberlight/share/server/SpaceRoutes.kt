package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.DeleteOutcome
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaCursor
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.MediaListEntry
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.StorageWarning
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.ParticipantInfo
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

/** Größe des Beitritts-Körpers in Bytes. Mehr ist kein gültiger Beitritt (ADR 0018). */
internal const val JOIN_MAX_BODY_BYTES = 4_096L

private val json = Json { ignoreUnknownKeys = true }

/** Körper von `POST /api/v1/join`. Geheimnis und Name stehen nur hier, nie in URL oder Header (ADR 0018). */
@Serializable
private data class JoinRequest(
    val inviteSecret: String,
    val displayName: String,
)

/** Körper von `POST /api/v1/join/preview`. Das Geheimnis steht nur im Körper (ADR 0018). */
@Serializable
private data class PreviewRequest(
    val inviteSecret: String,
)

/**
 * Routen der Schnittstelle v1 (ADR 0014, ADR 0018). Alle liegen unter `/api/v1` und hinter der Zugriffskette des Servers.
 * Der Server bedient genau einen Space. Die Pfade enthalten keine Space-ID.
 *
 * Rechte: GUEST darf lesen, hochladen, eigene Uploads zurückziehen und sich selbst abmelden. HOST darf zusätzlich
 * Teilnehmer entfernen, die Einladung erneuern und jedes Medium löschen (ADR 0018, Autorisierung).
 */
fun spaceRoutes(
    access: SpaceAccess,
    gates: ServerGates,
    tuning: UploadTuning = UploadTuning(),
): Route.() -> Unit =
    {
        val vault = access.vault
        route("/api/v1") {
            get("/space") {
                call.routeTemplate("/api/v1/space")
                val usage = vault.usage()
                call.respondJson(
                    HttpStatusCode.OK,
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(access.spaceName),
                            "storageLimitBytes" to JsonPrimitive(usage.limitBytes),
                            "usedBytes" to JsonPrimitive(usage.usedBytes),
                            "warning" to JsonPrimitive(usage.warning),
                        ),
                    ),
                )
            }

            post("/join") {
                call.routeTemplate(JOIN_PATH)
                call.handleJoin(access)
            }

            post("/join/preview") {
                call.routeTemplate(JOIN_PREVIEW_PATH)
                call.handleJoinPreview(access)
            }

            get("/me") {
                call.routeTemplate("/api/v1/me")
                val principal = call.principal()
                val info = vault.participant(principal.participantId)
                if (info == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized")
                    return@get
                }
                call.respondJson(HttpStatusCode.OK, participantJson(info, access.spaceId.value))
            }

            delete("/me") {
                call.routeTemplate("/api/v1/me")
                val principal = call.principal()
                if (principal.role == Role.HOST) {
                    call.respondError(HttpStatusCode.Forbidden, "forbidden")
                    return@delete
                }
                // Abmelden: der Token ist sofort ungültig, die Uploads bleiben und zeigen „entfernt“ (ADR 0018).
                access.removeGuest(principal.participantId, deleteUploads = false)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/participants") {
                call.routeTemplate("/api/v1/participants")
                if (!call.requireHost()) return@get
                call.respondJson(
                    HttpStatusCode.OK,
                    JsonObject(mapOf("items" to JsonArray(vault.participants().map(::participantListJson)))),
                )
            }

            delete("/participants/{id}") {
                call.routeTemplate("/api/v1/participants/{id}")
                if (!call.requireHost()) return@delete
                val id = ParticipantId.parse(call.parameters["id"].orEmpty())
                if (id == null) {
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                    return@delete
                }
                val deleteUploads =
                    when (call.request.queryParameters["deleteUploads"]) {
                        null, "false" -> {
                            false
                        }

                        "true" -> {
                            true
                        }

                        else -> {
                            call.respondError(HttpStatusCode.BadRequest, "bad_request")
                            return@delete
                        }
                    }
                when (access.removeGuest(id, deleteUploads)) {
                    RemoveOutcome.Removed -> call.respond(HttpStatusCode.NoContent)
                    RemoveOutcome.NotFound -> call.respondError(HttpStatusCode.NotFound, "not_found")
                    RemoveOutcome.HostCannotBeRemoved -> call.respondError(HttpStatusCode.Forbidden, "forbidden")
                }
            }

            post("/invite/rotate") {
                call.routeTemplate("/api/v1/invite/rotate")
                if (!call.requireHost()) return@post
                val fresh = access.rotateInvite()
                call.respondJson(HttpStatusCode.OK, JsonObject(mapOf("invite" to JsonPrimitive(fresh))))
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
                    call.refuseEarly(HttpStatusCode.TooManyRequests, "too_many_uploads")
                    return@post
                }
                try {
                    call.runUpload(access, tuning, principal)
                } finally {
                    gates.leaveUpload(principal.participantId)
                }
            }

            get("/media/{id}/content") {
                call.routeTemplate("/api/v1/media/{id}/content")
                call.respondMedia(vault, call.parameters["id"], inline = false)
            }

            // Ansicht im Browser: Original inline (ADR 0019). Die HEIC-JPEG-Ansicht folgt mit den Vorschauen.
            get("/media/{id}/view") {
                call.routeTemplate("/api/v1/media/{id}/view")
                call.respondMedia(vault, call.parameters["id"], inline = true)
            }

            // Video mit Range (ADR 0019). Nur Videos, sonst 404.
            get("/media/{id}/stream") {
                call.routeTemplate("/api/v1/media/{id}/stream")
                call.respondMedia(vault, call.parameters["id"], inline = true, onlyVideo = true)
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

/**
 * Medienantwort für Download ([inline] = `false`, Anhang) und Ansicht bzw. Stream ([inline] = `true`). Fehlt das Medium,
 * ist es kein Video bei [onlyVideo] oder ist die Datei weg, antwortet die Route mit `404`. Mehrere Bereiche ergeben `416`.
 */
private suspend fun ApplicationCall.respondMedia(
    vault: SpaceVault,
    rawId: String?,
    inline: Boolean,
    onlyVideo: Boolean = false,
) {
    val id = rawId?.let { MediaId.parse(it) }
    val file = id?.let { vault.contentOf(it) }
    if (file == null || (onlyVideo && !file.record.mime.startsWith("video/"))) {
        respondError(HttpStatusCode.NotFound, "not_found")
        return
    }
    if (rejectMultipleRanges(file.record.sizeBytes)) {
        respondError(HttpStatusCode.RequestedRangeNotSatisfiable, "range_not_satisfiable")
        return
    }
    respondContent(
        file = file.path.toFile(),
        contentType = ContentType.parse(file.record.mime),
        disposition = if (inline) "inline" else ContentDispositionHeader.attachment(file.record.originalName),
    )
}

/** Antwort 403 für Nicht-Gastgeber. Liefert `false`, wenn die Anfrage abgewiesen wurde. */
private suspend fun ApplicationCall.requireHost(): Boolean {
    if (principal().role == Role.HOST) return true
    respondError(HttpStatusCode.Forbidden, "forbidden")
    return false
}

/**
 * Beitritt (ADR 0018). Nur `application/json`, Körper bis [JOIN_MAX_BODY_BYTES]. Eine falsche Einladung zählt als
 * Fehlversuch für die Drosselung. Die Antwort mit dem Token kommt nur hier vor.
 */
private suspend fun ApplicationCall.handleJoin(access: SpaceAccess) {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        refuseEarly(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        return
    }
    val text = receiveBoundedText(JOIN_MAX_BODY_BYTES)
    if (text == null) {
        refuseEarly(
            HttpStatusCode.PayloadTooLarge,
            "payload_too_large",
            mapOf("limit" to JsonPrimitive(JOIN_MAX_BODY_BYTES)),
        )
        return
    }
    val body =
        try {
            json.decodeFromString<JoinRequest>(text)
        } catch (malformed: SerializationException) {
            respondError(HttpStatusCode.BadRequest, "bad_request")
            return
        }
    when (val outcome = access.join(body.inviteSecret, body.displayName)) {
        is JoinOutcome.Joined -> {
            respondJson(
                HttpStatusCode.Created,
                JsonObject(
                    mapOf(
                        "participantId" to JsonPrimitive(outcome.participantId.value),
                        "displayName" to JsonPrimitive(outcome.displayName),
                        "role" to JsonPrimitive(outcome.role.name),
                        "token" to JsonPrimitive(outcome.token),
                        "spaceId" to JsonPrimitive(access.spaceId.value),
                    ),
                ),
            )
        }

        JoinOutcome.InviteInvalid -> {
            attributes.put(JoinFailedKey, true)
            refuseEarly(HttpStatusCode.Forbidden, "invite_invalid")
        }

        is JoinOutcome.NameInvalid -> {
            respondError(
                HttpStatusCode.UnprocessableEntity,
                "name_invalid",
                mapOf("reason" to JsonPrimitive(outcome.reason.name.lowercase())),
            )
        }

        is JoinOutcome.NameTaken -> {
            respondError(
                HttpStatusCode.Conflict,
                "name_taken",
                mapOf("suggestion" to JsonPrimitive(outcome.suggestion)),
            )
        }

        JoinOutcome.SpaceFull -> {
            respondError(HttpStatusCode.Conflict, "space_full")
        }
    }
}

/**
 * Vorschau vor dem Beitritt (Paket 3.4, ADR 0018). Name und Farbe gibt es nur mit gültigem Einladungsgeheimnis. Fehlversuche
 * zählen wie beim Beitritt. Das Titelbild und die Teilnehmer bleiben außen vor.
 */
private suspend fun ApplicationCall.handleJoinPreview(access: SpaceAccess) {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        refuseEarly(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        return
    }
    val text = receiveBoundedText(JOIN_MAX_BODY_BYTES)
    if (text == null) {
        refuseEarly(
            HttpStatusCode.PayloadTooLarge,
            "payload_too_large",
            mapOf("limit" to JsonPrimitive(JOIN_MAX_BODY_BYTES)),
        )
        return
    }
    val body =
        try {
            json.decodeFromString<PreviewRequest>(text)
        } catch (malformed: SerializationException) {
            respondError(HttpStatusCode.BadRequest, "bad_request")
            return
        }
    if (!access.invitePreview(body.inviteSecret)) {
        attributes.put(JoinFailedKey, true)
        refuseEarly(HttpStatusCode.Forbidden, "invite_invalid")
        return
    }
    respondJson(
        HttpStatusCode.OK,
        JsonObject(
            mapOf(
                "spaceName" to JsonPrimitive(access.spaceName),
                "accentColor" to JsonPrimitive(access.accentColor),
            ),
        ),
    )
}

/** Liest den Körper bis [limit] Bytes. Liefert `null`, wenn er größer ist (ohne ihn ganz zu lesen). */
private suspend fun ApplicationCall.receiveBoundedText(limit: Long): String? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) return null
    val bytes = request.receiveChannel().readBuffer(limit + 1).readByteArray()
    if (bytes.size > limit) return null
    return bytes.toString(Charsets.UTF_8)
}

/**
 * Upload mit Abbruchmöglichkeit (ADR 0018). Der Upload läuft als Kind-Auftrag und ist im Verzeichnis [ActiveUploads]
 * eingetragen. Wird der Teilnehmer entfernt, bricht der Auftrag ab, die Vault räumt die Teildatei weg, und der Client
 * bekommt `401`.
 */
private suspend fun ApplicationCall.runUpload(
    access: SpaceAccess,
    tuning: UploadTuning,
    principal: Principal,
) {
    coroutineScope {
        val work = async { handleUpload(this@runUpload, access.vault, tuning) }
        access.uploads.add(principal.participantId, work)
        try {
            // Schließt das Fenster zwischen Prüfung und Eintrag: Ein schon entfernter Teilnehmer bricht hier ab.
            if (access.vault.participant(principal.participantId) ==
                null
            ) {
                work.cancel(CancellationException("Entfernt."))
            }
            work.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            refuseEarly(HttpStatusCode.Unauthorized, "unauthorized")
        } finally {
            access.uploads.remove(principal.participantId, work)
        }
    }
}

private suspend fun handleUpload(
    call: ApplicationCall,
    vault: SpaceVault,
    tuning: UploadTuning,
) {
    if (call.request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
        call.refuseEarly(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        return
    }
    val name = UploadFilenameHeader.decode(call.request.headers[UploadFilenameHeader.NAME])
    if (name == null) {
        call.refuseEarly(HttpStatusCode.BadRequest, "bad_filename")
        return
    }
    // Einwilligung (ADR 0015): nur der exakte Wert "1" gilt. Alles andere ist ein Fehler, keine stille Annahme.
    val keepLocation =
        when (call.request.headers[KeepLocationHeader.NAME]) {
            null -> {
                false
            }

            KeepLocationHeader.YES -> {
                true
            }

            else -> {
                call.refuseEarly(HttpStatusCode.BadRequest, "bad_request")
                return
            }
        }
    val rawLength = call.request.headers[HttpHeaders.ContentLength]
    val declared =
        if (rawLength == null) {
            null
        } else {
            rawLength.toLongOrNull()?.takeIf { it >= 0 } ?: run {
                call.refuseEarly(HttpStatusCode.BadRequest, "bad_request")
                return
            }
        }
    val source = BodyChunkSource(call.receiveChannel(), tuning.clock, tuning.policy, tuning.pollMillis, declared)
    val result =
        try {
            vault.ingest(call.principal(), name, declared, source, keepLocation)
        } catch (abort: TransferAbortedException) {
            call.refuseEarly(
                HttpStatusCode.RequestTimeout,
                "request_timeout",
                mapOf("reason" to JsonPrimitive(abort.violation.wireName)),
            )
            return
        } catch (incomplete: IncompleteBodyException) {
            call.refuseEarly(HttpStatusCode.BadRequest, "incomplete_body")
            return
        }
    when (result) {
        is IngestResult.Created -> call.respondUploaded(result.record, result.warning, duplicate = false)
        is IngestResult.Duplicate -> call.respondUploaded(result.record, result.warning, duplicate = true)
        is IngestResult.Rejected -> call.respondRejected(result.reason)
    }
}

private suspend fun ApplicationCall.respondUploaded(
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
                "locationKept" to JsonPrimitive(record.locationKept),
                "warning" to (warning?.let { JsonPrimitive("storage_80") } ?: JsonNull),
            ),
        ),
    )
}

private suspend fun ApplicationCall.respondRejected(reason: RejectReason) {
    when (reason) {
        RejectReason.UnsupportedType -> {
            refuseEarly(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        }

        is RejectReason.TooLarge -> {
            refuseEarly(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                mapOf("limit" to JsonPrimitive(reason.limitBytes)),
            )
        }

        RejectReason.StorageFull -> {
            refuseEarly(HttpStatusCode.InsufficientStorage, "storage_full")
        }

        RejectReason.DeviceStorageLow -> {
            refuseEarly(HttpStatusCode.InsufficientStorage, "device_storage_low")
        }

        is RejectReason.LocationNotRemovable -> {
            refuseEarly(
                HttpStatusCode.UnprocessableEntity,
                "location_not_removable",
                mapOf("reason" to JsonPrimitive(reason.reason.code)),
            )
        }
    }
}

private fun listItemJson(entry: MediaListEntry): JsonElement =
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(entry.id.value),
            "uploader" to (entry.uploaderName?.let { JsonPrimitive(it) } ?: JsonNull),
            "uploaderRemoved" to JsonPrimitive(entry.uploaderRemoved),
            "type" to JsonPrimitive(entry.mime),
            "size" to JsonPrimitive(entry.sizeBytes),
            "createdAt" to JsonPrimitive(entry.createdAt),
            "locationKept" to JsonPrimitive(entry.locationKept),
        ),
    )

private fun participantJson(
    info: ParticipantInfo,
    spaceId: String,
): JsonObject =
    JsonObject(
        mapOf(
            "spaceId" to JsonPrimitive(spaceId),
            "participantId" to JsonPrimitive(info.id.value),
            "displayName" to JsonPrimitive(info.displayName),
            "role" to JsonPrimitive(info.role.name),
        ),
    )

private fun participantListJson(info: ParticipantInfo): JsonElement =
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(info.id.value),
            "displayName" to JsonPrimitive(info.displayName),
            "role" to JsonPrimitive(info.role.name),
            "joinedAt" to JsonPrimitive(info.joinedAtMillis),
        ),
    )
