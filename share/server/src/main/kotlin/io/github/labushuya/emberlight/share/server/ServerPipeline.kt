package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.Principal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Teilnehmer der Anfrage. Die Zugriffsprüfung setzt ihn, Routen lesen ihn. */
internal val PrincipalKey = AttributeKey<Principal>("emberlight.principal")

/** Route-Template für das Protokoll, z. B. `/api/v1/media/{id}/content`. Ohne konkrete IDs. */
internal val RouteTemplateKey = AttributeKey<String>("emberlight.route-template")

private const val UNMATCHED_ROUTE = "(keine Route)"

/** Header, der Browser am MIME-Sniffing hindert (ADR 0013, Downloads). */
private const val NOSNIFF_HEADER = "X-Content-Type-Options"

/** Sekunden, nach denen ein 503 oder 429 zur Wiederholung auffordert (ADR 0014). */
internal const val RETRY_AFTER_SECONDS = "5"

/**
 * Alles, was die Zugriffskette braucht. [expectedHost] liefert den Host erst nach dem Start, deshalb
 * sind es Funktionen.
 */
internal class ServerEnvironment(
    val authorizer: RequestAuthorizer,
    val expectedHost: () -> String?,
    /** Prüft die Absenderadresse. Produktion: liegt im Subnetz der WLAN-Schnittstelle (ADR 0013). */
    val senderAllowed: (String) -> Boolean,
    val gates: ServerGates,
    val requestLog: (String) -> Unit,
    val debugLogging: Boolean,
)

/**
 * Installiert die Zugriffskette und die Routen. Die Kette läuft in der Phase `Setup`, also vor jedem Plugin und vor
 * jeder Route (ADR 0013, Reihenfolge der Prüfungen):
 *
 * 1. Protokoll (Eingang und Ausgang, Route-Template, ohne Header und Token)
 * 2. Host-Header muss exakt `<ip>:<port>` sein, sonst `403`
 * 3. Absender muss im Subnetz der WLAN-Schnittstelle liegen, sonst `403`
 * 4. `Authorization` genau einmal, gültig, sonst `401`
 * 5. Parallelität: Anfrage-Platz, sonst `503`
 *
 * Erst danach laufen die Plugins (Range) und die Routen. Keine CORS-Header.
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
            env.requestLog(
                "req method=${context.request.local.method.value} route=$template status=$status ms=$ms$origin",
            )
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val expected = env.expectedHost()
        if (expected == null || context.request.headers[HttpHeaders.Host] != expected) {
            context.respondError(HttpStatusCode.Forbidden, "forbidden_host")
            finish()
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!env.senderAllowed(context.request.origin.remoteAddress)) {
            context.respondError(HttpStatusCode.Forbidden, "forbidden_network")
            finish()
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val values =
            context.request.headers
                .getAll(HttpHeaders.Authorization)
                .orEmpty()
        // Mehrere Authorization-Header sind ein Fehler. Der erste Wert wird nicht genommen (ADR 0013).
        val principal = if (values.size == 1) env.authorizer.authorize(values.single()) else null
        if (principal == null) {
            context.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            context.respondError(HttpStatusCode.Unauthorized, "unauthorized")
            finish()
        } else {
            context.attributes.put(PrincipalKey, principal)
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        if (!env.gates.tryEnterRequest()) {
            context.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
            context.respondError(HttpStatusCode.ServiceUnavailable, "service_busy")
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

/** Datei-Antwort für den Medieninhalt, mit dem Typ aus der Erkennung und `nosniff`. Range übernimmt [PartialContent]. */
internal suspend fun ApplicationCall.respondContent(
    file: java.io.File,
    contentType: ContentType,
    disposition: String,
) {
    response.headers.append(HttpHeaders.ContentDisposition, disposition)
    response.headers.append(NOSNIFF_HEADER, "nosniff")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.AcceptRanges, "bytes")
    respond(LocalFileContent(file, contentType))
}

internal fun ApplicationCall.applyApiHeaders() {
    response.headers.append(NOSNIFF_HEADER, "nosniff")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

/** Setzt das Route-Template fürs Protokoll. */
internal fun ApplicationCall.routeTemplate(template: String) {
    attributes.put(RouteTemplateKey, template)
}

/** Teilnehmer der Anfrage. Nur nach erfolgreicher Zugriffsprüfung aufrufbar. */
internal fun ApplicationCall.principal(): Principal =
    checkNotNull(attributes.getOrNull(PrincipalKey)) {
        "Kein Teilnehmer gesetzt."
    }
