package io.github.labushuya.emberlight.share.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.InviteState
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import io.github.labushuya.emberlight.share.space.preview.PreviewRenderer
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Ein Space für die Browser-Tests der Gäste-Seite (ADR 0019, Paket 3.5a-3). Die Szene legt Datenbank, Vault und
 * Zugriffsprüfung an, startet den echten Server (CIO, Loopback, Gäste-Seite und Space-API) und bietet die Schritte
 * an, die ein Gast nicht selbst ausführen kann: Beitritt, Upload, Entfernen und Ende.
 *
 * Das Ende ist ein Flag ([closure]). Der Server schließt sonst nichts von selbst. [endsAtMillis] steuert nur die
 * Restzeit, die der Server meldet.
 */
internal class BrowserScene(
    private val spacesDir: File,
    val spaceName: String,
    val endsAtMillis: Long?,
) : AutoCloseable {
    val spaceId: SpaceId = SpaceId.generate()
    private val limits = SpaceLimits()
    private val database: SpacesDatabase =
        SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
    private val vault: SpaceVault =
        SpaceVault.open(
            database,
            spacesDir,
            spaceId,
            limits,
            StorageProbe { Long.MAX_VALUE / 4 },
            InviteSecretCipherFake(),
            previewRenderer = ImageIoPreviewRenderer(),
        )
    val gates = ServerGates()
    val closure = FlagClosure()

    // Wie in der Produktion (SpaceRuntime): der Space steht in der Tabelle, dann kommt die Einladung aus dem Vault.
    private val invite: InviteState =
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = spaceId.value,
                    name = spaceName,
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = null,
                    lifetimeUntil = null,
                    retentionDays = 7,
                    storageLimitBytes = limits.storageLimitBytes,
                    status = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            vault.ensureInvite()
        }
    val access: SpaceAccess =
        SpaceAccess(
            vault = vault,
            spaceId = spaceId,
            spaceName = spaceName,
            live = LiveAuthorizer(emptyList()),
            uploads = ActiveUploads(),
            invite = invite.secret,
            hostToken = null,
            mediaSigner = MediaUrlSigner.random(),
            endsAtMillis = endsAtMillis,
            limits = limits,
        )
    private var server: KtorLocalServer? = null

    /** Port des laufenden Servers, `0` vor dem Start. */
    var port: Int = 0
        private set

    val inviteSecret: String
        get() = access.inviteSecret

    /** Startet den Server auf 127.0.0.1 mit einem freien Port. Die Gäste-Dateien liest er aus dem Modul. */
    fun start(): Int =
        runBlocking {
            val assets = GuestAssets { name -> File(GUEST_DIR, name).takeIf { it.isFile }?.readBytes() }
            val created =
                KtorLocalServer(
                    routes = serverRoutes(access, assets, gates),
                    authorizer = access.live,
                    gates = gates,
                    closure = closure,
                    signedMedia = access.mediaAccess,
                )
            port = created.start(ServerConfig(subnet = Ipv4Subnet("127.0.0.1", 8)))
            server = created
            port
        }

    /** Stoppt den Server, etwa für den Verbindungsverlust. Die Datenbank bleibt offen. */
    fun stopServer() =
        runBlocking {
            server?.stop()
            server = null
        }

    /** Tritt dem Space bei. Liefert Teilnehmer und Token, wie der Beitritt im Browser. */
    fun join(name: String): Guest =
        runBlocking {
            when (val outcome = access.join(access.inviteSecret, name)) {
                is JoinOutcome.Joined -> Guest(outcome.participantId, outcome.displayName, outcome.token)
                else -> error("Beitritt von $name abgelehnt: $outcome")
            }
        }

    /** Entfernt einen Gast. Seine Uploads bleiben und zeigen „entfernt“. */
    fun removeGuest(id: ParticipantId) {
        val outcome = runBlocking { access.removeGuest(id, deleteUploads = false) }
        check(outcome == RemoveOutcome.Removed) { "Entfernen abgelehnt: $outcome" }
    }

    /** Lädt ein Medium über die echte Schnittstelle hoch. Liefert die Medien-ID. */
    fun upload(
        token: String,
        name: String,
        bytes: ByteArray,
        keepLocation: Boolean = false,
    ): String {
        check(port > 0) { "Der Server läuft nicht." }
        val connection = URL("http://127.0.0.1:$port/api/v1/media").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("X-Emberlight-Filename", name)
            if (keepLocation) connection.setRequestProperty("X-Emberlight-Keep-Location", "1")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val body =
                (
                    if (status <
                        400
                    ) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                ).bufferedReader().readText()
            check(status == 201) { "Upload $name: HTTP $status $body" }
            return Regex("\"id\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: error("Upload $name ohne Medien-ID: $body")
        } finally {
            connection.disconnect()
        }
    }

    /** Erzeugt die Vorschauen der Medien, wie der Hintergrunddienst es täte (ADR 0019, Paket 3.5a-2). */
    fun renderPreviews(ids: List<String>): Map<String, String?> =
        runBlocking {
            ids.forEach { vault.renderPreviews(MediaId(it)) }
            ids.associateWith { vault.entry(MediaId(it))?.previewState }
        }

    /** Markiert den Space als beendet. Der nächste Aufruf eines Gastes bekommt `410`. */
    fun markClosed() = closure.markClosed()

    override fun close() {
        stopServer()
        database.close()
    }

    /** Ein Gast mit Anzeigename, Teilnehmer-ID und Token. */
    data class Guest(
        val id: ParticipantId,
        val displayName: String,
        val token: String,
    )

    companion object {
        /** Gäste-Dateien im Modul, wie die App sie als Assets führt. */
        private val GUEST_DIR = File("src/main/assets/guest")

        /** Ein kleines, echtes JPEG (Bild, damit der Browser es darstellt). Ohne GPS-Daten. */
        fun jpeg(rgb: Int): ByteArray {
            val image = BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(rgb)
            graphics.fillRect(0, 0, 160, 120)
            graphics.color = Color.WHITE
            graphics.fillOval(40, 30, 80, 60)
            graphics.dispose()
            return ByteArrayOutputStream().use { out ->
                ImageIO.write(image, "jpg", out)
                out.toByteArray()
            }
        }

        /** Ein kurzes, echtes Video (MP4, H.264) aus `src/test/browser/fixtures`. */
        fun clip(): ByteArray {
            val file = File("src/test/browser/fixtures/clip.mp4")
            check(file.isFile) { "Video-Fixture fehlt: ${file.absolutePath}" }
            return file.readBytes()
        }
    }
}

