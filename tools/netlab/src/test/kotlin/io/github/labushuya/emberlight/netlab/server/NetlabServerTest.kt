package io.github.labushuya.emberlight.netlab.server

import io.github.labushuya.emberlight.netlab.guest.GuestTracker
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prüft den Testserver über echte Sockets auf der Loopback-Adresse. */
class NetlabServerTest {
    private val token = "tokenTokenTokenToken1"
    private val tracker = GuestTracker()
    private lateinit var server: NetlabServer
    private lateinit var base: String

    @BeforeTest
    fun setUp() {
        server = NetlabServer(token = token, tracker = tracker, maxUploadBytes = 2L * 1024 * 1024)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://127.0.0.1:${server.boundAddress!!.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun open(
        path: String,
        method: String = "GET",
        tokenHeader: String? = token,
    ): HttpURLConnection {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (tokenHeader != null) connection.setRequestProperty(NetlabServer.TOKEN_HEADER, tokenHeader)
        return connection
    }

    private fun body(connection: HttpURLConnection): String =
        (if (connection.responseCode < 400) connection.inputStream else connection.errorStream)
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun pingWithTokenAnswersOk() {
        val connection = open("/ping")
        assertEquals(200, connection.responseCode)
        assertTrue(body(connection).contains("\"ok\":true"))
    }

    @Test
    fun pingWithoutOrWrongTokenIsUnauthorized() {
        assertEquals(401, open("/ping", tokenHeader = null).responseCode)
        assertEquals(401, open("/ping", tokenHeader = "falsch").responseCode)
    }

    @Test
    fun uploadReportsReceivedBytes() {
        val size = 1024 * 1024
        val connection = open("/upload", method = "POST")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(size)
        connection.outputStream.use { it.write(ByteArray(size)) }
        assertEquals(200, connection.responseCode)
        assertTrue(body(connection).contains("\"bytes\":$size"))
    }

    @Test
    fun uploadAboveLimitIsRejected() {
        val size = 3 * 1024 * 1024
        val connection = open("/upload", method = "POST")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(size)
        val status =
            try {
                connection.outputStream.use { it.write(ByteArray(size)) }
                connection.responseCode
            } catch (_: java.io.IOException) {
                // Der Server schließt nach der Ablehnung, bevor der Körper vollständig gesendet ist.
                413
            }
        assertEquals(413, status)
    }

    @Test
    fun browserPageNeedsNoToken() {
        val connection = open("/browser", tokenHeader = null)
        assertEquals(200, connection.responseCode)
        assertTrue(connection.contentType.startsWith("text/html"))
        assertTrue(body(connection).contains("Verbunden"))
    }

    @Test
    fun unknownPathIsNotFoundAndWrongMethodIsRejected() {
        assertEquals(404, open("/gibt-es-nicht").responseCode)
        assertEquals(405, open("/ping", method = "POST").responseCode)
    }

    @Test
    fun requestsAreCountedAsGuests() {
        open("/browser", tokenHeader = null).responseCode
        assertEquals(1, tracker.snapshot(activeWindowMs = 60_000).total)
    }

    @Test
    fun stopReleasesThePort() {
        val port = server.boundAddress!!.port
        server.stop()
        assertTrue(!server.isRunning)
        assertEquals(null, server.boundAddress)
        // Erneuter Start auf dem gleichen Port zeigt, dass der Socket frei ist.
        val again = NetlabServer(token = token, tracker = GuestTracker())
        again.start(InetAddress.getByName("127.0.0.1"), port)
        again.stop()
    }
}
