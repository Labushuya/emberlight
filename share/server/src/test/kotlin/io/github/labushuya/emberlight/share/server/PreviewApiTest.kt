package io.github.labushuya.emberlight.share.server

import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.Credential
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import io.github.labushuya.emberlight.share.space.participants.Secrets
import io.github.labushuya.emberlight.share.space.preview.PreviewRenderer
import io.github.labushuya.emberlight.share.space.preview.RenderResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Vorschau und HEIC-Ansicht über die Ktor-Testumgebung (Paket 3.5a-2, ADR 0019): Zustände, `404 preview_unavailable`,
 * signierte Adresse gegen Header, kein Medien-Gate, Felder der Medienliste und der Entfernen-Fall. Der Renderer ist ein
 * Ersatz, die Zeit fest.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var vault: SpaceVault
    private lateinit var access: SpaceAccess
    private lateinit var spacesDir: File
    private var now = 1_000_000L
    private val signer = MediaUrlSigner.forTest(ByteArray(32) { (it + 7).toByte() }) { now }
    private val spaceId = SpaceId.generate()
    private val host = Principal(ParticipantId.generate(), Role.HOST)
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val gates = ServerGates()
    private val renderer = StubRenderer()

    private val limits =
        SpaceLimits(
            imageMaxBytes = 2_000,
            videoMaxBytes = 6_000,
            storageLimitBytes = 1_000_000,
            minDeviceReserveBytes = 0,
        )

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<android.content.Context>(), spacesDir)
        vault =
            SpaceVault.open(
                database,
                spacesDir,
                spaceId,
                limits,
                StorageProbe { Long.MAX_VALUE / 4 },
                InviteSecretCipherFake(),
                renderer,
            )
        runBlocking {
            vault.registerParticipant(host.participantId, "Gastgeberin", Role.HOST, "0".repeat(64))
            vault.registerParticipant(guest.participantId, "Gast", Role.GUEST, "0".repeat(64))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun ApplicationTestBuilder.install() {
        val credentials =
            listOf(
                Credential(Secrets.hashHex(HOST_TOKEN), host.participantId, host.role),
                Credential(Secrets.hashHex(GUEST_TOKEN), guest.participantId, guest.role),
            )
        access = testAccess(vault, spaceId, credentials, "Test-Space", mediaSigner = signer, limits = limits)
        val env =
            ServerEnvironment(
                authorizer = access.live,
                expectedHost = { "localhost" },
                senderAllowed = { true },
                gates = gates,
                requestLog = { },
                closure = SpaceClosure.Open,
                debugLogging = false,
                signedMedia = access.mediaAccess,
            )
        application {
            installEmberlightModule(env, routes = spaceRoutes(access, gates, UploadTuning(pollMillis = 1)))
        }
    }

    private suspend fun HttpClient.upload(
        bytes: ByteArray,
        name: String,
        keepLocation: Boolean = false,
    ): String {
        val response =
            post("/api/v1/media") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Authorization, "Bearer $GUEST_TOKEN")
                header("X-Emberlight-Filename", name)
                if (keepLocation) header("X-Emberlight-Keep-Location", "1")
                header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                setBody(bytes)
            }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("id")
            .jsonPrimitive.content
    }

    private fun signedPath(
        mediaId: String,
        route: String,
        purpose: MediaPurpose,
        who: ParticipantId = guest.participantId,
    ): String {
        val grant = signer.grant(mediaId, purpose, who)
        return "/api/v1/media/$mediaId/$route?pid=${who.value}&exp=${grant.expiresAt}&sig=${grant.signature}"
    }

    private suspend fun HttpClient.authGet(
        path: String,
        token: String = GUEST_TOKEN,
    ): HttpResponse =
        get(path) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    private suspend fun HttpClient.signedGet(path: String): HttpResponse =
        get(path) {
            header(HttpHeaders.Host, "localhost")
        }

    private suspend fun HttpClient.listItems(): Map<String, JsonObject> {
        val response = authGet("/api/v1/media")
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("id").jsonPrimitive.content }
    }

    private fun mediaHeaders(response: HttpResponse) {
        assertEquals(listOf("no-referrer"), response.headers.getAll("Referrer-Policy"))
        assertEquals(listOf("same-origin"), response.headers.getAll("Cross-Origin-Resource-Policy"))
        assertEquals(
            listOf("default-src 'none'; frame-ancestors 'none'"),
            response.headers.getAll("Content-Security-Policy"),
        )
        assertEquals(listOf("nosniff"), response.headers.getAll("X-Content-Type-Options"))
        assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl))
    }

    private suspend fun errorOf(response: HttpResponse): String =
        Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("error")
            .jsonPrimitive.content

    @Test
    fun previewIsUnavailableUntilItIsRenderedThenServedAsAJpegWithTheMediaHeaders() =
        testApplication {
            install()
            val id = client.upload(StubRenderer.BYTES_IN, "bild.jpg")
            val path = signedPath(id, "preview", MediaPurpose.PREVIEW)

            val pending = client.signedGet(path)
            assertEquals(HttpStatusCode.NotFound, pending.status)
            assertEquals("preview_unavailable", errorOf(pending))

            runBlocking { vault.renderPreviews(MediaId(id)) }

            val ready = client.signedGet(path)
            assertEquals(HttpStatusCode.OK, ready.status)
            assertEquals(ContentType.Image.JPEG.toString(), ready.headers[HttpHeaders.ContentType])
            assertEquals("inline", ready.headers[HttpHeaders.ContentDisposition])
            assertEquals("image-320", ready.bodyAsBytes().decodeToString())
            mediaHeaders(ready)
        }

    @Test
    fun thePreviewAcceptsTheHeaderAndTheSignedUrlButNotASignatureOfAnotherRoute() =
        testApplication {
            install()
            val id = client.upload(StubRenderer.BYTES_IN, "bild.jpg")
            runBlocking { vault.renderPreviews(MediaId(id)) }

            val byHeader = client.authGet("/api/v1/media/$id/preview")
            assertEquals(HttpStatusCode.OK, byHeader.status)

            val wrongRoute = client.signedGet(signedPath(id, "preview", MediaPurpose.VIEW))
            assertEquals(HttpStatusCode.Forbidden, wrongRoute.status)
            assertEquals("url_invalid", errorOf(wrongRoute))
        }

    @Test
    fun theListLinksThePreviewOnlyOnceItIsReady() =
        testApplication {
            install()
            val id = client.upload(StubRenderer.BYTES_IN, "bild.jpg")
            assertEquals(JsonNull, client.listItems().getValue(id).getValue("previewUrl"))

            runBlocking { vault.renderPreviews(MediaId(id)) }

            val item = client.listItems().getValue(id)
            assertEquals("ready", item.getValue("previewState").jsonPrimitive.content)
            val url = item.getValue("previewUrl").jsonPrimitive.content
            assertTrue(url.startsWith("/api/v1/media/$id/preview?pid=${guest.participantId.value}"))
            assertEquals(HttpStatusCode.OK, client.signedGet(url).status)
            assertEquals(JsonNull, item.getValue("viewState"))
        }

    @Test
    fun aPreviewOfAnUnknownMediumIsNotFound() =
        testApplication {
            install()

            val response = client.authGet("/api/v1/media/${MediaId.generate().value}/preview")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("not_found", errorOf(response))
        }

    @Test
    fun previewsDoNotCountAgainstTheMediaGateWhileTheOriginalDoes() =
        testApplication {
            install()
            val id = client.upload(StubRenderer.BYTES_IN, "bild.jpg")
            runBlocking { vault.renderPreviews(MediaId(id)) }
            repeat(ServerGates.MEDIA_LIMIT) { assertTrue(gates.tryEnterMedia()) }

            val preview = client.authGet("/api/v1/media/$id/preview")
            val original = client.authGet("/api/v1/media/$id/content")

            assertEquals(HttpStatusCode.OK, preview.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, original.status)
        }

    @Test
    fun aRemovedParticipantLosesTheSignedPreviewAtOnce() =
        testApplication {
            install()
            val id = client.upload(StubRenderer.BYTES_IN, "bild.jpg")
            runBlocking { vault.renderPreviews(MediaId(id)) }
            val path = signedPath(id, "preview", MediaPurpose.PREVIEW)
            assertEquals(HttpStatusCode.OK, client.signedGet(path).status)

            assertEquals(
                RemoveOutcome.Removed,
                runBlocking { access.removeGuest(guest.participantId, deleteUploads = false) },
            )

            val after = client.signedGet(path)
            assertEquals(HttpStatusCode.Forbidden, after.status)
            assertEquals("participant_inactive", errorOf(after))
        }

    @Test
    fun aHeicViewIsPendingThenServedAtOneThousandSixHundredPixelsWhileTheDownloadStaysTheOriginal() =
        testApplication {
            install()
            val id = client.upload(HEIC_BYTES, "foto.heic", keepLocation = true)
            val pending = client.listItems().getValue(id)
            assertEquals("pending", pending.getValue("viewState").jsonPrimitive.content)
            assertEquals(JsonNull, pending.getValue("viewUrl"))
            val viewPath = signedPath(id, "view", MediaPurpose.VIEW)

            val early = client.signedGet(viewPath)
            assertEquals(HttpStatusCode.NotFound, early.status)
            assertEquals("preview_unavailable", errorOf(early))

            runBlocking { vault.renderPreviews(MediaId(id)) }

            val ready = client.listItems().getValue(id)
            assertEquals("ready", ready.getValue("viewState").jsonPrimitive.content)
            val url = ready.getValue("viewUrl").jsonPrimitive.content
            val view = client.signedGet(url)
            assertEquals(HttpStatusCode.OK, view.status)
            assertEquals(ContentType.Image.JPEG.toString(), view.headers[HttpHeaders.ContentType])
            assertEquals("image-1600", view.bodyAsBytes().decodeToString())

            val download = client.signedGet(signedPath(id, "content", MediaPurpose.DOWNLOAD))
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals(HEIC_BYTES.toList(), download.bodyAsBytes().toList())
        }

    @Test
    fun aVideoListsItsCodecAndAPreviewAfterRendering() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.mp4(), "clip.mp4")
            assertEquals(
                "hvc1",
                client
                    .listItems()
                    .getValue(id)
                    .getValue("videoCodec")
                    .jsonPrimitive.content,
            )

            runBlocking { vault.renderPreviews(MediaId(id)) }

            val item = client.listItems().getValue(id)
            assertEquals("ready", item.getValue("previewState").jsonPrimitive.content)
            assertTrue(item.getValue("previewUrl") != JsonNull)
            assertEquals("video-320", client.authGet("/api/v1/media/$id/preview").bodyAsBytes().decodeToString())
        }

    /** Ersatz für die Plattformdekoder: schreibt feste Bytes, Codec `hvc1` für Videos. */
    private class StubRenderer : PreviewRenderer {
        override fun renderImage(
            source: File,
            mime: String,
            longEdge: Int,
            target: File,
        ): RenderResult = write(target, "image-$longEdge")

        override fun renderVideoFrame(
            source: File,
            longEdge: Int,
            target: File,
        ): RenderResult = write(target, "video-$longEdge")

        override fun videoCodec(source: File): String? = "hvc1"

        private fun write(
            target: File,
            text: String,
        ): RenderResult {
            target.writeBytes(text.toByteArray(Charsets.US_ASCII))
            return RenderResult.Done
        }

        companion object {
            val BYTES_IN: ByteArray = MediaFixtures.jpeg(300)
        }
    }

    private companion object {
        const val HOST_TOKEN = "tok-host-0123456789-preview"
        const val GUEST_TOKEN = "tok-guest-0123456789-preview"

        /** HEIC ohne auswertbare Standortdaten: nur mit Einwilligung annehmbar (ADR 0015). */
        val HEIC_BYTES: ByteArray = MediaFixtures.unremovableHeic()
    }
}