/** Schaltbares Ende. Der Server antwortet dann mit `410` (ADR 0017). */
internal class FlagClosure : SpaceClosure {
    private val closed = AtomicBoolean(false)

    fun markClosed() {
        closed.set(true)
    }

    override fun isClosed(): Boolean = closed.get()

    override fun onClosedRequest() {}
}

/**
 * Vorschauen mit der JDK (ImageIO), damit die Browser-Tests echte Vorschau-Bilder sehen. Der Android-Dekodierer läuft unter
 * Robolectric nicht zuverlässig. Bilder werden auf die lange Kante verkleinert. Videobilder und HEIC gelten als nicht
 * erzeugbar, dort zeigt die Seite den Platzhalter.
 */
internal class ImageIoPreviewRenderer : PreviewRenderer {
    override fun renderImage(
        source: File,
        mime: String,
        longEdge: Int,
        target: File,
    ): Boolean {
        if (mime != "image/jpeg" && mime != "image/png") return false
        val original = ImageIO.read(source) ?: return false
        val scale = minOf(1.0, longEdge.toDouble() / maxOf(original.width, original.height))
        val width = (original.width * scale).toInt().coerceAtLeast(1)
        val height = (original.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            drawImage(original, 0, 0, width, height, null)
            dispose()
        }
        return ImageIO.write(scaled, "jpg", target)
    }

    override fun renderVideoFrame(
        source: File,
        longEdge: Int,
        target: File,
    ): Boolean = false

    override fun videoCodec(source: File): String? = null
}
