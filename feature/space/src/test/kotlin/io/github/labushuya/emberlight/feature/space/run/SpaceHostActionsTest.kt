package io.github.labushuya.emberlight.feature.space.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import io.github.labushuya.emberlight.share.space.participants.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Gastgeber-Aktionen ohne laufenden Server (ADR 0018): Gäste entfernen (mit und ohne Uploads), Einladung erneuern,
 * Selbst-Entfernen verhindern. Echte Datenbank, Vault über denselben Index.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceHostActionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val space = SpaceId.generate()
    private val hostToken = "tok-host-0123456789-abcdef-host"
    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var cipher: FakeInviteSecretCipher
    private lateinit var vault: SpaceVault
    private lateinit var vaults: SpaceVaults
    private lateinit var actions: SpaceHostActions
    private val host = ParticipantId.generate()
    private val guest = ParticipantId.generate()
    private val otherGuest = ParticipantId.generate()

    // Gültige Medien-IDs (ADR 0013): der Wiederaufnahme-Pfad prüft sie beim Lesen des Index.
    private val guestUpload = MediaId.generate().value
    private val otherUpload = MediaId.generate().value

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        cipher = FakeInviteSecretCipher()
        vaults = SpaceVaults.create(database, spacesDir)
        vault = openVault(vaults)
        actions = SpaceHostActions(vaults, cipher)
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
                    storageLimitBytes = SpaceLimits().storageLimitBytes,
                    status = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            vault.registerParticipant(host, "Gastgeber", Role.HOST, Secrets.hashHex(hostToken))
            vault.registerParticipant(guest, "Gast", Role.GUEST, "0".repeat(64))
            vault.registerParticipant(otherGuest, "Anderer Gast", Role.GUEST, "0".repeat(64))
            insertUpload(guestUpload, guest, "a")
            insertUpload(otherUpload, otherGuest, "b")
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Dieselbe Instanz, die Server und Oberfläche im Prozess teilen (Paket 3.5c). */
    private fun openVault(on: SpaceVaults): SpaceVault = runBlocking { on.vaultFor(space, SpaceLimits(), cipher) }

    private fun insertUpload(
        id: String,
        uploader: ParticipantId,
        hashChar: String,
    ) {
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id,
                    spaceId = space.value,
                    sha256 = hashChar.repeat(64),
                    mime = "image/jpeg",
                    sizeBytes = 10L,
                    originalName = "$id.jpg",
                    uploaderId = uploader.value,
                    createdAt = 1_000L,
                    state = "READY",
                ),
            )
        }
    }

    private fun uploadIds(): Set<String> =
        runBlocking {
            database
                .media()
                .allOf(space.value)
                .map { it.id }
                .toSet()
        }

    @Test
    fun removingAGuestKeepsTheirUploadsWhenNotAskedToDeleteThem() {
        val before = actions.changes().value

        val outcome = runBlocking { actions.removeGuest(space, guest, deleteUploads = false) }

        assertEquals(RemoveOutcome.Removed, outcome)
        assertEquals(setOf(guestUpload, otherUpload), uploadIds())
        assertEquals(
            "Der Upload bleibt als Upload eines entfernten Gastes erhalten.",
            guest.value,
            runBlocking { database.media().findById(guestUpload) }?.uploaderId,
        )
        assertEquals(before + 1, actions.changes().value)
        assertNull(runBlocking { vault.participant(guest) })
    }

    @Test
    fun removingAGuestWithDeleteUploadsAlsoRemovesTheirMediaAndNoOthers() {
        val outcome = runBlocking { actions.removeGuest(space, guest, deleteUploads = true) }

        assertEquals(RemoveOutcome.Removed, outcome)
        assertEquals(setOf(otherUpload), uploadIds())
        assertNull(runBlocking { database.media().findById(guestUpload) })
    }

    @Test
    fun theHostCannotRemoveItselfAndNothingChanges() {
        val before = actions.changes().value

        val outcome = runBlocking { actions.removeGuest(space, host, deleteUploads = true) }

        assertEquals(RemoveOutcome.HostCannotBeRemoved, outcome)
        assertEquals(setOf(guestUpload, otherUpload), uploadIds())
        assertTrue(runBlocking { actions.participants(space) }.any { it.id == host && it.role == Role.HOST })
        assertEquals(before, actions.changes().value)
    }

    @Test
    fun removingAnUnknownOrAlreadyRemovedGuestReportsNotFound() {
        assertEquals(
            RemoveOutcome.NotFound,
            runBlocking { actions.removeGuest(space, ParticipantId.generate(), deleteUploads = false) },
        )
        runBlocking { actions.removeGuest(space, guest, deleteUploads = false) }
        assertEquals(RemoveOutcome.NotFound, runBlocking { actions.removeGuest(space, guest, deleteUploads = false) })
    }

    @Test
    fun participantsListTheHostFirstAndWithoutRemovedGuests() {
        runBlocking { actions.removeGuest(space, guest, deleteUploads = false) }

        val listed = runBlocking { actions.participants(space) }

        assertEquals(host, listed.first().id)
        assertFalse(listed.any { it.id == guest })
        assertTrue(listed.any { it.id == otherGuest })
    }

    @Test
    fun rotatingTheInviteReplacesTheOldCodeAndKeepsJoinedGuests() {
        val old = runBlocking { vault.ensureInvite().secret }
        val joined = runBlocking { vault.join(old, "Ben") } as JoinOutcome.Joined
        val before = actions.changes().value

        val fresh = runBlocking { actions.rotateInvite(space) }

        assertFalse(fresh == old)
        assertEquals(before + 1, actions.changes().value)
        assertEquals(Secrets.hashHex(fresh), runBlocking { database.spaces().find(space.value) }?.inviteHash)
        assertEquals(JoinOutcome.InviteInvalid, runBlocking { vault.join(old, "Clara") })
        assertTrue(runBlocking { vault.join(fresh, "Clara") } is JoinOutcome.Joined)
        assertTrue(runBlocking { vault.credentials() }.any { it.participantId == joined.participantId })
    }

    @Test
    fun theRotatedInviteIsTheOneShownAfterARestart() {
        val fresh = runBlocking { actions.rotateInvite(space) }

        // Ein Neustart des Prozesses: eine frische Instanz auf derselben Datenbank.
        val afterRestart = runBlocking { openVault(SpaceVaults.create(database, spacesDir)).ensureInvite() }

        assertEquals(fresh, afterRestart.secret)
        assertFalse(afterRestart.renewed)
    }

    @Test
    fun noInviteIsReportedWhileNoServerRuns() {
        assertNull(actions.currentInvite(space))
    }
}
