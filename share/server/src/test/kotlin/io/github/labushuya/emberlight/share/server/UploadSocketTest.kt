package io.github.labushuya.emberlight.share.server

import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.StorageProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Pflicht-Tests für den Upload (ADR 0012, „Pflicht-Tests für den Upload“) über einen echten Socket. Jede Anfrage geht
 * roh an den Server, damit Content-Length, Chunked-Kodierung und Abbrüche real sind.
 */
@RunWith(RobolectricTestRunner::class)
class UploadSocketTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val token = "upload-token-0123456789"
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val spaceId = SpaceId.generate()
    private lateinit var vault: SpaceVault
    private lateinit var spacesDir: File
    private lateinit var server: KtorLocalServer
    private var port = 0

    /** Bild 2000 Byte, Video 6000 Byte. Kleine Grenzen, damit die Tests schnell bleiben. */
    private val limits =
        SpaceLimits(
            imageMaxBytes = 2_000,
            videoMaxBytes = 6_000,
            storageLimitBytes = 1_000_000,
            minDeviceReserveBytes = 0,
        )

    @Before
    fun start() {
        spacesDir = folder.newFolder("spaces")
        vault =
            SpaceVault.open(
                ApplicationProvider.getApplicationContext(),
                spacesDir,
                spaceId,
                limits,
                StorageProbe { Long.MAX_VALUE / 4 },
            )
        runBlocking { vault.registerParticipant(guest.participantId, "Gast", Role.GUEST, "0".repeat(64)) }
        server = newServer(TokenAuthorizer(mapOf(sha(token) to guest)))
        port = runBlocking { server.start(ServerConfig(subnet = Ipv4Subnet("127.0.0.1", 8))) }
    }

    @After
    fun stop() {
        runBlocking { server.stop() }
        vault.close()
    }

    private fun newServer(authorizer: RequestAuthorizer): KtorLocalServer {
        val gates = ServerGates()
        return KtorLocalServer(
            routes = spaceRoutes(vault, "Test", gates, UploadTuning(pollMillis = 1)),
            authorizer = authorizer,
            gates = gates,
        )
    }

    private fun sha(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun headers(vararg extra: Pair<String, String>): List<Pair<String, String>> =
        listOf(
            "Host" to "127.0.0.1:$port",
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/octet-stream",
            "X-Emberlight-Filename" to "bild.jpg",
        ) + extra

    private fun storedFiles(): Int = File(spacesDir, spaceId.value).listFiles()?.size ?: 0

    private fun chunked(
        data: ByteArray,
        chunk: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < data.size) {
            val count = minOf(chunk, data.size - index)
            out.write("${count.toString(16)}\r\n".toByteArray())
            out.write(data, index, count)
            out.write("\r\n".toByteArray())
            index += count
        }
        out.write("0\r\n\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun denyAllRejectsUploadWithUnauthorizedAndNoFile() {
        runBlocking { server.stop() }
        server = newServer(RequestAuthorizer.DenyAll)
        port = runBlocking { server.start(ServerConfig(subnet = Ipv4Subnet("127.0.0.1", 8))) }

        val response =
            answered(
                RawHttp.sendAnsweringEarly(
                    port,
                    RawHttp.request("POST", "/api/v1/media", headers(), MediaFixtures.jpeg(300)),
                ),
            )

        assertEquals(401, response.status)
        assertEquals(0, storedFiles())
    }

    @Test
    fun chunkedUploadWithoutContentLengthIsCutAtTheLimit() {
        val body = chunked(MediaFixtures.jpeg(3_000), chunk = 500)

        val response =
            answered(
                RawHttp.sendAnsweringEarly(
                    port,
                    RawHttp.request(
                        "POST",
                        "/api/v1/media",
                        headers("Transfer-Encoding" to "chunked"),
                        body,
                    ),
                ),
            )

        assertEquals(413, response.status)
        assertTrue(response.body.contains("payload_too_large"))
        assertEquals(0, storedFiles())
    }

    /**
     * Großer Körper ohne erlaubte Signatur: `415` kommt nach dem Kopf, nicht nach dem Körper. Die Antwort trägt
     * `Connection: close`, danach ist die Verbindung zu, und eine neue Anfrage funktioniert.
     */
    @Test
    fun unsignedLargeBodyIsRefusedEarlyWithConnectionCloseAndServerStaysUsable() {
        val bodySize = 256L * 1024 * 1024
        val head =
            RawHttp.request(
                "POST",
                "/api/v1/media",
                headers("Content-Length" to bodySize.toString()),
            )

        val started = System.nanoTime()
        val (response, sent) = RawHttp.sendBodyAnsweringEarly(port, head, bodySize)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("Antwort ausgeblieben nach $elapsedMs ms", 415, response?.status)
        assertEquals("close", response?.headers?.get("connection"))
        assertTrue("Körper vollständig gesendet: $sent von $bodySize", sent < bodySize)
        assertTrue("Dauer $elapsedMs ms", elapsedMs < 5_000)
        assertEquals(0, storedFiles())

        val health =
            RawHttp.get(port, "/health", "Host" to "127.0.0.1:$port", "Authorization" to "Bearer $token")
        assertEquals(200, health.status)
    }

    /**
     * Content-Length kleiner als der gesendete Körper: Der Server liest genau Content-Length Bytes (HTTP-Rahmung, CIO).
     * Die Datei hat deshalb die angekündigte Länge und nie mehr. Die Grenze in Bytes greift zusätzlich beim Zählen.
     */
    @Test
    fun contentLengthSmallerThanTheBodyStoresAtMostTheDeclaredBytes() {
        val response =
            answered(
                RawHttp.sendAnsweringEarly(
                    port,
                    RawHttp.request(
                        "POST",
                        "/api/v1/media",
                        headers("Content-Length" to "100"),
                        MediaFixtures.jpeg(3_000),
                    ),
                ),
            )

        assertEquals(201, response.status)
        assertTrue("Antwort: ${response.body}", response.body.contains("\"size\":100"))
    }

    /**
     * Angekündigte Länge über der Videogrenze: `413` vor dem Speichern. Der Körper ist vollständig vorhanden, die Antwort
     * kommt trotzdem nebenläufig zum Schreiben an (siehe [RawHttp.sendAnsweringEarly]). Ohne das ginge die Antwort verloren,
     * sobald der Server schließt, bevor der Client sie liest.
     */
    @Test
    fun declaredLengthAboveTheVideoLimitIsRefusedBeforeReading() {
        val body = MediaFixtures.mp4() + ByteArray(9_000 - MediaFixtures.mp4().size)
        val response =
            answered(
                RawHttp.sendAnsweringEarly(
                    port,
                    RawHttp.request("POST", "/api/v1/media", headers("Content-Length" to "9000"), body),
                ),
            )

        assertEquals(413, response.status)
        assertTrue(response.body.contains("payload_too_large"))
        assertEquals(0, storedFiles())
    }

    @Test
    fun connectionAbortMidUploadRemovesThePartialFile() {
        val partial =
            RawHttp.request(
                "POST",
                "/api/v1/media",
                headers("Content-Length" to "5000"),
                MediaFixtures.jpeg(1_000),
            )
        RawHttp.sendAndAbort(port, partial)

        val deadline = System.currentTimeMillis() + 5_000
        while (storedFiles() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals("Teildatei nach Abbruch geblieben", 0, storedFiles())
    }

    @Test
    fun duplicateAuthorizationOnUploadIsUnauthorized() {
        val response =
            answered(
                RawHttp.sendAnsweringEarly(
                    port,
                    RawHttp.request(
                        "POST",
                        "/api/v1/media",
                        headers("Authorization" to "Bearer $token"),
                        MediaFixtures.jpeg(300),
                    ),
                ),
            )

        assertEquals(401, response.status)
        assertEquals(0, storedFiles())
    }

    /** Eine Antwort ist Pflicht. Fehlt sie, schlägt der Test mit klarer Meldung fehl statt mit einem Nullzeiger. */
    private fun answered(response: RawResponse?): RawResponse =
        checkNotNull(response) { "Keine Antwort vom Server erhalten." }
}
