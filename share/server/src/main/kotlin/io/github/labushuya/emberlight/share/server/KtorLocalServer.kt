package io.github.labushuya.emberlight.share.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Version der Server-Schnittstelle. Erhöhen, wenn sich Routen oder Antworten für Gäste-Geräte ändern. */
const val SERVER_API_VERSION = 1

/**
 * Ktor-Implementierung von [LocalServer] mit der CIO-Engine (ADR 0012).
 *
 * Bindet an `0.0.0.0`. Jede Anfrage, auch `/health` und unbekannte Pfade, durchläuft [authorizer]. Die übergebenen
 * [routes] liegen hinter dieser Prüfung. [requestLog] erhält Eingang und Abschluss jeder Anfrage (Methode, Pfad,
 * Status, Dauer). Der Aufrufer darf Pfade nur ohne sensible Teile protokollieren.
 */
class KtorLocalServer(
    private val routes: Route.() -> Unit,
    private val authorizer: RequestAuthorizer = RequestAuthorizer.DenyAll,
    private val requestLog: (String) -> Unit = {},
) : LocalServer {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private var running: EmbeddedServer<*, *>? = null

    override val state: StateFlow<ServerState> = mutableState.asStateFlow()

    override suspend fun start(config: ServerConfig): Int =
        lock.withLock {
            check(running == null) { "Server läuft bereits." }
            val server =
                embeddedServer(CIO, port = config.port, host = BIND_HOST) {
                    installEmberlightModule(authorizer, requestLog, routes)
                }
            val port =
                try {
                    server.startSuspend(wait = false)
                    server.engine
                        .resolvedConnectors()
                        .first { it.type == ConnectorType.HTTP }
                        .port
                } catch (failure: Throwable) {
                    server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
                    throw failure
                }
            running = server
            mutableState.value = ServerState.Running(port)
            port
        }

    override suspend fun stop() {
        lock.withLock {
            val server = running ?: return
            server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
            running = null
            mutableState.value = ServerState.Stopped
        }
    }

    private companion object {
        const val BIND_HOST = "0.0.0.0"
        const val STOP_TIMEOUT_MS = 2_000L
    }
}

/**
 * Gemeinsame Konfiguration für Engine und Tests: Range-Antworten, Zugriffsprüfung am Root der Routing-Kette und
 * `/health`. Die Prüfung hängt am Root, damit kein Pfad daran vorbeikommt.
 */
internal fun Application.installEmberlightModule(
    authorizer: RequestAuthorizer,
    requestLog: (String) -> Unit = {},
    routes: Route.() -> Unit,
) {
    install(PartialContent)
    routing {
        // Protokoll: Eingang und fertige Antwort. Liegt vor der Prüfung, damit auch abgewiesene Anfragen auftauchen.
        intercept(ApplicationCallPipeline.Call) {
            val startedAt = System.nanoTime()
            val path =
                context.request.local.uri
                    .substringBefore('?')
            requestLog("req-in method=${context.request.local.method.value} path=$path")
            try {
                proceed()
            } finally {
                val status = context.response.status()?.value ?: 0
                val ms = (System.nanoTime() - startedAt) / 1_000_000L
                requestLog("req-out path=$path status=$status ms=$ms")
            }
        }
        intercept(ApplicationCallPipeline.Call) {
            if (!authorizer.isAuthorized(context.request.headers[HttpHeaders.Authorization])) {
                context.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                context.respond(HttpStatusCode.Unauthorized)
                finish()
            }
        }
        get("/health") {
            call.respondText(HEALTH_BODY, ContentType.Application.Json)
        }
        routes()
    }
}

private val HEALTH_BODY = """{"status":"ok","apiVersion":$SERVER_API_VERSION}"""
