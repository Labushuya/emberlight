package io.github.labushuya.emberlight.share.space.participants

import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.BytesSource
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Beitritt, Einladung, Entfernen und Obergrenze über die Vault (ADR 0018). Echte Datenbank, keine Netzwerkschicht. */
@RunWith(RobolectricTestRunner::class)
class ParticipantRosterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private var clock = 1_000L
    private val space = SpaceId.generate()
    private val vault by lazy {
        SpaceVault(
            database,
            SpaceStorage(spacesDir, space),
            LIMITS,
            StorageProbe {
                Long.MAX_VALUE /
                    4
            },
        ) { clock++ }
    }

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext(), spacesDir)
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = space.value,
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
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun joinedOrFail(outcome: JoinOutcome): JoinOutcome.Joined {
        assertTrue("Erwartet Joined, war $outcome", outcome is JoinOutcome.Joined)
        return outcome as JoinOutcome.Joined
    }

    private fun invite(): String = runBlocking { vault.ensureInvite(null) }

    private fun join(
        secret: String,
        name: String,
    ): JoinOutcome = runBlocking { vault.join(secret, name) }

    @Test
    fun joinWithoutAnInviteIsRefused() {
        assertEquals(JoinOutcome.InviteInvalid, join(Secrets.generate(), "Anna"))
    }

    @Test
    fun malformedAndWrongSecretsAreInviteInvalid() {
        invite()
        assertEquals(JoinOutcome.InviteInvalid, join("", "Anna"))
        assertEquals(JoinOutcome.InviteInvalid, join(Secrets.generate(), "Anna"))
    }

    @Test
    fun correctSecretJoinsWithANormalizedNameAndATokenOnlyItsHashIsStored() {
        val secret = invite()
        val joined = joinedOrFail(join(secret, "  Anna   Maria "))

        assertEquals("Anna Maria", joined.displayName)
        assertEquals(Role.GUEST, joined.role)
        assertTrue(Secrets.isWellFormed(joined.token))
        val credential = runBlocking { vault.credentials() }.single { it.participantId == joined.participantId }
        assertEquals(Secrets.hashHex(joined.token), credential.tokenHash)
        assertFalse(credential.tokenHash.contains(joined.token))
    }

    @Test
    fun invalidNameIsReportedWithItsReason() {
        val secret = invite()
        assertEquals(JoinOutcome.NameInvalid(ParticipantName.Reason.EMPTY), join(secret, "   "))
        assertEquals(
            JoinOutcome.NameInvalid(ParticipantName.Reason.FORBIDDEN_CHARACTER),
            join(secret, "Anna‮"),
        )
    }

    @Test
    fun nameIsUniqueCaseInsensitiveAndTheSuggestionIsFree() {
        val secret = invite()
        joinedOrFail(join(secret, "Anna"))
        assertEquals(JoinOutcome.NameTaken("ANNA 2"), join(secret, "ANNA"))
        joinedOrFail(join(secret, "Anna 2"))
        assertEquals(JoinOutcome.NameTaken("anna 3"), join(secret, "anna"))
    }

    @Test
    fun rotatingTheInviteInvalidatesOldCodesButKeepsJoinedGuests() {
        val old = invite()
        val first = joinedOrFail(join(old, "Ben"))

        val fresh = runBlocking { vault.rotateInvite() }

        assertEquals(JoinOutcome.InviteInvalid, join(old, "Clara"))
        joinedOrFail(join(fresh, "Clara"))
        assertTrue(runBlocking { vault.credentials() }.any { it.participantId == first.participantId })
    }

    @Test
    fun ensureInviteKeepsAKnownSecretAndReplacesAnUnknownOne() {
        val secret = invite()
        assertEquals(secret, runBlocking { vault.ensureInvite(secret) })
        val replaced = runBlocking { vault.ensureInvite(Secrets.generate()) }
        assertFalse(replaced == secret)
        assertEquals(JoinOutcome.InviteInvalid, join(secret, "Dora"))
    }

    @Test
    fun removedGuestLosesCredentialAndNameIsFreeAgain() {
        val secret = invite()
        val guest = joinedOrFail(join(secret, "Eva"))

        assertEquals(
            RemoveOutcome.Removed,
            runBlocking { vault.removeParticipant(guest.participantId, deleteUploads = false) },
        )

        assertFalse(runBlocking { vault.credentials() }.any { it.participantId == guest.participantId })
        assertNull(runBlocking { vault.participant(guest.participantId) })
        joinedOrFail(join(secret, "Eva"))
    }

    @Test
    fun removingAnUnknownOrRemovedParticipantIsNotFound() {
        val secret = invite()
        val guest = joinedOrFail(join(secret, "Fynn"))
        runBlocking { vault.removeParticipant(guest.participantId, deleteUploads = false) }

        assertEquals(
            RemoveOutcome.NotFound,
            runBlocking {
                vault.removeParticipant(guest.participantId, deleteUploads = false)
            },
        )
        assertEquals(
            RemoveOutcome.NotFound,
            runBlocking {
                vault.removeParticipant(ParticipantId.generate(), deleteUploads = false)
            },
        )
    }

    @Test
    fun hostCannotRemoveItself() {
        val host = runBlocking { vault.ensureHost(Secrets.hashHex("token")) }
        assertEquals(
            RemoveOutcome.HostCannotBeRemoved,
            runBlocking { vault.removeParticipant(host, deleteUploads = false) },
        )
        assertNotNull(runBlocking { vault.participant(host) })
    }

    @Test
    fun hostIsCreatedOnceAndKeepsItsIdWhileTheTokenHashChanges() {
        val first = runBlocking { vault.ensureHost(Secrets.hashHex("one")) }
        val second = runBlocking { vault.ensureHost(Secrets.hashHex("two")) }
        assertEquals(first, second)
        val host = runBlocking { vault.credentials() }.single { it.role == Role.HOST }
        assertEquals(Secrets.hashHex("two"), host.tokenHash)
    }

    @Test
    fun fiftyActiveParticipantsIncludingTheHostAreTheLimit() {
        runBlocking { vault.ensureHost(Secrets.hashHex("host")) }
        val secret = invite()
        repeat(ParticipantRoster.MAX_PARTICIPANTS - 1) { index ->
            joinedOrFail(join(secret, "Gast ${index + 1}"))
        }
        assertEquals(ParticipantRoster.MAX_PARTICIPANTS, runBlocking { vault.participants() }.size)
        assertEquals(JoinOutcome.SpaceFull, join(secret, "Überzählig"))
    }

    @Test
    fun removingWithUploadsDeletesThoseMediaAndTheFile() {
        runBlocking { vault.ensureHost(Secrets.hashHex("host")) }
        val secret = invite()
        val guest = joinedOrFail(join(secret, "Gina"))
        val principal = Principal(guest.participantId, Role.GUEST)
        val bytes = MediaFixtures.jpeg(100)
        val result = runBlocking { vault.ingest(principal, "bild.jpg", bytes.size.toLong(), BytesSource(bytes)) }
        assertTrue(result is IngestResult.Created)
        val mediaId: MediaId = (result as IngestResult.Created).record.id
        val fileCount = checkNotNull(File(spacesDir, space.value).listFiles()).size
        assertEquals(1, fileCount)

        assertEquals(
            RemoveOutcome.Removed,
            runBlocking { vault.removeParticipant(guest.participantId, deleteUploads = true) },
        )

        assertNull(runBlocking { vault.find(mediaId) })
        assertEquals(0, checkNotNull(File(spacesDir, space.value).listFiles()).size)
    }

    @Test
    fun removingWithoutDeletingKeepsUploadsWithTheRemovedFlag() {
        val secret = invite()
        val guest = joinedOrFail(join(secret, "Hanna"))
        val principal = Principal(guest.participantId, Role.GUEST)
        val bytes = MediaFixtures.jpeg(100)
        runBlocking { vault.ingest(principal, "bild.jpg", bytes.size.toLong(), BytesSource(bytes)) }

        runBlocking { vault.removeParticipant(guest.participantId, deleteUploads = false) }

        val page = runBlocking { vault.list(null, 10) }
        assertEquals(1, page.items.size)
        assertEquals("Hanna", page.items.single().uploaderName)
        assertTrue(page.items.single().uploaderRemoved)
    }

    private companion object {
        val LIMITS =
            SpaceLimits(
                imageMaxBytes = 1_000,
                videoMaxBytes = 5_000,
                storageLimitBytes = 1_000_000,
                minDeviceReserveBytes = 0,
            )
    }
}
