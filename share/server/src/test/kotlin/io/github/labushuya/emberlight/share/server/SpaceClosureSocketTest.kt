package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ablauf über den echten Socket (ADR 0017): Ein abgelaufener Space antwortet mit `410 space_closed`, auch ohne gültigen
 * Schlüssel, aber erst nach Host- und Netzprüfung.
 */
class SpaceClosureSocketTest {
    private val localSubnet = Ipv4Subnet("127.0.0.1", 8)

    @Test
    fun aClosedSpaceAnswersGoneWithoutAnyKeyAndReportsTheRequest() =
        runBlocking {
            val closure = FixedClosure(closed = true)
            val server = KtorLocalServer(routes = {}, authorizer = allowAll, closure = closure)
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response = RawHttp.get(port, "/health", "Host" to "127.0.0.1:$port", "Authorization" to "Bearer abc")

            assertEquals(410, response.status)
            assertEquals("""{"error":"space_closed"}""", response.body)
            assertEquals(1, closure.reported.get())
            server.stop()
        }

    @Test
    fun aForeignHostStillGetsForbiddenOnAClosedSpace() =
        runBlocking {
            val closure = FixedClosure(closed = true)
            val server = KtorLocalServer(routes = {}, closure = closure)
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response = RawHttp.get(port, "/health", "Host" to "evil.example:$port")

            assertEquals(403, response.status)
            assertEquals(0, closure.reported.get())
            server.stop()
        }

    @Test
    fun anOpenSpaceKeepsTheUsualDenialWithoutAuthorization() =
        runBlocking {
            val closure = FixedClosure(closed = false)
            val server = KtorLocalServer(routes = {}, closure = closure)
            val port = server.start(ServerConfig(subnet = localSubnet))

            val response = RawHttp.get(port, "/health", "Host" to "127.0.0.1:$port")

            assertEquals(401, response.status)
            assertEquals(0, closure.reported.get())
            server.stop()
        }

    // Nach 3.4 kommt die Zugriffsprüfung vor dem Ablauf (ADR 0018, Reihenfolge). Ohne gültigen Schlüssel also 401.
    private val allowAll = RequestAuthorizer { Principal(ParticipantId.generate(), Role.GUEST) }

    private class FixedClosure(
        private val closed: Boolean,
    ) : SpaceClosure {
        val reported = AtomicInteger()

        override fun isClosed(): Boolean = closed

        override fun onClosedRequest() {
            reported.incrementAndGet()
        }
    }
}
