package io.github.labushuya.emberlight.share.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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

    /**
     * Sendet Kopf und Körper und liest die Antwort gleichzeitig, wie ein Browser oder curl. Der Server darf vor dem Ende des
     * Körpers antworten und die Verbindung schließen. Dann schlägt das Schreiben fehl, das ist hier erwartet. Liefert die
     * Antwort (`null`, wenn keine ankam) und die Zahl der Körperbytes, die in den Socket geschrieben wurden.
     *
     * Die Antwort wird nebenläufig gelesen. Ein Lesen erst nach dem Schreiben verliert die Antwort: Schließt der Server
     * mit ungelesenen Bytes im Puffer, sendet er RST, und Windows verwirft den bereits empfangenen Rest.
     */
    fun sendBodyAnsweringEarly(
        port: Int,
        head: ByteArray,
        bodySize: Long,
    ): Pair<RawResponse?, Long> =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val reply = CompletableFuture.supplyAsync { readReply(socket) }
            var sent = 0L
            try {
                val out = socket.getOutputStream()
                out.write(head)
                val chunk = ByteArray(CHUNK_BYTES) { 0x55 }
                while (sent < bodySize) {
                    val count = minOf(CHUNK_BYTES.toLong(), bodySize - sent).toInt()
                    out.write(chunk, 0, count)
                    sent += count.toLong()
                }
                out.flush()
            } catch (_: IOException) {
                // Der Server hat die Verbindung geschlossen. Das ist der erwartete Fall.
            }
            val raw = reply.get(TIMEOUT_MS.toLong() + 5_000L, TimeUnit.MILLISECONDS)
            (if (raw.isEmpty()) null else parse(raw)) to sent
        }

    /** Liest, bis der Server schließt oder zurücksetzt. Was bis dahin ankam, bleibt erhalten. */
    private fun readReply(socket: Socket): ByteArray {
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        try {
            val input = socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                collected.write(buffer, 0, count)
            }
        } catch (_: IOException) {
            // Zurückgesetzt oder Zeitüberschreitung. Die bis dahin empfangene Antwort zählt.
        }
        return collected.toByteArray()
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
    private const val CHUNK_BYTES = 16 * 1024
}
