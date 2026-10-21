package io.github.labushuya.emberlight.share.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Version der Server-Schnittstelle. Bleibt 1 bis zu einer inkompatiblen Änderung nach dem ersten Release mit Routen
 * (`v0.3.0`). Ändern sich Routen oder Antworten nur innerhalb der Version, bleibt die Zahl gleich (ADR 0014, Version).
 */
const val SERVER_API_VERSION = 1

/**
 * Ktor-Implementierung von [LocalServer] mit der CIO-Engine (ADR 0012, ADR 0013).
 *
 * Bindet an die Adresse der WLAN-Schnittstelle aus [ServerConfig.subnet], nie an `0.0.0.0`. Die Zugriffskette
 * (Protokoll, Host, Netz, Authorization, Parallelität) läuft vor jedem Plugin und jeder Route, auch vor `/health` und
 * unbekannten Pfaden. Die übergebenen [routes] liegen hinter dieser Kette.
 *
 * CIO begrenzt Kopfgröße und Verbindungsanzahl nicht. Das ist das Restrisiko aus ADR 0013, Entscheidung 1. Der Server
 * setzt deshalb einen Leerlauf-Timeout für Verbindungen und begrenzt Anfragen und Uploads selbst ([gates]). Der Körper
 * eines Uploads wird mit eigenen Grenzen gelesen ([TransferPolicy]).
 *
 * [requestLog] erhält Eingang und Abschluss jeder Anfrage. Der Eintrag nennt das Route-Template, nie den rohen Pfad, und
 * nie Header oder Tokens (ADR 0013, Logging).
 */
class KtorLocalServer(
    private val routes: Route.() -> Unit,
    private val authorizer: RequestAuthorizer = RequestAuthorizer.DenyAll,
    private val requestLog: (String) -> Unit = {},
    private val gates: ServerGates = ServerGates(),
    private val closure: SpaceClosure = SpaceClosure.Open,
    private val joinLimiter: JoinLimiter = JoinLimiter(),
    private val drainMaxBytes: Long = DRAIN_MAX_BYTES,
    private val signedMedia: SignedMediaAuthorizer? = null,
) : LocalServer {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private var running: EmbeddedServer<*, *>? = null

    override val state: StateFlow<ServerState> = mutableState.asStateFlow()

    override suspend fun start(config: ServerConfig): Int =
        lock.withLock {
            check(running == null) { "Server läuft bereits." }
            val expectedHost = AtomicReference<String?>(null)
            val env =
                ServerEnvironment(
                    authorizer = authorizer,
                    expectedHost = expectedHost::get,
                    senderAllowed = { remote -> config.subnet.contains(remote) },
                    gates = gates,
                    requestLog = requestLog,
                    debugLogging = config.debugLogging,
                    closure = closure,
                    joinLimiter = joinLimiter,
                    drainMaxBytes = drainMaxBytes,
                    signedMedia = signedMedia,
                )
            val server =
                embeddedServer(
                    CIO,
                    environment = applicationEnvironment { },
                    configure = {
                        connectionIdleTimeoutSeconds = IDLE_TIMEOUT_SECONDS
                        connector {
                            host = config.subnet.address
                            port = config.port
                        }
                    },
                ) {
                    installEmberlightModule(env, routes)
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
            // Erst nach dem Start steht der Port fest. Davor ist jede Anfrage mit 403 abgewiesen.
            expectedHost.set("${config.subnet.address}:$port")
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

    companion object {
        /** Leerlauf einer Verbindung, bis CIO sie schließt. Schließt stumme Verbindungen (ADR 0013, Messung). */
        const val IDLE_TIMEOUT_SECONDS = 15
        private const val STOP_TIMEOUT_MS = 2_000L
    }
}
