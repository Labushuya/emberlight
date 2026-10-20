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
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import java.security.MessageDigest

/**
 * API v1 über die Ktor-Testumgebung mit echtem Index und Speicher (ADR 0014). Teilnehmer: Gastgeber, zwei Gäste.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var vault: SpaceVault
    private lateinit var spacesDir: File
    private val spaceId = SpaceId.generate()
    private val host = Principal(ParticipantId.generate(), Role.HOST)
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val otherGuest = Principal(ParticipantId.generate(), Role.GUEST)
    private val tokens =
        mapOf("tok-host-0123456789" to host, "tok-guest-0123456789" to guest, "tok-other-0123456789" to otherGuest)
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
        vault = SpaceVault.open(database, spacesDir, spaceId, limits, StorageProbe { Long.MAX_VALUE / 4 })
        runBlocking {
            vault.registerParticipant(host.participantId, "Gastgeberin", Role.HOST, "0".repeat(64))
            vault.registerParticipant(guest.participantId, "Gast", Role.GUEST, "0".repeat(64))
            vault.registerParticipant(otherGuest.participantId, "Anderer Gast", Role.GUEST, "0".repeat(64))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun ApplicationTestBuilder.install() {
        val authorizer = TokenAuthorizer(tokens.mapKeys { sha(it.key) })
        application {
            installEmberlightModule(
                ServerEnvironment(
                    authorizer = authorizer,
                    expectedHost = { "localhost" },
                    senderAllowed = { true },
                    gates = gates,
                    requestLog = { logLines += it },
                    closure = SpaceClosure.Open,
                    debugLogging = false,
                ),
                routes = spaceRoutes(vault, "Test-Space", gates, UploadTuning(pollMillis = 1)),
            )
        }
    }

    private fun sha(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpClient.upload(
        token: String,
        bytes: ByteArray,
        name: String = "bild.jpg",
        type: ContentType = ContentType.Application.OctetStream,
    ): HttpResponse =
        post("/api/v1/media") {
            auth(token)
            header("X-Emberlight-Filename", name)
            header(HttpHeaders.ContentType, type.toString())
            setBody(bytes)
        }

    private suspend fun json(response: HttpResponse) = Json.parseToJsonElement(response.bodyAsText()).jsonObject

    @Test
    fun uploadReturns201WithDetectedTypeAndId() =
        testApplication {
            install()

            val response = client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300))

            assertEquals(HttpStatusCode.Created, response.status)
            val body = json(response)
            assertEquals("image/jpeg", body.getValue("type").jsonPrimitive.content)
            assertEquals(300L, body.getValue("size").jsonPrimitive.long)
            assertEquals(false, body.getValue("duplicate").jsonPrimitive.boolean)
            assertEquals(
                22,
                body
                    .getValue("id")
                    .jsonPrimitive.content.length,
            )
        }

    @Test
    fun identicalContentReturns200DuplicateWithTheFirstId() =
        testApplication {
            install()
            val first = json(client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300)))

            val second = client.upload("tok-host-0123456789", MediaFixtures.jpeg(300))

            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(first.getValue("id"), json(second).getValue("id"))
            assertEquals(true, json(second).getValue("duplicate").jsonPrimitive.boolean)
        }

    @Test
    fun wrongContentTypeIsUnsupportedMediaType() =
        testApplication {
            install()

            val response = client.upload("tok-guest-0123456789", MediaFixtures.jpeg(), type = ContentType.Text.Plain)

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }

    @Test
    fun forbiddenContentIsUnsupportedMediaTypeAndLeavesNoFile() =
        testApplication {
            install()

            val response = client.upload("tok-guest-0123456789", MediaFixtures.ascii("<html>Hallo</html>"))

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("unsupported_media_type", json(response).getValue("error").jsonPrimitive.content)
            assertEquals(0, File(spacesDir, spaceId.value).listFiles()?.size ?: 0)
        }

    @Test
    fun declaredLengthAboveTheLimitIs413WithTheLimit() =
        testApplication {
            install()

            val response = client.upload("tok-guest-0123456789", MediaFixtures.jpeg(2_500))

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(2_000L, json(response).getValue("limit").jsonPrimitive.long)
        }

    @Test
    fun badFilenameHeaderIsBadRequest() =
        testApplication {
            install()

            val response =
                client.post("/api/v1/media") {
                    auth("tok-guest-0123456789")
                    header("X-Emberlight-Filename", "%ZZ")
                    header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    setBody(MediaFixtures.jpeg())
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("bad_filename", json(response).getValue("error").jsonPrimitive.content)
        }

    @Test
    fun listIsPagedNewestFirstAndRejectsBadCursors() =
        testApplication {
            install()
            client.upload("tok-guest-0123456789", MediaFixtures.jpeg(100))
            client.upload("tok-host-0123456789", MediaFixtures.jpeg(101))
            client.upload("tok-other-0123456789", MediaFixtures.jpeg(102))

            val first = json(client.get("/api/v1/media?limit=2") { auth("tok-guest-0123456789") })
            val cursor = first.getValue("nextCursor").jsonPrimitive.content
            val second = json(client.get("/api/v1/media?limit=2&cursor=$cursor") { auth("tok-guest-0123456789") })

            assertEquals(2, first.getValue("items").let { (it as kotlinx.serialization.json.JsonArray).size })
            assertEquals(1, second.getValue("items").let { (it as kotlinx.serialization.json.JsonArray).size })
            assertEquals(
                "Anderer Gast",
                (first.getValue("items") as kotlinx.serialization.json.JsonArray)[0]
                    .jsonObject
                    .getValue("uploader")
                    .jsonPrimitive.content,
            )
            val bad = client.get("/api/v1/media?cursor=kaputt") { auth("tok-guest-0123456789") }
            assertEquals("bad_cursor", json(bad).getValue("error").jsonPrimitive.content)
            val tooLarge = client.get("/api/v1/media?limit=101") { auth("tok-guest-0123456789") }
            assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
        }

    @Test
    fun downloadCarriesNosniffContentTypeAndRfc6266Disposition() =
        testApplication {
            install()
            val id =
                json(client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300), name = "%C3%9Cbung.jpg"))
                    .getValue("id")
                    .jsonPrimitive.content

            val response = client.get("/api/v1/media/$id/content") { auth("tok-guest-0123456789") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertTrue(disposition.startsWith("attachment;"))
            assertTrue(disposition.contains("filename*=UTF-8''%C3%9Cbung.jpg"))
            assertEquals(300, response.bodyAsBytes().size)
        }

    @Test
    fun rangeRequestReturnsPartialContent() =
        testApplication {
            install()
            val id =
                json(
                    client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300)),
                ).getValue("id").jsonPrimitive.content

            val response =
                client.get("/api/v1/media/$id/content") {
                    auth("tok-guest-0123456789")
                    header(HttpHeaders.Range, "bytes=0-3")
                }

            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals(4, response.bodyAsBytes().size)
        }

    @Test
    fun downloadSendsExactlyOneAcceptRangesHeaderOn200And206() =
        testApplication {
            install()
            val id =
                json(
                    client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300)),
                ).getValue("id").jsonPrimitive.content

            val full = client.get("/api/v1/media/$id/content") { auth("tok-guest-0123456789") }
            val partial =
                client.get("/api/v1/media/$id/content") {
                    auth("tok-guest-0123456789")
                    header(HttpHeaders.Range, "bytes=0-99")
                }

            assertEquals(HttpStatusCode.OK, full.status)
            assertEquals(listOf("bytes"), full.headers.getAll(HttpHeaders.AcceptRanges).orEmpty())
            assertEquals(HttpStatusCode.PartialContent, partial.status)
            assertEquals(listOf("bytes"), partial.headers.getAll(HttpHeaders.AcceptRanges).orEmpty())
        }

    @Test
    fun unknownMediaIsNotFound() =
        testApplication {
            install()

            val response = client.get("/api/v1/media/${"A".repeat(22)}/content") { auth("tok-guest-0123456789") }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun deletionFollowsTheRoleRules() =
        testApplication {
            install()
            val ownId =
                json(
                    client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300)),
                ).getValue("id").jsonPrimitive.content
            val foreignId =
                json(
                    client.upload("tok-guest-0123456789", MediaFixtures.jpeg(301)),
                ).getValue("id").jsonPrimitive.content

            val foreign = client.delete("/api/v1/media/$foreignId") { auth("tok-other-0123456789") }
            val own = client.delete("/api/v1/media/$ownId") { auth("tok-guest-0123456789") }
            val byHost = client.delete("/api/v1/media/$foreignId") { auth("tok-host-0123456789") }
            val again = client.delete("/api/v1/media/$foreignId") { auth("tok-host-0123456789") }

            assertEquals(HttpStatusCode.Forbidden, foreign.status)
            assertEquals(HttpStatusCode.NoContent, own.status)
            assertEquals(HttpStatusCode.NoContent, byHost.status)
            assertEquals(HttpStatusCode.NotFound, again.status)
        }

    @Test
    fun uploadWithoutValidTokenIsUnauthorizedAndWritesNothing() =
        testApplication {
            install()

            val response =
                client.post("/api/v1/media") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    setBody(MediaFixtures.jpeg())
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, File(spacesDir, spaceId.value).listFiles()?.size ?: 0)
        }

    @Test
    fun parallelUploadLimitAnswers429WithRetryAfter() =
        testApplication {
            install()
            assertTrue(gates.tryEnterUpload(guest.participantId))
            assertTrue(gates.tryEnterUpload(guest.participantId))

            val response = client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300))

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            assertEquals("too_many_uploads", json(response).getValue("error").jsonPrimitive.content)
        }

    @Test
    fun spaceInfoReportsNameAndUsage() =
        testApplication {
            install()
            client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300))

            val body = json(client.get("/api/v1/space") { auth("tok-guest-0123456789") })

            assertEquals("Test-Space", body.getValue("name").jsonPrimitive.content)
            assertEquals(300L, body.getValue("usedBytes").jsonPrimitive.long)
            assertEquals(1_000_000L, body.getValue("storageLimitBytes").jsonPrimitive.long)
        }

    @Test
    fun responsesNeverContainCorsHeaders() =
        testApplication {
            install()

            val response = client.get("/api/v1/space") { auth("tok-guest-0123456789") }

            assertEquals(null, response.headers["Access-Control-Allow-Origin"])
        }

    @Test
    fun logUsesTemplatesAndNeverTokens() =
        testApplication {
            install()
            client.get("/api/v1/media/${"B".repeat(22)}/content") { auth("tok-guest-0123456789") }

            assertTrue(logLines.isNotEmpty())
            assertTrue(logLines.none { it.contains("tok-guest") || it.contains("BBBBBBBB") })
        }

    /** Ein HEIC lässt sich nicht bereinigen (ADR 0015). Ohne Einwilligung: 422 mit Grund, nichts gespeichert. */
    @Test
    fun locationNotRemovableIs422WithReasonAndStoresNothing() =
        testApplication {
            install()
            val heic = MediaFixtures.unremovableHeic()

            val response = client.upload("tok-guest-0123456789", heic, name = "foto.heic")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = json(response)
            assertEquals("location_not_removable", body.getValue("error").jsonPrimitive.content)
            assertEquals("unsupported", body.getValue("reason").jsonPrimitive.content)
            val list = json(client.get("/api/v1/media") { auth("tok-guest-0123456789") })
            assertEquals(0, list.getValue("items").jsonArray.size)
        }

    /** Mit Einwilligung wird die Datei unverändert gespeichert und als „Standort enthalten“ markiert. */
    @Test
    fun keepLocationHeaderStoresTheFileUnchangedAndMarksIt() =
        testApplication {
            install()
            val heic = MediaFixtures.unremovableHeic()

            val response =
                client.post("/api/v1/media") {
                    auth("tok-guest-0123456789")
                    header("X-Emberlight-Filename", "foto.heic")
                    header("X-Emberlight-Keep-Location", "1")
                    setBody(heic)
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(true, json(response).getValue("locationKept").jsonPrimitive.boolean)
            val id = json(response).getValue("id").jsonPrimitive.content
            val list = json(client.get("/api/v1/media") { auth("tok-guest-0123456789") })
            val item =
                list
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(true, item.getValue("locationKept").jsonPrimitive.boolean)
            assertEquals(id, item.getValue("id").jsonPrimitive.content)
            val download = client.get("/api/v1/media/$id/content") { auth("tok-guest-0123456789") }
            assertTrue(heic.contentEquals(download.bodyAsBytes()))
        }

    /** Der Einwilligungs-Header akzeptiert nur den Wert `1`. Alles andere ist ein Fehler. */
    @Test
    fun keepLocationHeaderWithAnotherValueIsBadRequest() =
        testApplication {
            install()

            val response =
                client.post("/api/v1/media") {
                    auth("tok-guest-0123456789")
                    header("X-Emberlight-Filename", "bild.jpg")
                    header("X-Emberlight-Keep-Location", "ja")
                    setBody(MediaFixtures.jpeg(300))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("bad_request", json(response).getValue("error").jsonPrimitive.content)
        }

    @Test
    fun cleanedUploadIsNotMarkedAsLocationKept() =
        testApplication {
            install()
            client.upload("tok-guest-0123456789", MediaFixtures.jpeg(300))

            val list = json(client.get("/api/v1/media") { auth("tok-guest-0123456789") })

            assertEquals(
                false,
                list
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("locationKept")
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun deleteWithoutAuthorizationIsUnauthorized() =
        testApplication {
            install()

            val response = client.delete("/api/v1/media/${"C".repeat(22)}") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
