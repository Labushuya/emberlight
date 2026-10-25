package io.github.labushuya.emberlight.netlab.server

import io.github.labushuya.emberlight.netlab.guest.GuestTracker
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kleiner Testserver für Paket 4.0 (Funktion B4). Gebunden an genau eine Adresse, die Hotspot-IP des Gastgebers.
 * Endpunkte:
 * - `GET /ping`: Antwort ohne Last, mit Token-Prüfung.
 * - `POST /upload`: nimmt den Körper an (bis [maxUploadBytes]) und meldet Bytes und Dauer, mit Token-Prüfung.
 * - `GET /browser`: einfache HTML-Seite „verbunden“ für den Browser-Test, ohne Token.
 *
 * Das Token kommt im Header `X-Netlab-Token`, nicht in der URL, damit es in keinem Protokoll landet.
 * Keine Verbindung nach außen, keine Speicherung von Daten, keine Dateizugriffe.
 */
class NetlabServer(
    private val token: String,
    private val tracker: GuestTracker,
    private val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val slots = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    /** Gebundene Adresse nach dem Start, bei Port 0 mit dem tatsächlichen Port. */
    @Volatile
    var boundAddress: InetSocketAddress? = null
        private set

    /**
     * Bindet an [host] und [port] und startet den Akzeptierer. Mit Port 0 wählt das System einen freien Port.
     * Schlägt das Binden fehl (z. B. Adresse nicht mehr vorhanden), wird die Ausnahme weitergereicht.
     */
    @Synchronized
    fun start(
        host: InetAddress,
        port: Int,
    ) {
        check(running.compareAndSet(false, true)) { "Server läuft bereits" }
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(host, port), BACKLOG)
            socket = server
            boundAddress = server.localSocketAddress as InetSocketAddress
            val pool =
                Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "netlab-http").apply {
                        isDaemon =
                            true
                    }
                }
            executor = pool
            pool.execute { acceptLoop(server, pool) }
        } catch (e: IOException) {
            running.set(false)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        executor?.shutdownNow()
        socket = null
        executor = null
        boundAddress = null
    }

    val isRunning: Boolean get() = running.get()

    private fun acceptLoop(
        server: ServerSocket,
        pool: ExecutorService,
    ) {
        while (running.get()) {
            val client =
                try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
            if (!slots.tryAcquire()) {
                // Zu viele gleichzeitige Verbindungen. Die neue wird sofort geschlossen, ohne Antwort.
                client.close()
                continue
            }
            pool.execute {
                try {
                    handle(client)
                } finally {
                    slots.release()
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { conn ->
            conn.soTimeout = READ_TIMEOUT_MS
            val remote = conn.inetAddress.hostAddress ?: return
            tracker.touch(remote)
            try {
                val output = conn.getOutputStream()
                val input = conn.getInputStream().buffered()
                try {
                    val request = HttpWire.readHead(input) ?: return
                    route(request, input, output)
                } catch (e: HttpProtocolException) {
                    respondText(output, e.status, "Fehler: ${HttpWire.reason(e.status)}")
                }
            } catch (_: IOException) {
                // Verbindung abgebrochen oder Zeitüberschreitung. Der Gast sieht den Fehler selbst.
            }
        }
    }

    private fun route(
        request: HttpRequest,
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        when (request.path) {
            "/browser" -> {
                if (request.method != "GET") throw HttpProtocolException(405, "Nur GET")
                HttpWire.writeResponse(
                    output,
                    200,
                    "text/html; charset=utf-8",
                    BROWSER_PAGE.toByteArray(Charsets.UTF_8),
                )
            }

            "/ping" -> {
                if (request.method != "GET") throw HttpProtocolException(405, "Nur GET")
                requireToken(request)
                respondJson(output, """{"ok":true,"serverMs":${clock()}}""")
            }

            "/upload" -> {
                if (request.method != "POST") throw HttpProtocolException(405, "Nur POST")
                requireToken(request)
                val length = HttpWire.contentLength(request, maxUploadBytes)
                val startedAt = System.nanoTime()
                val received = HttpWire.discardBody(input, length)
                val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                respondJson(output, """{"bytes":$received,"ms":$millis}""")
            }

            else -> {
                throw HttpProtocolException(404, "Unbekannter Pfad")
            }
        }
    }

    private fun requireToken(request: HttpRequest) {
        val given = request.headers[TOKEN_HEADER].orEmpty().toByteArray(Charsets.UTF_8)
        val expected = token.toByteArray(Charsets.UTF_8)
        // Vergleich in konstanter Zeit, damit die Antwortzeit keine Zeichen des Tokens verrät.
        if (!MessageDigest.isEqual(given, expected)) throw HttpProtocolException(401, "Token fehlt oder ist falsch")
    }

    private fun respondJson(
        output: java.io.OutputStream,
        json: String,
    ) {
        HttpWire.writeResponse(output, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun respondText(
        output: java.io.OutputStream,
        status: Int,
        text: String,
    ) {
        try {
            HttpWire.writeResponse(output, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Der Gast hat die Verbindung bereits geschlossen.
        }
    }

    companion object {
        const val TOKEN_HEADER = "x-netlab-token"
        const val DEFAULT_MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        private const val BACKLOG = 32
        private const val MAX_CONCURRENT_CONNECTIONS = 32
        private const val READ_TIMEOUT_MS = 15_000
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Seite für den Browser-Test. Feste Inhalte, keine Eingabe, keine Skripte. */
        const val BROWSER_PAGE =
            "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>emberlight Netlab</title></head><body>" +
                "<h1>Verbunden</h1><p>Dieses Gerät erreicht den emberlight-Netlab-Testserver im Hotspot.</p>" +
                "</body></html>"
    }
}
