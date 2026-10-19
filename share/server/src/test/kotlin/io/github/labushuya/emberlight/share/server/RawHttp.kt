package io.github.labushuya.emberlight.share.server

import java.net.Socket

/** Antwort einer rohen HTTP-Anfrage. Header in Kleinbuchstaben. */
internal class RawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Rohe HTTP-Anfragen über einen echten Socket. Nötig, weil `HttpURLConnection` Host und mehrere Authorization-Header
 * nicht frei setzen lässt. Jede Anfrage sendet `Connection: close`, damit die Antwort bis zum Schluss gelesen werden
 * kann.
 */
internal object RawHttp {
    fun get(
        port: Int,
        path: String,
        vararg headers: Pair<String, String>,
    ): RawResponse = send(port, request("GET", path, headers.toList()))

    fun request(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        // Ein Körper braucht eine Längenangabe, sonst liest der Server ihn nicht als Teil der Anfrage.
        val explicitLength = headers.any { it.first.equals("Content-Length", true) }
        val chunked = headers.any { it.first.equals("Transfer-Encoding", true) }
        val all =
            if (body.isNotEmpty() && !explicitLength && !chunked) {
                headers + ("Content-Length" to body.size.toString())
            } else {
                headers
            }
        val head = StringBuilder("$method $path HTTP/1.1\r\n")
        all.forEach { (name, value) -> head.append("$name: $value\r\n") }
        head.append("Connection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    fun send(
        port: Int,
        bytes: ByteArray,
        timeoutMs: Int = TIMEOUT_MS,
    ): RawResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
            return parse(socket.getInputStream().readBytes())
        }
    }

    /** Öffnet eine Verbindung, schickt [partial] und schließt sie ohne Ende. Simuliert einen Abbruch mitten im Upload. */
    fun sendAndAbort(
        port: Int,
        partial: ByteArray,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(partial)
                flush()
            }
        }
    }

    private fun parse(raw: ByteArray): RawResponse {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        val head = if (split < 0) text else text.substring(0, split)
        val body = if (split < 0) "" else text.substring(split + 4)
        val lines = head.split("\r\n")
        val status =
            lines
                .first()
                .split(" ")
                .getOrNull(1)
                ?.toIntOrNull() ?: -1
        val headers =
            lines
                .drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon < 0) null else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                }.toMap()
        return RawResponse(status, headers, body)
    }

    private const val TIMEOUT_MS = 10_000
}
