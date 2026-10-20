package io.github.labushuya.emberlight.share.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.Credential
import io.github.labushuya.emberlight.share.space.participants.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

/**
 * Frühe Ablehnung über den echten Socket (ADR 0014, lingering close, ADR 0018). Der Client schickt einen großen Körper
 * weiter, während der Server schon antwortet. Die Statuszeile muss ankommen. Ein Entfernen bricht den laufenden Upload ab.
 */
@RunWith(RobolectricTestRunner::class)
class LingeringCloseSocketTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var access: SpaceAccess
    private lateinit var server: KtorLocalServer
    private var port = 0
    private val spaceId = SpaceId.generate()
    private val guestToken = "tok-guest-lingering-0123456789"
    private val guestId = ParticipantId.generate()

    @Before
    fun start() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        val vault = SpaceVault.open(database, spacesDir, spaceId, LIMITS, StorageProbe { Long.MAX_VALUE / 4 })
        runBlocking { vault.registerParticipant(guestId, "Gast", Role.GUEST, Secrets.hashHex(guestToken)) }
        access =
            testAccess(
                vault,
                spaceId,
                listOf(Credential(Secrets.hashHex(guestToken), guestId, Role.GUEST)),
            )
        val gates = ServerGates()
        server =
            KtorLocalServer(
                routes = spaceRoutes(access, gates, UploadTuning(pollMillis = 1)),
                authorizer = access.live,
                gates = gates,
            )
        port = runBlocking { server.start(ServerConfig(subnet = Ipv4Subnet("127.0.0.1", 8))) }
    }

    @After
    fun stop() {
        runBlocking { server.stop() }
        database.close()
    }

    private fun head(
        path: String,
        length: Long,
        vararg extra: Pair<String, String>,
        serverPort: Int = port,
    ): ByteArray =
        RawHttp.request(
            "POST",
            path,
            listOf(
                "Host" to "127.0.0.1:$serverPort",
                "Content-Type" to "application/octet-stream",
                "Content-Length" to length.toString(),
                "X-Emberlight-Filename" to "bild.jpg",
            ) + extra,
        )

    @Test
    fun unauthorizedLargeBodyGetsItsStatusAndTheConnectionCloses() {
        val total = SIXTY_FOUR_MIB
        val (response, sent) = RawHttp.sendBodyAnsweringEarly(port, head("/api/v1/media", total), total)

        assertTrue("Keine Antwort, der Client hat die Statuszeile nicht erhalten.", response != null)
        assertEquals(401, response!!.status)
        assertEquals("close", response.headers["connection"])
        assertTrue("Der Client hätte den ganzen Körper senden sollen, nicht abbrechen: $sent", sent <= total)
    }

    @Test
    fun declaredLengthAboveTheLimitGetsA413WithTheStatusReadable() {
        val total = SIXTY_FOUR_MIB
        // Gültige JPEG-Kennung am Anfang, damit das Format erkannt wird und die Größe zählt (sonst 415).
        val request =
            head("/api/v1/media", total, "Authorization" to "Bearer $guestToken") + MediaFixtures.jpeg(total.toInt())

        val response = RawHttp.sendAnsweringEarly(port, request)

        assertTrue("Keine Antwort, die Statuszeile fehlt.", response != null)
        assertEquals(413, response!!.status)
        assertEquals("payload_too_large", response.body.substringAfter("\"error\":\"").substringBefore("\""))
    }

    @Test
    fun removingAGuestAbortsItsRunningUploadAndTheServerStaysUsable() {
        val content = MediaFixtures.jpeg(PARTIAL_TOTAL)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            out.write(
                head(
                    "/api/v1/media",
                    content.size.toLong(),
                    "Authorization" to "Bearer $guestToken",
                ),
            )
            out.write(content, 0, PARTIAL_SENT)
            out.flush()
            awaitTempFile()

            runBlocking { access.removeGuest(guestId, deleteUploads = false) }

            val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            assertTrue("Erwartet 401, war: $status", status?.contains(" 401 ") == true)
        }
        assertEquals(0, storedFileCount())
        val health = RawHttp.get(port, "/health", "Host" to "127.0.0.1:$port", "Authorization" to "Bearer abc")
        assertEquals(401, health.status)
    }

    /**
     * Gegenprobe (ADR 0014, Umsetzung 3.4): Dieselbe Ablehnung ohne Verwerfen des Körpers. Auf dem Windows-Loopback geht die
     * Statuszeile dann verloren. Der Test hält das fest, damit niemand das Verwerfen unbemerkt entfernt.
     */
    @Test
    fun withoutDrainTheRefusalIsLostOnThisPlatform() {
        val gates = ServerGates()
        val bare =
            KtorLocalServer(
                routes = spaceRoutes(access, gates, UploadTuning(pollMillis = 1)),
                authorizer = access.live,
                gates = gates,
                drainMaxBytes = 0,
            )
        val barePort = runBlocking { bare.start(ServerConfig(subnet = Ipv4Subnet("127.0.0.1", 8))) }
        try {
            val total = SIXTY_FOUR_MIB
            val request =
                head("/api/v1/media", total, "Authorization" to "Bearer $guestToken", serverPort = barePort) +
                    MediaFixtures.jpeg(total.toInt())

            assertNull(RawHttp.sendAnsweringEarly(barePort, request))
        } finally {
            runBlocking { bare.stop() }
        }
    }

    /** Wartet, bis der Upload seine Teildatei angelegt hat. Kurze Abstände, mit Obergrenze (Socket-Test). */
    private fun awaitTempFile() {
        repeat(MAX_POLLS) {
            if (storedFileCount() > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Die Teildatei des Uploads ist nicht erschienen.")
    }

    private fun storedFileCount(): Int = File(spacesDir, spaceId.value).listFiles()?.size ?: 0

    private companion object {
        const val SIXTY_FOUR_MIB = 64L * 1024 * 1024
        const val PARTIAL_TOTAL = 20_000
        const val PARTIAL_SENT = 4_000
        const val TIMEOUT_MS = 15_000
        const val MAX_POLLS = 2_000
        const val POLL_MILLIS = 5L

        val LIMITS =
            SpaceLimits(
                imageMaxBytes = 2_000_000,
                videoMaxBytes = 6_000_000,
                storageLimitBytes = 1_000_000_000,
                minDeviceReserveBytes = 0,
            )
    }
}
