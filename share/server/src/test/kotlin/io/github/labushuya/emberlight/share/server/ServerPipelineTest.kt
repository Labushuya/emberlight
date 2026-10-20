package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Zugriffskette in der Phase `Setup` (ADR 0013, Reihenfolge der Prüfungen). Die Tests laufen über die Ktor-Testumgebung.
 * Der Absender ist ein Prädikat, damit die Tests nicht davon abhängen, welche Adresse die Testumgebung meldet. Das
 * Subnetz selbst prüft [ServerUnitTests] und der Socket-Test in [KtorLocalServerTest].
 */
class ServerPipelineTest {
    private val token = "pipeline-token-0123456789"
    private val principal = Principal(ParticipantId.generate(), Role.HOST)
    private val authorizer =
        TokenAuthorizer(mapOf(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()) to principal))
    private val host = "localhost"

    private fun env(
        authorizer: RequestAuthorizer = this.authorizer,
        senderAllowed: (String) -> Boolean = { true },
        gates: ServerGates = ServerGates(),
        log: MutableList<String> = mutableListOf(),
    ) = ServerEnvironment(
        authorizer = authorizer,
        expectedHost = { host },
        senderAllowed = senderAllowed,
        gates = gates,
        requestLog = { log += it },
        debugLogging = false,
        closure = SpaceClosure.Open,
    )

    private fun HttpRequestBuilder.withHost() {
        header(HttpHeaders.Host, host)
    }

    private fun HttpRequestBuilder.withToken() {
        withHost()
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /** Ein Plugin, das in den Phasen `Plugins` und `Fallback` antwortet, bevor eine Route an der Reihe ist. */
    private fun io.ktor.server.application.Application.greedyPlugins() {
        intercept(ApplicationCallPipeline.Plugins) {
            context.respondText("Plugin-Antwort", status = HttpStatusCode.OK)
            finish()
        }
        intercept(ApplicationCallPipeline.Fallback) {
            context.respondText("Fallback-Antwort", status = HttpStatusCode.OK)
            finish()
        }
    }

    @Test
    fun unauthorizedRequestGets401EvenIfAPluginWouldAnswer() =
        testApplication {
            application {
                greedyPlugins()
                installEmberlightModule(env(authorizer = RequestAuthorizer.DenyAll), routes = {})
            }

            val response = client.get("/beliebig") { withHost() }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertFalse(response.bodyAsText().contains("Plugin"))
        }

    @Test
    fun authorizedRequestReachesRoutesAfterTheCheck() =
        testApplication {
            application {
                installEmberlightModule(env(), routes = { route("/app") { get("/ping") { call.respondText("pong") } } })
            }

            val response = client.get("/app/ping") { withToken() }

            assertEquals("pong", response.bodyAsText())
        }

    @Test
    fun wrongHostIsForbiddenEvenWithValidAuthorization() =
        testApplication {
            application { installEmberlightModule(env(), routes = {}) }

            val response =
                client.get("/health") {
                    header(HttpHeaders.Host, "fremd.example")
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("forbidden_host"))
        }

    @Test
    fun senderRejectedByNetworkRuleIsForbidden() =
        testApplication {
            application { installEmberlightModule(env(senderAllowed = { false }), routes = {}) }

            val response = client.get("/health") { withToken() }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("forbidden_network"))
        }

    @Test
    fun requestGateFullAnswers503WithRetryAfter() =
        testApplication {
            val gates = ServerGates()
            repeat(ServerGates.REQUEST_LIMIT) { assertTrue(gates.tryEnterRequest()) }
            application { installEmberlightModule(env(gates = gates), routes = {}) }

            val response = client.get("/health") { withToken() }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            assertTrue(response.bodyAsText().contains("service_busy"))
        }

    @Test
    fun requestLogNeverContainsRawIdsOrTokens() =
        testApplication {
            val log = mutableListOf<String>()
            application {
                installEmberlightModule(
                    env(log = log),
                    routes = {
                        route("/api/v1") {
                            get("/media/{id}/content") {
                                call.attributes.put(RouteTemplateKey, "/api/v1/media/{id}/content")
                                call.respondText("x")
                            }
                        }
                    },
                )
            }

            client.get("/api/v1/media/GEHEIMERWERT_1234567890/content") { withToken() }

            assertTrue(log.isNotEmpty())
            assertTrue(log.any { it.contains("route=/api/v1/media/{id}/content") })
            assertFalse(log.any { it.contains("GEHEIMERWERT") || it.contains(token) })
        }
}
