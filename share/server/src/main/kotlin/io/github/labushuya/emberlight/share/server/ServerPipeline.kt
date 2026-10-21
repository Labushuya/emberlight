package io.github.labushuya.emberlight.share.server

import android.util.Log
import io.github.labushuya.emberlight.share.space.Principal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** Teilnehmer der Anfrage. Die Zugriffsprüfung setzt ihn, Routen lesen ihn. */
internal val PrincipalKey = AttributeKey<Principal>("emberlight.principal")

/** Route-Template für das Protokoll, z. B. `/api/v1/media/{id}/content`. Ohne konkrete IDs. */
internal val RouteTemplateKey = AttributeKey<String>("emberlight.route-template")

/** Gesetzt von der Beitritts-Route, wenn die Einladung falsch war. Zählt als Fehlversuch (ADR 0018). */
internal val JoinFailedKey = AttributeKey<Boolean>("emberlight.join-failed")

/** Grenze für das Verwerfen nach einer frühen Ablehnung, aus [ServerEnvironment.drainMaxBytes]. */
internal val DrainLimitKey = AttributeKey<Long>("emberlight.drain-limit")

private const val UNMATCHED_ROUTE = "(keine Route)"

/** Pfad des Beitritts. Er braucht keine Zugriffsprüfung, aber eine Drosselung (ADR 0018). */
internal const val JOIN_PATH = "/api/v1/join"

/** Vorschau vor dem Beitritt (Paket 3.4, ADR 0018). Wie der Beitritt: ohne Zugriffsprüfung, mit derselben Drosselung. */
internal const val JOIN_PREVIEW_PATH = "/api/v1/join/preview"

/** Header, der Browser am MIME-Sniffing hindert (ADR 0013, Downloads). */
private const val NOSNIFF_HEADER = "X-Content-Type-Options"

/** Sekunden, nach denen ein 503 oder 429 zur Wiederholung auffordert (ADR 0014). */
internal const val RETRY_AFTER_SECONDS = "5"

/**
 * Höchstens so viele Körper-Bytes werden nach einer frühen Ablehnung verworfen (ADR 0014, lingering close). Mit 1 MiB
 * ging die Statuszeile auf dem Loopback des Windows-PCs verloren: Der Server schloss mit ungelesenen Bytes, Windows verwarf den
 * Empfang (RST). Die harte Grenze ist deshalb [DRAIN_TIMEOUT_MS], die Bytegrenze liegt über dem Upload-Limit der Tests.
 */
internal const val DRAIN_MAX_BYTES = 64L shl 20

/** Höchstdauer für das Verwerfen des Körpers nach einer frühen Ablehnung. */
internal const val DRAIN_TIMEOUT_MS = 2_000L

/**
 * Alles, was die Zugriffskette braucht. [expectedHost] liefert den Host erst nach dem Start, deshalb sind es Funktionen.
 */
internal class ServerEnvironment(
    val authorizer: RequestAuthorizer,
    val expectedHost: () -> String?,
    /** Prüft die Absenderadresse. Produktion: liegt im Subnetz der WLAN-Schnittstelle (ADR 0013). */
    val senderAllowed: (String) -> Boolean,
    val gates: ServerGates,
    val requestLog: (String) -> Unit,
    val debugLogging: Boolean,
    /** Ablauf des Space (ADR 0017). Wirkt nach der Zugriffsprüfung, vor den Routen. */
    val closure: SpaceClosure,
    val joinLimiter: JoinLimiter = JoinLimiter(),
    /** Höchstzahl verworfener Körper-Bytes nach einer frühen Ablehnung. Nur Tests ändern sie. */
    val drainMaxBytes: Long = DRAIN_MAX_BYTES,
    /** Signierte Medien-URLs (ADR 0019). `null` schaltet sie ab, dann gilt nur der Header. */
    val signedMedia: SignedMediaAuthorizer? = null,
)

