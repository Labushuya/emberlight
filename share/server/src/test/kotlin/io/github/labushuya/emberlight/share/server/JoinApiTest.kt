package io.github.labushuya.emberlight.share.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.ParticipantRoster
import io.github.labushuya.emberlight.share.space.participants.Secrets
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * Beitritt, `/me` und die Gastgeber-Routen über die Ktor-Testumgebung mit echter Datenbank (ADR 0018). Die Drosselung läuft
 * mit fester Uhr, keine echten Wartezeiten.
 */
@RunWith(RobolectricTestRunner::class)
class JoinApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var access: SpaceAccess
    private val spaceId = SpaceId.generate()
    private val hostToken = "tok-host-0123456789-abcdef-host"
    private var clock = 0L
    private val limiter = JoinLimiter(nowMillis = { clock })
    private val gates = ServerGates()

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        val vault = SpaceVault.open(database, spacesDir, spaceId, LIMITS, StorageProbe { Long.MAX_VALUE / 4 })
        val invite =
            runBlocking {
                database.spaces().upsert(
                    SpaceEntity(
                        id = spaceId.value,
                        name = "Test",
                        colorIndex = 1,
                        coverSha256 = null,
                        lifetimePreset = null,
                        lifetimeUntil = null,
                        retentionDays = 7,
                        storageLimitBytes = LIMITS.storageLimitBytes,
                        status = "ACTIVE",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
                vault.ensureHost(Secrets.hashHex(hostToken))
                vault.ensureInvite(null)
            }
        access =
            SpaceAccess(
                vault = vault,
                spaceId = spaceId,
                spaceName = "Test-Space",
                live = LiveAuthorizer(runBlocking { vault.credentials() }),
                uploads = ActiveUploads(),
                invite = invite,
                hostToken = hostToken,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun ApplicationTestBuilder.install() {
        application {
            installEmberlightModule(
                ServerEnvironment(
                    authorizer = access.live,
                    expectedHost = { "localhost" },
                    senderAllowed = { true },
                    gates = gates,
                    requestLog = {},
                    debugLogging = false,
                    closure = SpaceClosure.Open,
                    joinLimiter = limiter,
                ),
                routes = spaceRoutes(access, gates, UploadTuning(pollMillis = 1)),
            )
        }
    }

    private suspend fun HttpClient.join(
        secret: String,
        name: String,
    ): HttpResponse =
        post("/api/v1/join") {
            header(HttpHeaders.Host, "localhost")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("inviteSecret", secret)
                    put("displayName", name)
                }.toString(),
            )
        }

    private suspend fun HttpClient.api(
        method: String,
        path: String,
        token: String,
    ): HttpResponse {
        val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return when (method) {
            "GET" -> get(path, builder)
            "DELETE" -> delete(path, builder)
            "POST" -> post(path, builder)
            else -> error("Methode $method")
        }
    }

    private suspend fun json(response: HttpResponse): JsonObject =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    /** Tritt bei und liefert die Antwort-Daten. */
    private suspend fun HttpClient.joinOk(name: String): JsonObject {
        val response = join(currentInvite(), name)
        assertEquals(HttpStatusCode.Created, response.status)
        return json(response)
    }

    private fun currentInvite(): String = access.inviteSecret

    @Test
    fun correctInviteJoinsAndTheReturnedTokenAuthenticates() =
        testApplication {
            install()

            val body = client.joinOk("Anna")

            assertEquals("Anna", body.getValue("displayName").jsonPrimitive.content)
            assertEquals("GUEST", body.getValue("role").jsonPrimitive.content)
            assertEquals(spaceId.value, body.getValue("spaceId").jsonPrimitive.content)
            val token = body.getValue("token").jsonPrimitive.content
            assertTrue(Secrets.isWellFormed(token))

            val me = client.api("GET", "/api/v1/me", token)
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("Anna", json(me).getValue("displayName").jsonPrimitive.content)
        }

    @Test
    fun joinRequiresJsonContentType() =
        testApplication {
            install()

            val response =
                client.post("/api/v1/join") {
                    header(HttpHeaders.Host, "localhost")
                    contentType(ContentType.Text.Plain)
                    setBody("""{"inviteSecret":"${currentInvite()}","displayName":"Anna"}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("unsupported_media_type", json(response).getValue("error").jsonPrimitive.content)
        }

    @Test
    fun wrongInviteIsForbiddenAndTenFailuresBlockTheAddressUntilTheWindowPasses() =
        testApplication {
            install()

            repeat(JoinLimiter.PER_ADDRESS) {
                val response = client.join(Secrets.generate(), "Anna")
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals("invite_invalid", json(response).getValue("error").jsonPrimitive.content)
            }
            val blocked = client.join(currentInvite(), "Anna")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("rate_limited", json(blocked).getValue("error").jsonPrimitive.content)
            assertTrue(blocked.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)

            clock = JoinLimiter.WINDOW_MILLIS
            assertEquals(HttpStatusCode.Created, client.join(currentInvite(), "Anna").status)
        }

    @Test
    fun invalidNameIsRefusedWithAReason() =
        testApplication {
            install()

            val empty = client.join(currentInvite(), "   ")
            assertEquals(HttpStatusCode.UnprocessableEntity, empty.status)
            assertEquals("empty", json(empty).getValue("reason").jsonPrimitive.content)

            val bidi = client.join(currentInvite(), "Anna‮")
            assertEquals("forbidden_character", json(bidi).getValue("reason").jsonPrimitive.content)
        }

    @Test
    fun markupInANameIsRefusedAndNothingIsStored() =
        testApplication {
            install()

            val response = client.join(currentInvite(), "<img src=x onerror=alert(1)>")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(1, runBlocking { access.vault.participants() }.size)
        }

    @Test
    fun takenNameIsConflictWithASuggestionAndKeepsTheFirstGuest() =
        testApplication {
            install()
            client.joinOk("Anna")

            val response = client.join(currentInvite(), "ANNA")

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = json(response)
            assertEquals("name_taken", body.getValue("error").jsonPrimitive.content)
            assertEquals("ANNA 2", body.getValue("suggestion").jsonPrimitive.content)
        }

    @Test
    fun rotatingTheInviteInvalidatesTheOldCodeAndKeepsJoinedGuests() =
        testApplication {
            install()
            val oldInvite = currentInvite()
            val anna = client.joinOk("Anna")
            val annaToken = anna.getValue("token").jsonPrimitive.content

            val rotated = client.api("POST", "/api/v1/invite/rotate", hostToken)
            assertEquals(HttpStatusCode.OK, rotated.status)
            val fresh = json(rotated).getValue("invite").jsonPrimitive.content

            assertEquals(HttpStatusCode.Forbidden, client.join(oldInvite, "Ben").status)
            assertEquals(HttpStatusCode.Created, client.join(fresh, "Ben").status)
            assertEquals(HttpStatusCode.OK, client.api("GET", "/api/v1/me", annaToken).status)
        }

    @Test
    fun guestMayNotListParticipantsOrRotateTheInvite() =
        testApplication {
            install()
            val guest =
                client
                    .joinOk("Anna")
                    .getValue("token")
                    .jsonPrimitive.content

            assertEquals(HttpStatusCode.Forbidden, client.api("GET", "/api/v1/participants", guest).status)
            assertEquals(HttpStatusCode.Forbidden, client.api("POST", "/api/v1/invite/rotate", guest).status)
        }

    @Test
    fun hostListsParticipantsAndTheGuestLeavesWithDeleteMe() =
        testApplication {
            install()
            val guest =
                client
                    .joinOk("Anna")
                    .getValue("token")
                    .jsonPrimitive.content

            val list = json(client.api("GET", "/api/v1/participants", hostToken))
            assertEquals(2, list.getValue("items").jsonArray.size)

            assertEquals(HttpStatusCode.NoContent, client.api("DELETE", "/api/v1/me", guest).status)
            assertEquals(HttpStatusCode.Unauthorized, client.api("GET", "/api/v1/me", guest).status)
        }

    @Test
    fun hostCannotLeaveOrBeRemoved() =
        testApplication {
            install()
            val hostId = runBlocking { access.vault.credentials() }.single { it.role.name == "HOST" }.participantId

            assertEquals(HttpStatusCode.Forbidden, client.api("DELETE", "/api/v1/me", hostToken).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.api("DELETE", "/api/v1/participants/${hostId.value}", hostToken).status,
            )
        }

    @Test
    fun removingAGuestKeepsItsUploadsMarkedAsRemoved() =
        testApplication {
            install()
            val guest = client.joinOk("Anna")
            val token = guest.getValue("token").jsonPrimitive.content
            val upload =
                client.post("/api/v1/media") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Emberlight-Filename", "bild.jpg")
                    contentType(ContentType.Application.OctetStream)
                    setBody(MediaFixtures.jpeg(300))
                }
            assertEquals(HttpStatusCode.Created, upload.status)
            val guestId = guest.getValue("participantId").jsonPrimitive.content

            val removed = client.api("DELETE", "/api/v1/participants/$guestId?deleteUploads=false", hostToken)

            assertEquals(HttpStatusCode.NoContent, removed.status)
            val items = json(client.api("GET", "/api/v1/media", hostToken)).getValue("items").jsonArray
            assertEquals(1, items.size)
            assertEquals(
                "true",
                items
                    .single()
                    .jsonObject
                    .getValue("uploaderRemoved")
                    .toString(),
            )
            assertEquals(HttpStatusCode.Unauthorized, client.api("GET", "/api/v1/me", token).status)
        }

    @Test
    fun removingAGuestWithUploadsDeletesThemToo() =
        testApplication {
            install()
            val guest = client.joinOk("Anna")
            val token = guest.getValue("token").jsonPrimitive.content
            client.post("/api/v1/media") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Emberlight-Filename", "bild.jpg")
                contentType(ContentType.Application.OctetStream)
                setBody(MediaFixtures.jpeg(300))
            }
            val guestId = guest.getValue("participantId").jsonPrimitive.content

            client.api("DELETE", "/api/v1/participants/$guestId?deleteUploads=true", hostToken)

            assertEquals(0, json(client.api("GET", "/api/v1/media", hostToken)).getValue("items").jsonArray.size)
        }

    @Test
    fun aFullSpaceRefusesTheFiftyFirstParticipant() =
        testApplication {
            install()
            repeat(ParticipantRoster.MAX_PARTICIPANTS - 1) { index -> client.joinOk("Gast ${index + 1}") }

            val response = client.join(currentInvite(), "Überzählig")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("space_full", json(response).getValue("error").jsonPrimitive.content)
        }

    @Test
    fun malformedBodyIsBadRequest() =
        testApplication {
            install()

            val response =
                client.post("/api/v1/join") {
                    header(HttpHeaders.Host, "localhost")
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Anna"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    private companion object {
        val LIMITS =
            SpaceLimits(
                imageMaxBytes = 2_000,
                videoMaxBytes = 6_000,
                storageLimitBytes = 1_000_000,
                minDeviceReserveBytes = 0,
            )
    }
}
