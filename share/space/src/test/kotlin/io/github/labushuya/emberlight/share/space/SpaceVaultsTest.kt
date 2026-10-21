package io.github.labushuya.emberlight.share.space

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.participants.FakeInviteSecretCipher
import io.github.labushuya.emberlight.share.space.preview.FakePreviewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Die eine Vault je Space im Prozess (Paket 3.5c, ADR 0019 Nachtrag 3.5c). Gleichzeitige Uploads von Gast und Gastgeber
 * laufen über dieselbe Sperre, der Gastgeber-Upload bekommt ohne Neustart eine Vorschau, und die Wiederaufnahme läuft
 * genau einmal pro Vault.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceVaultsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var renderer: FakePreviewRenderer
    private lateinit var vaults: SpaceVaults
    private val space = SpaceId.generate()
    private val host = Principal(ParticipantId.generate(), Role.HOST)
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val cipher = FakeInviteSecretCipher()

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        renderer = FakePreviewRenderer()
        vaults = SpaceVaults.create(database, spacesDir, renderer)
        runBlocking {
            database.spaces().upsert(spaceRow())
            val vault = vaults.vaultOf(space, cipher)!!
            vault.registerParticipant(host.participantId, "Gastgeber", Role.HOST, "1".repeat(64))
            vault.registerParticipant(guest.participantId, "Gast", Role.GUEST, "2".repeat(64))
        }
    }

    @After
    fun tearDown() {
        vaults.close()
        database.close()
    }

    private fun spaceRow() =
        SpaceEntity(
            id = space.value,
            name = "Test",
            colorIndex = 1,
            coverSha256 = null,
            lifetimePreset = null,
            lifetimeUntil = null,
            retentionDays = 7,
            storageLimitBytes = 1_000_000_000L,
            status = "ACTIVE",
            createdAt = 1L,
            updatedAt = 1L,
        )

    @Test
    fun theSameSpaceGetsTheSameVaultForServerAndApp() {
        val fromServer = runBlocking { vaults.vaultFor(space, SpaceLimits(), cipher) }
        val fromApp = runBlocking { vaults.vaultOf(space, cipher) }

        assertSame(fromServer, fromApp)
        assertSame(fromServer, runBlocking { vaults.vaultFor(space, SpaceLimits(), cipher) })
    }

    @Test
    fun vaultOfAnUnknownSpaceIsNull() {
        assertNull(runBlocking { vaults.vaultOf(SpaceId.generate(), cipher) })
    }

    @Test
    fun theLimitsOfTheStoredSpaceAreUsedByTheApp() {
        val other = SpaceId.generate()
        runBlocking { database.spaces().upsert(spaceRow().copy(id = other.value, storageLimitBytes = 4_000L)) }

        val vault = runBlocking { vaults.vaultOf(other, cipher)!! }

        assertEquals(4_000L, runBlocking { vault.usage() }.limitBytes)
    }

    @Test
    fun simultaneousGuestAndHostUploadsOfTheSameFileStoreItOnceWithoutARace() {
        val vault = runBlocking { vaults.vaultOf(space, cipher)!! }
        val bytes = MediaFixtures.jpeg(300)

        val results =
            runBlocking {
                coroutineScope {
                    listOf(guest, host, guest, host)
                        .map { who ->
                            async(Dispatchers.Default) {
                                vault.ingest(who, "bild.jpg", bytes.size.toLong(), BytesSource(bytes), false)
                            }
                        }.map { it.await() }
                }
            }

        assertEquals(1, results.count { it is IngestResult.Created })
        assertEquals(3, results.count { it is IngestResult.Duplicate })
        // Genau ein Medium im Index, also keine zweite Kopie durch den Wettlauf.
        assertEquals(1, runBlocking { vault.list(null, 50) }.items.size)
    }

    @Test
    fun simultaneousUploadsOfDifferentFilesAllLandAndCountOnce() {
        val vault = runBlocking { vaults.vaultOf(space, cipher)!! }
        val files = (1..6).map { MediaFixtures.jpeg(100 + it) }

        val results =
            runBlocking {
                coroutineScope {
                    files
                        .mapIndexed { index, bytes ->
                            async(Dispatchers.Default) {
                                val who = if (index % 2 == 0) host else guest
                                vault.ingest(who, "f$index.jpg", bytes.size.toLong(), BytesSource(bytes), false)
                            }
                        }.map { it.await() }
                }
            }

        assertTrue(results.all { it is IngestResult.Created })
        assertEquals(files.size, runBlocking { vault.list(null, 50) }.items.size)
    }

    @Test
    fun aHostUploadGetsItsPreviewWithoutARestart() {
        val vault = runBlocking { vaults.vaultOf(space, cipher)!! }

        val id =
            runBlocking {
                val result = vault.ingest(host, "gastgeber.jpg", 120L, BytesSource(MediaFixtures.jpeg(120)), false)
                (result as IngestResult.Created).record.id
            }

        // Der Worker der Vault läuft im Prozess, ohne Server. Er muss die Vorschau ohne Neustart fertigstellen.
        runBlocking {
            withTimeout(WAIT_MS) {
                while (vault.entry(id)?.previewState != "READY") {
                    delay(POLL_MS)
                }
            }
        }
        assertEquals("READY", runBlocking { vault.entry(id) }?.previewState)
    }

    @Test
    fun recoveryRunsOncePerVaultNotOncePerServerStart() {
        val first = runBlocking { vaults.vaultOf(space, cipher)!! }
        val staleTemp = File(spacesDir, "${space.value}/.tmp-laufend")
        staleTemp.parentFile!!.mkdirs()
        staleTemp.writeText("Teildatei eines laufenden Uploads")

        // Ein zweiter Serverstart holt dieselbe Vault und räumt dabei nichts weg.
        val again = runBlocking { vaults.vaultFor(space, SpaceLimits(), cipher) }

        assertSame(first, again)
        assertTrue(staleTemp.exists())
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }

    @Test
    fun aSpaceBeingDeletedOpensNoNewVaultForServerOrApp() {
        runBlocking {
            database.spaces().markPurging(space.value)
            vaults.forget(space)
        }

        assertNull(runBlocking { vaults.vaultOf(space, cipher) })
        assertThrows(SpaceNotOpenableException::class.java) {
            runBlocking { vaults.vaultFor(space, SpaceLimits(), cipher) }
        }
    }

    @Test
    fun aDeletedSpaceOpensNoVault() {
        runBlocking {
            vaults.forget(space)
            database.spaces().markDeleted(space.value, now = 9L)
        }

        assertNull(runBlocking { vaults.vaultOf(space, cipher) })
        assertThrows(SpaceNotOpenableException::class.java) {
            runBlocking { vaults.vaultFor(space, SpaceLimits(), cipher) }
        }
    }

    @Test
    fun anUploadIsRefusedWhileTheSpaceIsBeingDeleted() {
        val vault = runBlocking { vaults.vaultOf(space, cipher)!! }
        runBlocking { database.spaces().markPurging(space.value) }

        val result = runBlocking { vault.ingest(guest, "bild.jpg", null, BytesSource(MediaFixtures.jpeg(100))) }

        assertEquals(IngestResult.Rejected(RejectReason.SpaceDeleting), result)
        assertTrue(runBlocking { database.media().allOf(space.value) }.isEmpty())
    }
}
