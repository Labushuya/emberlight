package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Echter Socket auf 127.0.0.1 (ADR 0012, ADR 0013). Die Anfragen laufen roh, damit Host-Header und mehrere
 * Authorization-Header wirklich gesendet werden.
 */
class KtorLocalServerTest {
    private val localSubnet = Ipv4Subnet("127.0.0.1", 8)
    private val hostPrincipal = Principal(ParticipantId.generate(), Role.HOST)
    private val token = "testtoken-0123456789"
    private val allowAll =
        TokenAuthorizer(mapOf(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()) to hostPrincipal))

    private fun hostHeader(port: Int) = "Host" to "127.0.0.1:$port"

    private fun bearer() = "Authorization" to "Bearer $token"

    @Test
    fun healthIsDeniedWithoutAuthorizationAndSaysWhy() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            val response = RawHttp.get(port, "/health", hostHeader(port))

            assertEquals(401, response.status)
            assertEquals("Bearer", response.headers["www-authenticate"])
            assertEquals("""{"error":"unauthorized"}""", response.body)
            server.stop()
        }
    }

    @Test
    fun unknownPathIsProtectedToo() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))

            assertEquals(401, RawHttp.get(port, "/gibt-es-nicht", hostHeader(port)).status)
            server.stop()
        }
    }

    @Test
    fun authorizedHealthReturnsStatusAndApiVersionOnly() {
        val server = KtorLocalServer(routes = {}, authorizer = allowAll)
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            val response = RawHttp.get(port, "/health", hostHeader(port), bearer())

            assertEquals(200, response.status)
            assertEquals("""{"status":"ok","apiVersion":$SERVER_API_VERSION}""", response.body)
            assertEquals("nosniff", response.headers["x-content-type-options"])
            assertEquals("no-store", response.headers["cache-control"])
            assertNull(response.headers["access-control-allow-origin"])
            server.stop()
        }
    }

    @Test
    fun foreignHostHeaderIsForbiddenEvenWithValidAuthorization() {
        val server = KtorLocalServer(routes = {}, authorizer = allowAll)
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response = RawHttp.get(port, "/health", "Host" to "evil.example:$port", bearer())

            assertEquals(403, response.status)
            assertEquals("""{"error":"forbidden_host"}""", response.body)
            server.stop()
        }
    }

    @Test
    fun hostWithoutPortIsForbidden() {
        val server = KtorLocalServer(routes = {}, authorizer = allowAll)
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))

            assertEquals(403, RawHttp.get(port, "/health", "Host" to "127.0.0.1", bearer()).status)
            server.stop()
        }
    }

    @Test
    fun multipleAuthorizationHeadersAreRejectedEvenIfTheFirstIsValid() {
        val server = KtorLocalServer(routes = {}, authorizer = allowAll)
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response =
                RawHttp.get(port, "/health", hostHeader(port), bearer(), "Authorization" to "Bearer falsch")

            assertEquals(401, response.status)
            server.stop()
        }
    }

    @Test
    fun startTwiceIsRejectedAndStopFreesTheState() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            assertTrue(runCatching { server.start(ServerConfig(subnet = localSubnet)) }.isFailure)
            assertEquals(ServerState.Running(port), server.state.value)

            server.stop()
            assertEquals(ServerState.Stopped, server.state.value)
        }
    }

    @Test
    fun requestLogNamesRouteTemplatesAndNeverTheToken() {
        val lines = mutableListOf<String>()
        val server = KtorLocalServer(routes = {}, authorizer = allowAll, requestLog = { lines += it })
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            RawHttp.get(port, "/health", hostHeader(port), bearer())
            server.stop()
        }

        assertTrue(lines.any { it.contains("route=/health") && it.contains("status=200") })
        assertFalse(lines.any { it.contains(token) })
    }

    @Test
    fun headerLineOverTheCioLimitIsRejectedWith400() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response = RawHttp.get(port, "/health", hostHeader(port), "X-Lang" to "a".repeat(8_201))

            assertEquals(400, response.status)
            server.stop()
        }
    }

    /**
     * Dokumentiertes Restrisiko (ADR 0013, Entscheidung 1): CIO begrenzt die Gesamtgröße der Header nicht. Der Test
     * hält das Verhalten fest. Es gibt keine Abwehr, sondern nur die Zugriffsprüfung danach.
     */
    @Test
    fun totalHeaderSizeIsNotLimitedByCioDocumentedRisk() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            val headers = (1..1_000).map { "X-Pad-$it" to "b".repeat(1_000) }

            val response = RawHttp.get(port, "/health", hostHeader(port), *headers.toTypedArray())

            assertEquals("Die Anfrage erreicht die Zugriffsprüfung, nicht den Header-Filter", 401, response.status)
            server.stop()
        }
    }

    /** Leerlauf-Timeout der Verbindung (ADR 0013). Ein stummer Client wird geschlossen. Das dauert echte 15 Sekunden. */
    @Test
    fun silentConnectionIsClosedByIdleTimeout() {
        val server = KtorLocalServer(routes = {})
        runBlocking {
            val port = server.start(ServerConfig(subnet = localSubnet))
            val started = System.nanoTime()

            val closedByServer =
                java.net.Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 30_000
                    runCatching { socket.getInputStream().read() }.getOrDefault(-1) == -1
                }
            val seconds = (System.nanoTime() - started) / 1_000_000_000L

            assertTrue("Server schließt nicht, Dauer $seconds s", closedByServer)
            assertTrue("Schluss erst nach $seconds s", seconds in 10..25)
            server.stop()
        }
    }
}
