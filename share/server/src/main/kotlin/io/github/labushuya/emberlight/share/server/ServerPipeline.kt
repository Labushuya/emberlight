package io.github.labushuya.emberlight.share.server

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

/** Teilnehmer der Anfrage. Die Zugriffsprüfung setzt ihn, Routen lesen ihn. */
internal val PrincipalKey = AttributeKey<Principal>("emberlight.principal")

/** Route-Template für das Protokoll, z. B. `/api/v1/media/{id}/content`. Ohne konkrete IDs. */
internal val RouteTemplateKey = AttributeKey<String>("emberlight.route-template")

/** Gesetzt von der Beitritts-Route, wenn die Einladung falsch war. Zählt als Fehlversuch (ADR 0018). */
internal val JoinFailedKey = AttributeKey<Boolean>("emberlight.join-failed")

private const val UNMATCHED_ROUTE = "(keine Route)"

/** Pfad des Beitritts. Er braucht keine Zugriffsprüfung, aber eine Drosselung (ADR 0018). */
internal const val JOIN_PATH = "/api/v1/join"

/** Header, der Browser am MIME-Sniffing hindert (ADR 0013, Downloads). */
private const val NOSNIFF_HEADER = "X-Content-Type-Options"

/** Sekunden, nach denen ein 503 oder 429 zur Wiederholung auffordert (ADR 0014). */
internal const val RETRY_AFTER_SECONDS = "5"

/** Höchstens so viele Körper-Bytes werden nach einer frühen Ablehnung gelesen und verworfen (ADR 0014, lingering close). */
internal const val DRAIN_MAX_BYTES = 1L shl 20

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
        if (context.isJoinRequest()) {
            val wait = env.joinLimiter.retryAfterSeconds(context.request.origin.remoteAddress)
            if (wait != null) {
                context.response.headers.append(HttpHeaders.RetryAfter, wait.toString())
                context.refuseEarly(HttpStatusCode.TooManyRequests, "rate_limited")
                finish()
            }
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!context.isPublicSite() && !context.isJoinRequest()) {
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
    intercept(ApplicationCallPipeline.Setup) {
        // Nach dem Ablauf antwortet jede Anfrage mit 410, auch ohne gültigen Schlüssel. Der Betrieb stoppt danach (ADR 0017).
        if (!context.isPublicSite() && env.closure.isClosed()) {
            env.closure.onClosedRequest()
            context.refuseEarly(HttpStatusCode.Gone, "space_closed")
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

/** Beitritts-Anfrage? Nur `POST /api/v1/join`. */
internal fun ApplicationCall.isJoinRequest(): Boolean =
    request.local.method == HttpMethod.Post && request.path() == JOIN_PATH

/** Gäste-Seite und ihre Dateien. Ohne Zugriffsprüfung, weil die Dateien keine Daten des Space enthalten (ADR 0018). */
internal fun ApplicationCall.isPublicSite(): Boolean =
    request.local.method == HttpMethod.Get && GuestSite.isPublicPath(request.path())

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

/** Einheitliches Fehlerformat `{"error":"<code>"}` plus optionale Felder (ADR 0014). */
internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    extra: Map<String, JsonElement> = emptyMap(),
) {
    respondJson(status, JsonObject(mapOf("error" to JsonPrimitive(code)) + extra))
}

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
    try {
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
            body.discard(DRAIN_MAX_BYTES)
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
    // Accept-Ranges setzt PartialContent selbst. Ein eigener Header führte zu zwei Werten (ADR 0014, Umsetzung 3.1a).
    respond(
        io.ktor.server.http.content
            .LocalFileContent(file, contentType),
    )
}

internal fun ApplicationCall.applyApiHeaders() {
    response.headers.append(NOSNIFF_HEADER, "nosniff")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

/** Setzt das Route-Template fürs Protokoll. */
internal fun ApplicationCall.routeTemplate(template: String) {
    attributes.put(RouteTemplateKey, template)
}
