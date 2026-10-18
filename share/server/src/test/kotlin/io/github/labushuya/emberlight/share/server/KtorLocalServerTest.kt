package io.github.labushuya.emberlight.share.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class KtorLocalServerTest {
    private val allowAll = RequestAuthorizer { header -> header == "Bearer testtoken-0123456789" }

    @Test
    fun healthIsDeniedWithoutAuthorization() =
        testApplication {
            application { installEmberlightModule(RequestAuthorizer.DenyAll, routes = {}) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/health").status)
        }

    @Test
    fun healthReturnsStatusAndApiVersionOnly() =
        testApplication {
            application { installEmberlightModule(allowAll, routes = {}) }
            val response = client.get("/health") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok","apiVersion":$SERVER_API_VERSION}""", response.bodyAsText())
        }

    @Test
    fun unknownRoutesAreProtectedToo() =
        testApplication {
            application { installEmberlightModule(RequestAuthorizer.DenyAll, routes = {}) }
            // Ohne Berechtigung kein Hinweis, ob ein Pfad existiert: auch unbekannte Pfade liefern 401.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/gibt-es-nicht").status)
        }

    @Test
    fun unknownRoutesAreNotFoundWhenAuthorized() =
        testApplication {
            application { installEmberlightModule(allowAll, routes = {}) }
            val response =
                client.get("/gibt-es-nicht") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun appRoutesAreReachableOnlyWhenAuthorized() =
        testApplication {
            application {
                installEmberlightModule(allowAll) {
                    route("/app") { get("/ping") { call.respondText("pong") } }
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/app/ping").status)
            val ok = client.get("/app/ping") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals("pong", ok.bodyAsText())
        }

    @Test
    fun requestLogSeesEntryAndExit() =
        testApplication {
            val lines = mutableListOf<String>()
            application {
                installEmberlightModule(
                    RequestAuthorizer.DenyAll,
                    requestLog = { lines += it },
                    routes = {},
                )
            }
            client.get("/health")
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("req-in method=GET path=/health"))
            assertTrue(lines[1].startsWith("req-out path=/health status=401"))
        }

    @Test
    fun realSocketServerStartsAnswersAndStops() {
        val server = KtorLocalServer(routes = {}, authorizer = allowAll)
        runBlocking {
            val port = server.start(ServerConfig(port = 0))
            assertTrue(port > 0)
            assertEquals(ServerState.Running(port), server.state.value)

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, httpStatus("http://127.0.0.1:$port/health", null))
            val allowed = httpStatus("http://127.0.0.1:$port/health", "Bearer testtoken-0123456789")
            assertEquals(HttpURLConnection.HTTP_OK, allowed)

            server.stop()
            assertEquals(ServerState.Stopped, server.state.value)
            // Ein zweiter Stopp ist harmlos.
            server.stop()
        }
    }

    @Test
    fun defaultAuthorizerDeniesEverythingOnSocket() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig())
            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, httpStatus("http://127.0.0.1:$port/health", null))
            assertEquals(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                httpStatus("http://127.0.0.1:$port/health", "Bearer irgendetwas-anderes"),
            )
            server.stop()
        }
    }

    @Test
    fun secondStartWhileRunningIsRejected() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            server.start(ServerConfig())
            val failure = runCatching { server.start(ServerConfig()) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            server.stop()
        }
    }

    @Test
    fun portOutOfRangeIsRejected() {
        assertTrue(runCatching { ServerConfig(port = 65536) }.isFailure)
        assertTrue(runCatching { ServerConfig(port = -1) }.isFailure)
        assertEquals(0, ServerConfig().port)
    }

    private fun httpStatus(
        url: String,
        authorization: String?,
    ): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (authorization != null) connection.setRequestProperty(HttpHeaders.Authorization, authorization)
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