/**
 * Installiert die Zugriffskette und die Routen. Die Kette läuft in der Phase `Setup`, also vor jedem Plugin und vor
 * jeder Route (ADR 0013, Reihenfolge der Prüfungen, ADR 0018):
 *
 * 1. Protokoll (Eingang und Ausgang, Route-Template, ohne Header und Token)
 * 2. Host-Header muss exakt `<ip>:<port>` sein, sonst `403`
 * 3. Absender muss im Subnetz der WLAN-Schnittstelle liegen, sonst `403`
 * 4. Drosselung nur für den Beitritt, sonst `429`
 * 5. `Authorization` genau einmal, gültig, sonst `401`. Entfällt für den Beitritt und für die Gäste-Seite.
 * 6. Space-Zustand, nach dem Ablauf `410` (`space_closed`). Entfällt für die Gäste-Seite.
 * 7. Parallelität: Anfrage-Platz, sonst `503`
 *
 * Jede Ablehnung läuft über [refuseEarly] (lingering close). Erst danach laufen die Plugins (Range) und die Routen.
 */
internal fun Application.installEmberlightModule(
    env: ServerEnvironment,
    routes: Route.() -> Unit,
) {
    intercept(ApplicationCallPipeline.Setup) {
        val startedAt = System.nanoTime()
        context.attributes.put(DrainLimitKey, env.drainMaxBytes)
        try {
            proceed()
        } finally {
            val status = context.response.status()?.value ?: 0
            val template = context.attributes.getOrNull(RouteTemplateKey) ?: UNMATCHED_ROUTE
            val ms = (System.nanoTime() - startedAt) / 1_000_000L
            val origin = if (env.debugLogging) " from=${context.request.origin.remoteAddress}" else ""
            if (context.attributes.getOrNull(JoinFailedKey) == true) {
                env.joinLimiter.recordFailure(context.request.origin.remoteAddress)
            }
            env.requestLog(
                "req method=${context.request.local.method.value} route=$template status=$status ms=$ms$origin",
            )
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val expected = env.expectedHost()
        if (expected == null || context.request.headers[HttpHeaders.Host] != expected) {
            context.refuseEarly(HttpStatusCode.Forbidden, "forbidden_host")
            finish()
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!env.senderAllowed(context.request.origin.remoteAddress)) {
            context.refuseEarly(HttpStatusCode.Forbidden, "forbidden_network")
            finish()
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (context.isInviteRequest()) {
            val wait = env.joinLimiter.retryAfterSeconds(context.request.origin.remoteAddress)
            if (wait != null) {
                context.response.headers.append(HttpHeaders.RetryAfter, wait.toString())
                context.refuseEarly(HttpStatusCode.TooManyRequests, "rate_limited")
                finish()
            }
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!context.isPublicSite() && !context.isInviteRequest()) {
            // Signierte Medien-URL (ADR 0019): nur GET der Medienrouten, nur mit Signatur-Parametern in der Anfrage.
            when (val signed = context.signedMediaAccess(env.signedMedia)) {
                is SignedMediaAccess.Granted -> {
                    context.attributes.put(PrincipalKey, signed.principal)
                }

                is SignedMediaAccess.Denied -> {
                    context.refuseEarly(HttpStatusCode.Forbidden, signed.code)
                    finish()
                }

                SignedMediaAccess.Absent -> {
                    val values =
                        context.request.headers
                            .getAll(HttpHeaders.Authorization)
                            .orEmpty()
                    // Mehrere Authorization-Header sind ein Fehler. Der erste Wert wird nicht genommen (ADR 0013).
                    val principal = if (values.size == 1) env.authorizer.authorize(values.single()) else null
                    if (principal == null) {
                        context.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                        context.refuseEarly(HttpStatusCode.Unauthorized, "unauthorized")
                        finish()
                    } else {
                        context.attributes.put(PrincipalKey, principal)
                    }
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        // Nach dem Ablauf antwortet jede Anfrage mit 410, auch ohne gültigen Schlüssel. Der Betrieb stoppt danach (ADR 0017).
        if (!context.isPublicSite() && env.closure.isClosed()) {
            env.closure.onClosedRequest()
            val endedAt = env.closure.endedAtMillis()
            val extra =
                endedAt?.let { mapOf("endedAt" to JsonPrimitive(Instant.ofEpochMilli(it).toString())) }.orEmpty()
            context.refuseEarly(HttpStatusCode.Gone, "space_closed", extra)
            finish()
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!env.gates.tryEnterRequest()) {
            context.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
            context.refuseEarly(HttpStatusCode.ServiceUnavailable, "service_busy")
            finish()
        } else {
            try {
                proceed()
            } finally {
                env.gates.leaveRequest()
            }
        }
    }

    install(PartialContent)
    routing {
        get("/health") {
            call.attributes.put(RouteTemplateKey, "/health")
            call.respondJson(
                HttpStatusCode.OK,
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "apiVersion" to JsonPrimitive(SERVER_API_VERSION),
                    ),
                ),
            )
        }
        routes()
    }
}

/** Beitritts-Anfrage? `POST /api/v1/join` und die Vorschau `POST /api/v1/join/preview` (ADR 0018). */
internal fun ApplicationCall.isInviteRequest(): Boolean =
    request.local.method == HttpMethod.Post && request.path() in INVITE_PATHS

private val INVITE_PATHS = setOf(JOIN_PATH, JOIN_PREVIEW_PATH)

/** Gäste-Seite und ihre Dateien. Ohne Zugriffsprüfung, weil die Dateien keine Daten des Space enthalten (ADR 0018). */
internal fun ApplicationCall.isPublicSite(): Boolean =
    request.local.method == HttpMethod.Get && GuestSite.isPublicPath(request.path())

/** Medienroute mit ID und Zweck. Nur GET-Routen dieser Liste nehmen signierte URLs an (ADR 0019). */
internal class MediaRoute(
    val id: String,
    val purpose: MediaPurpose,
)

private val MEDIA_ROUTE = Regex("^/api/v1/media/([^/]+)/(preview|view|content|stream)$")

/** Ordnet einen Pfad einer Medienroute zu. `null` für alle anderen Pfade. */
internal fun mediaRouteOf(path: String): MediaRoute? {
    val match = MEDIA_ROUTE.matchEntire(path) ?: return null
    val purpose =
        when (match.groupValues[2]) {
            "preview" -> MediaPurpose.PREVIEW
            "view" -> MediaPurpose.VIEW
            "content" -> MediaPurpose.DOWNLOAD
            else -> MediaPurpose.STREAM
        }
    return MediaRoute(match.groupValues[1], purpose)
}

/**
 * Prüfung per signierter URL für diese Anfrage. [SignedMediaAccess.Absent], wenn die Anfrage keine Medienroute per GET ist
 * oder keine Signatur-Parameter trägt. Mehrfach gesendete Parameter gelten als ungültig.
 */
internal fun ApplicationCall.signedMediaAccess(authorizer: SignedMediaAuthorizer?): SignedMediaAccess {
    if (authorizer == null || request.local.method != HttpMethod.Get) return SignedMediaAccess.Absent
    val route = mediaRouteOf(request.path()) ?: return SignedMediaAccess.Absent
    val query = request.queryParameters

    fun one(name: String): String? = query.getAll(name)?.let { values -> if (values.size == 1) values.single() else "" }
    return authorizer.authorize(route.id, route.purpose, one("pid"), one("exp"), one("sig"))
}

/** Teilnehmer der Anfrage. Nur nach erfolgreicher Zugriffsprüfung aufrufbar. */
internal fun ApplicationCall.principal(): Principal =
    checkNotNull(attributes.getOrNull(PrincipalKey)) {
        "Kein Teilnehmer gesetzt."
    }

/** Antwort mit JSON und den Schutzheadern. Für alle API-Antworten außer dem Medieninhalt. */
internal suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: JsonObject,
) {
    applyApiHeaders()
    respondText(body.toString(), ContentType.Application.Json, status)
}

/**
 * Einheitliches Fehlerformat `{"error":"<code>"}` plus optionale Felder (ADR 0014). Jede Fehlerantwort wird mit Status und
 * Code protokolliert (Hotfix 2, Tag `EmberlightServer`), auch im Release. Protokolliert werden nie Namen, Pfade oder
 * Dateiinhalte, nur die festen Codes. Lesen: `adb logcat -s EmberlightServer`, siehe `DEBUG-SERVER.md`.
 */
internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    extra: Map<String, JsonElement> = emptyMap(),
) {
    logResponse(status, code)
    respondJson(status, JsonObject(mapOf("error" to JsonPrimitive(code)) + extra))
}

/**
 * Protokollzeile zu einer Fehlerantwort. Ein Fehler im Protokoll darf die Antwort nie verhindern. Auf dem JVM ohne
 * Android-Stub wirft `Log` eine Ausnahme, deshalb wird sie hier abgefangen.
 */
private fun logResponse(
    status: HttpStatusCode,
    code: String,
) {
    try {
        val message = "Antwort ${status.value}: $code"
        if (status.value >= SERVER_ERROR_MIN) Log.e(SERVER_LOG_TAG, message) else Log.w(SERVER_LOG_TAG, message)
    } catch (unavailable: RuntimeException) {
        // Ohne Android-Laufzeit (Unit-Tests auf dem JVM) gibt es kein Protokoll. Die Antwort geht trotzdem raus.
    }
}

private const val SERVER_LOG_TAG = "EmberlightServer"
private const val SERVER_ERROR_MIN = 500

/**
 * Frühe Ablehnung mit lingering close (ADR 0014, ADR 0018). Die Antwort trägt `Connection: close`. Danach wird der
 * Körper, falls er noch läuft, bis [DRAIN_MAX_BYTES] oder [DRAIN_TIMEOUT_MS] gelesen und verworfen. Erst dann endet der
 * Handler. So schließt der Server nicht mit ungelesenen Bytes im Puffer, und der Client erhält die Statuszeile.
 */
internal suspend fun ApplicationCall.refuseEarly(
    status: HttpStatusCode,
    code: String,
    extra: Map<String, JsonElement> = emptyMap(),
) {
    response.headers.append(HttpHeaders.Connection, "close")
    respondError(status, code, extra)
    drainRequestBody()
}

/** Liest den Restkörper bis zur Grenze und verwirft ihn. Ein Fehler oder Zeitablauf beendet nur den Rest. */
internal suspend fun ApplicationCall.drainRequestBody() {
    val body = request.receiveChannel()
    val limit = attributes.getOrNull(DrainLimitKey) ?: DRAIN_MAX_BYTES
    try {
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
            body.discard(limit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        // Der Client hat die Verbindung bereits geschlossen. Die Antwort ist raus.
    } finally {
        // Danach nichts mehr lesen. Sonst liest CIO den Körper ganz vom Socket, auch nach dem Ende des Handlers.
        body.cancel(null)
    }
}

/** Datei-Antwort für den Medieninhalt, mit dem Typ aus der Erkennung und `nosniff`. Range übernimmt [PartialContent]. */
internal suspend fun ApplicationCall.respondContent(
    file: java.io.File,
    contentType: ContentType,
    disposition: String,
) {
    response.headers.append(HttpHeaders.ContentDisposition, disposition)
    response.headers.append(NOSNIFF_HEADER, "nosniff")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    applyMediaHeaders()
    // Accept-Ranges setzt PartialContent selbst. Ein eigener Header führte zu zwei Werten (ADR 0014, Umsetzung 3.1a).
    respond(
        io.ktor.server.http.content
            .LocalFileContent(file, contentType),
    )
}

/**
 * Zusätzliche Header für Medienantworten (ADR 0019): kein Referrer, nur gleiche Herkunft, keine Einbettung und keine
 * Ausführung. Die URL trägt eine Signatur, die nicht in fremde Seiten wandern soll.
 */
internal fun ApplicationCall.applyMediaHeaders() {
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
}

/**
 * Mehrere Bereiche in einem `Range`-Header (ADR 0019): Der Server antwortet nicht mit `multipart/byteranges`, sondern mit
 * `416`. Ein einzelner Bereich bleibt beim Plugin [PartialContent].
 */
internal fun ApplicationCall.rejectMultipleRanges(size: Long): Boolean {
    val range = request.headers[HttpHeaders.Range] ?: return false
    if (!range.contains(',')) return false
    applyMediaHeaders()
    response.headers.append("Content-Range", "bytes */$size")
    return true
}

internal fun ApplicationCall.applyApiHeaders() {
    response.headers.append(NOSNIFF_HEADER, "nosniff")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

/** Setzt das Route-Template fürs Protokoll. */
internal fun ApplicationCall.routeTemplate(template: String) {
    attributes.put(RouteTemplateKey, template)
}
