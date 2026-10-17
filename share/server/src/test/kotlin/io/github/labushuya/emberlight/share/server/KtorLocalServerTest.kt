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
    private val token = AccessToken.of("testtoken-0123456789")

    @Test
    fun healthNeedsTheToken() =
        testApplication {
            application { installEmberlightModule(token, routes = {}) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/health").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/health") { header(HttpHeaders.Authorization, "Bearer falsch-falsch-falsch") }.status,
            )
            val ok = client.get("/health") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("""{"status":"ok"}""", ok.bodyAsText())
        }

    @Test
    fun unknownRoutesAreProtectedToo() =
        testApplication {
            application { installEmberlightModule(token, routes = {}) }
            // Ohne Token kein Hinweis, ob ein Pfad existiert: auch unbekannte Pfade liefern 401.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/gibt-es-nicht").status)
            val withToken =
                client.get(
                    "/gibt-es-nicht",
                ) { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals(HttpStatusCode.NotFound, withToken.status)
        }

    @Test
    fun appRoutesAreReachableOnlyWithToken() =
        testApplication {
            application {
                installEmberlightModule(token) {
                    route("/app") { get("/ping") { call.respondText("pong") } }
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/app/ping").status)
            val ok = client.get("/app/ping") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }
            assertEquals("pong", ok.bodyAsText())
        }

    @Test
    fun realSocketServerStartsAnswersAndStops() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(port = 0, token = token))
            assertTrue(port > 0)
            assertEquals(ServerState.Running(port), server.state.value)

            val denied = httpStatus("http://127.0.0.1:$port/health", null)
            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, denied)
            val allowed = httpStatus("http://127.0.0.1:$port/health", "Bearer testtoken-0123456789")
            assertEquals(HttpURLConnection.HTTP_OK, allowed)

            server.stop()
            assertEquals(ServerState.Stopped, server.state.value)
            // Ein zweiter Stopp ist harmlos.
            server.stop()
        }
    }

    @Test
    fun secondStartWhileRunningIsRejected() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            server.start(ServerConfig(token = token))
            val failure = runCatching { server.start(ServerConfig(token = token)) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            server.stop()
        }
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
