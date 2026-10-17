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
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ktor-Implementierung von [LocalServer] mit der CIO-Engine (ADR 0012).
 *
 * Bindet an `0.0.0.0`. Jede Route, auch `/health`, verlangt `Authorization: Bearer <token>`. Die übergebenen
 * [routes] liegen hinter dieser Prüfung.
 */
class KtorLocalServer(
    private val routes: Route.() -> Unit,
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
                    installEmberlightModule(config.token, routes)
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
 * Gemeinsame Konfiguration für Engine und Tests: Range-Antworten, Token-Prüfung auf allen Routen, `/health`.
 * Die Token-Prüfung hängt am Root der Routing-Kette, damit kein Pfad daran vorbeikommt.
 */
internal fun Application.installEmberlightModule(
    token: AccessToken,
    routes: Route.() -> Unit,
) {
    install(PartialContent)
    routing {
        intercept(ApplicationCallPipeline.Call) {
            val appCall = context
            if (!token.matches(bearerToken(appCall.request.headers[HttpHeaders.Authorization]))) {
                appCall.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                appCall.respondText("", status = HttpStatusCode.Unauthorized)
                finish()
            }
        }
        get("/health") {
            call.respondText(HEALTH_BODY, ContentType.Application.Json)
        }
        routes()
    }
}

/** Liest den Token aus `Authorization: Bearer <token>`. Das Schema zählt nicht zwischen Groß- und Kleinschreibung. */
internal fun bearerToken(header: String?): String? {
    if (header == null || !header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
    return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
}

private const val BEARER_PREFIX = "Bearer "
private const val HEALTH_BODY = """{"status":"ok"}"""
