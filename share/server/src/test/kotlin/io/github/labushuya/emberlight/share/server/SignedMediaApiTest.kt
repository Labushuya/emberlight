package io.github.labushuya.emberlight.share.server

import androidx.test.core.app.ApplicationProvider
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Signierte Medien-URLs über die Ktor-Testumgebung (ADR 0019): Prüfung, Zweck, Ablauf, Entfernen, Range und Logging.
 * Die Uhr ist eine Variable, es gibt keine Sleeps.
 */
@RunWith(RobolectricTestRunner::class)
class SignedMediaApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var vault: SpaceVault
    private lateinit var spacesDir: File
    private lateinit var access: SpaceAccess
    private var now = 1_000_000L
    private val signer = MediaUrlSigner.forTest(ByteArray(32) { (it + 1).toByte() }) { now }
    private val spaceId = SpaceId.generate()
    private val host = Principal(ParticipantId.generate(), Role.HOST)
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val gates = ServerGates()
    private val logLines = mutableListOf<String>()

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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = SpacesDatabase.open(context, spacesDir)
        vault =
            SpaceVault.open(
                database,
                spacesDir,
                spaceId,
                limits,
                StorageProbe { Long.MAX_VALUE / 4 },
                InviteSecretCipherFake(),
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
                requestLog = { logLines += it },
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
    ): String {
        val response =
            post("/api/v1/media") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Authorization, "Bearer $GUEST_TOKEN")
                header("X-Emberlight-Filename", name)
                header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                setBody(bytes)
            }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
    }

    /** Signierte Adresse für [route] mit dem zugehörigen Zweck, ausgestellt jetzt. */
    private fun signedPath(
        mediaId: String,
        route: String,
        purpose: MediaPurpose,
        who: ParticipantId = guest.participantId,
    ): String {
        val grant = signer.grant(mediaId, purpose, who)
        return "/api/v1/media/$mediaId/$route?pid=${who.value}&exp=${grant.expiresAt}&sig=${grant.signature}"
    }

    private suspend fun HttpClient.signedGet(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse =
        get(path) {
            header(HttpHeaders.Host, "localhost")
            headers.forEach { (name, value) -> header(name, value) }
        }

    private fun mediaHeaders(response: HttpResponse) {
        assertEquals(listOf("no-referrer"), response.headers.getAll("Referrer-Policy"))
        assertEquals(listOf("same-origin"), response.headers.getAll("Cross-Origin-Resource-Policy"))
        assertEquals(listOf("default-src 'none'; frame-ancestors 'none'"), response.headers.getAll("Content-Security-Policy"))
        assertEquals(listOf("nosniff"), response.headers.getAll("X-Content-Type-Options"))
        assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl))
    }

    @Test
    fun signedViewUrlServesTheOriginalInlineWithMediaHeaders() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val response = client.signedGet(signedPath(id, "view", MediaPurpose.VIEW))

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("inline", response.headers[HttpHeaders.ContentDisposition])
            assertEquals(300, response.bodyAsBytesSize())
            mediaHeaders(response)
        }

    @Test
    fun signedDownloadUrlIsAnAttachment() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "urlaub.jpg")

            val response = client.signedGet(signedPath(id, "content", MediaPurpose.DOWNLOAD))

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
            mediaHeaders(response)
        }

    @Test
    fun aTamperedSignatureIsForbiddenWithUrlInvalid() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            val good = signedPath(id, "view", MediaPurpose.VIEW)
            val bad = good.replaceAfter("sig=", "AAAA")

            val response = client.signedGet(bad)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("url_invalid"))
        }

    @Test
    fun aSignatureForOneRouteDoesNotOpenAnother() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            val viewSigned = signedPath(id, "view", MediaPurpose.VIEW)

            val onDownload = client.signedGet(viewSigned.replace("/view?", "/content?"))

            assertEquals(HttpStatusCode.Forbidden, onDownload.status)
        }

    @Test
    fun anExpiredUrlIsForbiddenWithUrlExpiredUntilANewOneWorks() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            val old = signedPath(id, "view", MediaPurpose.VIEW)
            now += MediaPurpose.VIEW.ttlSeconds

            val expired = client.signedGet(old)
            assertEquals(HttpStatusCode.Forbidden, expired.status)
            assertTrue(expired.bodyAsText().contains("url_expired"))

            val fresh = client.signedGet(signedPath(id, "view", MediaPurpose.VIEW))
            assertEquals(HttpStatusCode.OK, fresh.status)
        }

    @Test
    fun removedParticipantLosesSignedAccessAtOnce() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            val url = signedPath(id, "view", MediaPurpose.VIEW)
            runBlocking {
                assertEquals(RemoveOutcome.Removed, access.removeGuest(guest.participantId, deleteUploads = false))
            }

            val response = client.signedGet(url)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("participant_inactive"))
        }

    @Test
    fun headerAuthorizationStillWorksOnMediaRoutes() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val response = client.signedGet("/api/v1/media/$id/view", HttpHeaders.Authorization to "Bearer $GUEST_TOKEN")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun signedParametersOnOtherRoutesDoNotReplaceTheHeader() =
        testApplication {
            install()
            val query = "pid=${guest.participantId.value}&exp=${now + 600}&sig=AAAA"

            val response = client.signedGet("/api/v1/space?$query")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun streamServesVideoWithRangeAndRejectsImages() =
        testApplication {
            install()
            val video = client.upload(MediaFixtures.mp4(), "clip.mp4")
            val image = client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val partial =
                client.signedGet(
                    signedPath(video, "stream", MediaPurpose.STREAM),
                    HttpHeaders.Range to "bytes=0-3",
                )
            assertEquals(HttpStatusCode.PartialContent, partial.status)
            assertEquals(4, partial.bodyAsBytesSize())
            mediaHeaders(partial)

            val notVideo = client.signedGet(signedPath(image, "stream", MediaPurpose.STREAM))
            assertEquals(HttpStatusCode.NotFound, notVideo.status)
        }

    @Test
    fun streamUrlLivesThirtyMinutesAndRangeRequestsWithSeveralPartsAreRefused() =
        testApplication {
            install()
            val video = client.upload(MediaFixtures.mp4(), "clip.mp4")
            val url = signedPath(video, "stream", MediaPurpose.STREAM)
            now += 25 * 60

            assertEquals(HttpStatusCode.OK, client.signedGet(url).status)

            val several =
                client.signedGet(url, HttpHeaders.Range to "bytes=0-1,3-4")
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, several.status)
            assertTrue(several.headers[HttpHeaders.ContentRange].orEmpty().startsWith("bytes */"))
            assertFalse(several.bodyAsText().contains("multipart"))

            now += 6 * 60
            assertEquals(HttpStatusCode.Forbidden, client.signedGet(url).status)
        }

    @Test
    fun logLinesNeverContainTheQueryOrTheSignature() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            val grant = signer.grant(id, MediaPurpose.VIEW, guest.participantId)
            val url = "/api/v1/media/$id/view?pid=${guest.participantId.value}&exp=${grant.expiresAt}&sig=${grant.signature}"

            client.signedGet(url)

            val joined = logLines.joinToString("\n")
            assertTrue(joined.contains("route=/api/v1/media/{id}/view"))
            assertFalse(joined.contains(grant.signature))
            assertFalse(joined.contains("sig="))
            assertFalse(joined.contains("pid="))
        }

    @Test
    fun listItemsCarrySignedUrlsAndMineFlagThatWorkForTheRequester() =
        testApplication {
            install()
            client.upload(MediaFixtures.jpeg(300), "bild.jpg")
            client.upload(MediaFixtures.mp4(), "clip.mp4")

            val list =
                client.get("/api/v1/media") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $GUEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, list.status)
            val items = Json.parseToJsonElement(list.bodyAsText()).jsonObject.getValue("items").jsonArray
            assertEquals(2, items.size)
            val video = items.map { it.jsonObject }.single { it.getValue("type").jsonPrimitive.content == "video/mp4" }
            val image = items.map { it.jsonObject }.single { it.getValue("type").jsonPrimitive.content == "image/jpeg" }
            assertTrue(video.getValue("mine").jsonPrimitive.boolean)
            assertEquals(600L, video.getValue("urlsExpireInSeconds").jsonPrimitive.long)
            assertTrue(video.containsKey("streamUrl"))
            assertFalse(image.containsKey("streamUrl"))
            // Die Adresse aus der Liste funktioniert ohne Authorization.
            val viewPath = image.getValue("viewUrl").jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, client.signedGet(viewPath).status)
            assertEquals(HttpStatusCode.OK, client.signedGet(video.getValue("streamUrl").jsonPrimitive.content).status)
        }

    @Test
    fun mineIsFalseForTheHostForAGuestsUpload() =
        testApplication {
            install()
            client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val list =
                client.get("/api/v1/media") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $HOST_TOKEN")
                }
            val item = Json.parseToJsonElement(list.bodyAsText()).jsonObject.getValue("items").jsonArray.single().jsonObject
            assertFalse(item.getValue("mine").jsonPrimitive.boolean)
        }

    @Test
    fun singleItemRouteNeedsTheHeaderAndAnswersNotFoundForUnknownIds() =
        testApplication {
            install()
            val id = client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val without = client.signedGet("/api/v1/media/$id")
            assertEquals(HttpStatusCode.Unauthorized, without.status)

            val with = client.signedGet("/api/v1/media/$id", HttpHeaders.Authorization to "Bearer $GUEST_TOKEN")
            assertEquals(HttpStatusCode.OK, with.status)
            assertEquals(id, Json.parseToJsonElement(with.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)

            val unknown =
                client.signedGet("/api/v1/media/mediaZZZZZZZZZZZZZZZZZZZZ", HttpHeaders.Authorization to "Bearer $GUEST_TOKEN")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }

    @Test
    fun spaceInfoCarriesRevisionLimitsAndNoEndWithoutOne() =
        testApplication {
            install()
            val before = Json.parseToJsonElement(
                client.signedGet("/api/v1/space", HttpHeaders.Authorization to "Bearer $GUEST_TOKEN").bodyAsText(),
            ).jsonObject
            client.upload(MediaFixtures.jpeg(300), "bild.jpg")

            val after =
                client.signedGet("/api/v1/space", HttpHeaders.Authorization to "Bearer $GUEST_TOKEN").bodyAsText().let {
                    Json.parseToJsonElement(it).jsonObject
                }
            assertTrue(after.getValue("revision").jsonPrimitive.long > before.getValue("revision").jsonPrimitive.long)
            assertEquals(JsonNull, after.getValue("remainingSeconds"))
            assertEquals(JsonNull, after.getValue("endsAt"))
            val limits = after.getValue("limits").jsonObject
            assertEquals(2_000L, limits.getValue("imageMaxBytes").jsonPrimitive.long)
            assertEquals(6_000L, limits.getValue("videoMaxBytes").jsonPrimitive.long)
        }

    private suspend fun HttpResponse.bodyAsBytesSize(): Int = bodyAsBytes().size

    private companion object {
        const val HOST_TOKEN = "tok-host-0123456789-signed"
        const val GUEST_TOKEN = "tok-guest-0123456789-signed"
    }
}
