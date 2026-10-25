package io.github.labushuya.emberlight.netlab.server

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** Eine eingelesene HTTP/1.1-Anfrage. Schlüssel der Header sind klein geschrieben. */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
)

/** Fehler beim Lesen einer Anfrage. [status] ist der HTTP-Status für die Antwort. */
class HttpProtocolException(
    val status: Int,
    message: String,
) : Exception(message)

/**
 * Minimales HTTP/1.1 für den Testserver (Paket 4.0). Bewusst klein: Anfragezeile, Header bis zu einer Obergrenze,
 * Körper nur über Content-Length. Keine Chunked-Kodierung, keine Keep-Alive-Verbindungen.
 *
 * Die Funktionen arbeiten auf Streams und sind ohne Socket testbar.
 */
object HttpWire {
    /** Obergrenze für Anfragezeile und Header zusammen. Schützt vor überlangen Kopfzeilen. */
    const val MAX_HEAD_BYTES = 8 * 1024

    /** Liest Anfragezeile und Header bis zur Leerzeile. Liefert `null`, wenn die Verbindung ohne Daten endet. */
    fun readHead(input: InputStream): HttpRequest? {
        val lines = readHeadLines(input) ?: return null
        val requestLine = lines.firstOrNull() ?: throw HttpProtocolException(400, "Leere Anfrage")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw HttpProtocolException(400, "Ungültige Anfragezeile")
        }
        val method = parts[0]
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpProtocolException(400, "Ungültiges Ziel")
        val (path, rawQuery) = target.split('?', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index <= 0) throw HttpProtocolException(400, "Ungültiger Header")
            val name = line.substring(0, index).trim().lowercase()
            if (headers.containsKey(name)) throw HttpProtocolException(400, "Doppelter Header")
            headers[name] = line.substring(index + 1).trim()
        }
        return HttpRequest(method, path, parseQuery(rawQuery), headers)
    }

    /** Liefert die Länge des Körpers aus Content-Length. Fehlt die Angabe, ist das 411. Über [maxBytes] ist 413. */
    fun contentLength(
        request: HttpRequest,
        maxBytes: Long,
    ): Long {
        if (request.headers.containsKey("transfer-encoding")) {
            throw HttpProtocolException(411, "Transfer-Encoding wird nicht unterstützt")
        }
        val raw = request.headers["content-length"] ?: throw HttpProtocolException(411, "Content-Length fehlt")
        val length = raw.toLongOrNull() ?: throw HttpProtocolException(400, "Content-Length ungültig")
        if (length < 0) throw HttpProtocolException(400, "Content-Length ungültig")
        if (length > maxBytes) throw HttpProtocolException(413, "Körper zu groß")
        return length
    }

    /** Liest den Körper vollständig, ohne ihn zu speichern. Liefert die Anzahl gelesener Bytes. */
    fun discardBody(
        input: InputStream,
        length: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw HttpProtocolException(400, "Körper unvollständig")
            remaining -= read
        }
        return length - remaining
    }

    fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val head =
            buildString {
                append("HTTP/1.1 $status ${reason(status)}\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    internal fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }

    /** Liest Zeilen bis zur Leerzeile. Die Obergrenze gilt für alle Bytes zusammen. */
    private fun readHeadLines(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val current = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (lines.isEmpty() &&
                    current.size() == 0
                ) {
                    null
                } else {
                    throw HttpProtocolException(400, "Verbindung endet früh")
                }
            }
            total++
            if (total > MAX_HEAD_BYTES) throw HttpProtocolException(431, "Kopfzeilen zu groß")
            if (byte == '\n'.code) {
                val line = current.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                current.reset()
                if (line.isEmpty()) return lines
                lines += line
            } else {
                current.write(byte)
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val index = part.indexOf('=')
                val key = if (index < 0) part else part.substring(0, index)
                val value = if (index < 0) "" else part.substring(index + 1)
                URLDecoder.decode(key, Charsets.UTF_8.name()) to URLDecoder.decode(value, Charsets.UTF_8.name())
            }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
