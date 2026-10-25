package io.github.labushuya.emberlight.netlab.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpWireTest {
    private fun read(text: String): HttpRequest? =
        HttpWire.readHead(ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII)))

    @Test
    fun parsesMethodPathQueryAndHeaders() {
        val request = read("GET /ping?x=1&name=a%20b HTTP/1.1\r\nHost: test\r\nX-Netlab-Token: abc\r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/ping", request.path)
        assertEquals(mapOf("x" to "1", "name" to "a b"), request.query)
        assertEquals("abc", request.headers["x-netlab-token"])
    }

    @Test
    fun emptyStreamGivesNull() {
        assertNull(read(""))
    }

    @Test
    fun brokenRequestLineIsBadRequest() {
        val error = assertFailsWith<HttpProtocolException> { read("GARBAGE\r\n\r\n") }
        assertEquals(400, error.status)
    }

    @Test
    fun unknownProtocolIsBadRequest() {
        val error = assertFailsWith<HttpProtocolException> { read("GET / SPDY/3\r\n\r\n") }
        assertEquals(400, error.status)
    }

    @Test
    fun duplicateHeaderIsRejected() {
        val error =
            assertFailsWith<HttpProtocolException> {
                read("GET / HTTP/1.1\r\nA: 1\r\na: 2\r\n\r\n")
            }
        assertEquals(400, error.status)
    }

    @Test
    fun oversizedHeadIsRejectedWith431() {
        val longHeader = "X-Long: " + "a".repeat(HttpWire.MAX_HEAD_BYTES) + "\r\n\r\n"
        val error = assertFailsWith<HttpProtocolException> { read("GET / HTTP/1.1\r\n$longHeader") }
        assertEquals(431, error.status)
    }

    @Test
    fun contentLengthRules() {
        val withLength = HttpRequest("POST", "/upload", emptyMap(), mapOf("content-length" to "10"))
        assertEquals(10L, HttpWire.contentLength(withLength, maxBytes = 100))

        val missing = HttpRequest("POST", "/upload", emptyMap(), emptyMap())
        assertEquals(411, assertFailsWith<HttpProtocolException> { HttpWire.contentLength(missing, 100) }.status)

        val chunked = HttpRequest("POST", "/upload", emptyMap(), mapOf("transfer-encoding" to "chunked"))
        assertEquals(411, assertFailsWith<HttpProtocolException> { HttpWire.contentLength(chunked, 100) }.status)

        val tooBig = HttpRequest("POST", "/upload", emptyMap(), mapOf("content-length" to "101"))
        assertEquals(413, assertFailsWith<HttpProtocolException> { HttpWire.contentLength(tooBig, 100) }.status)

        val negative = HttpRequest("POST", "/upload", emptyMap(), mapOf("content-length" to "-1"))
        assertEquals(400, assertFailsWith<HttpProtocolException> { HttpWire.contentLength(negative, 100) }.status)
    }

    @Test
    fun discardBodyCountsBytesAndDetectsShortBody() {
        val body = ByteArray(200_000) { it.toByte() }
        assertEquals(200_000L, HttpWire.discardBody(ByteArrayInputStream(body), 200_000L))

        val error =
            assertFailsWith<HttpProtocolException> {
                HttpWire.discardBody(ByteArrayInputStream(ByteArray(10)), 20L)
            }
        assertEquals(400, error.status)
    }

    @Test
    fun writesStatusHeadersAndBody() {
        val out = ByteArrayOutputStream()
        HttpWire.writeResponse(out, 200, "text/plain", "hallo".toByteArray(), mapOf("X-Test" to "1"))
        val text = out.toString(Charsets.US_ASCII.name())
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 5\r\n"))
        assertTrue(text.contains("X-Test: 1\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhallo"))
    }
}
