@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space.run

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.media.HostMediaStore
import io.github.labushuya.emberlight.feature.space.media.HostMediaUiState
import io.github.labushuya.emberlight.feature.space.media.HostMediaViewModel
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.ChunkSource
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.Secrets
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * Der Gastgeber sieht Änderungen, die der Server schreibt, ohne die Ansicht neu zu öffnen (Fehlerbericht zu v0.3.0-alpha.1).
 * Der Server geht über dieselbe Vault und dieselbe Datenbank wie die Oberfläche (ADR 0016, ADR 0019). Beitritt und Upload
 * laufen hier über die Vault, also über denselben Weg wie die Routen des Servers.
 */
@RunWith(RobolectricTestRunner::class)
class HostLiveUpdatesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val space = SpaceId.generate()
    private val host = ParticipantId.generate()
    private val cipher = FakeInviteSecretCipher()
    private val clock =
        object : RunClock {
            override fun wallMillis(): Long = NOW

            override fun elapsedMillis(): Long = NOW

            override fun uptimeMillis(): Long = NOW
        }
    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var vaults: SpaceVaults
    private val collectors = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        vaults = SpaceVaults.create(database, spacesDir)
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = space.value,
                    name = "Test",
                    colorIndex = 0,
                    coverSha256 = null,
                    lifetimePreset = null,
                    lifetimeUntil = NOW + HOUR,
                    retentionDays = 7,
                    storageLimitBytes = GIB,
                    status = "ACTIVE",
                    createdAt = NOW - DAY,
                    updatedAt = NOW - DAY,
                    startedAt = NOW - MINUTE,
                    expiresAt = NOW + HOUR,
                ),
            )
            vaults.vaultOf(space, cipher)!!.registerParticipant(host, "Gastgeber", Role.HOST, Secrets.hashHex("h"))
        }
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        vaults.close()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun aGuestWhoJoinsAppearsInTheRunningSpaceWithoutReopeningIt() =
        runBlocking {
            val vault = vaults.vaultOf(space, cipher)!!
            val invite = vault.ensureInvite().secret
            val running = track(runningViewModel().state, SpaceRunningUiState())
            awaitUntil { running.value.loaded && running.value.participants.size == 1 }

            val outcome = vault.join(invite, "Anna")

            assertTrue(outcome is JoinOutcome.Joined)
            awaitUntil { running.value.participants.size == 2 }
            assertEquals(listOf("Gastgeber", "Anna"), running.value.participants.map { it.displayName })
        }

    @Test
    fun aGuestsUploadShowsTheGuestsNameInTheHostMediaGrid() =
        runBlocking {
            val vault = vaults.vaultOf(space, cipher)!!
            val joined = vault.join(vault.ensureInvite().secret, "Anna") as JoinOutcome.Joined
            val result =
                vault.ingest(
                    Principal(joined.participantId, Role.GUEST),
                    "bild.jpg",
                    JPEG.size.toLong(),
                    Bytes(JPEG),
                )
            assertTrue(result is IngestResult.Created)

            val media =
                HostMediaViewModel(
                    SpaceRunRepository(database, clock),
                    SpaceHostActions(vaults, cipher),
                    HostMediaStore(vaults, cipher),
                )
            media.show(space)
            val grid = track(media.state, HostMediaUiState())
            awaitUntil { grid.value.items.size == 1 }

            assertEquals(
                "Anna",
                grid.value.items
                    .single()
                    .uploaderName,
            )
        }

    @Test
    fun anOpenGridShowsTheNameOfAGuestWhoJoinedAndUploadedWhileItWatches() =
        runBlocking {
            val vault = vaults.vaultOf(space, cipher)!!
            val media =
                HostMediaViewModel(
                    SpaceRunRepository(database, clock),
                    SpaceHostActions(vaults, cipher),
                    HostMediaStore(vaults, cipher),
                )
            media.show(space)
            val grid = track(media.state, HostMediaUiState())
            awaitUntil { grid.value.loaded }

            val joined = vault.join(vault.ensureInvite().secret, "Anna") as JoinOutcome.Joined
            val result =
                vault.ingest(
                    Principal(joined.participantId, Role.GUEST),
                    "bild.jpg",
                    JPEG.size.toLong(),
                    Bytes(JPEG),
                )
            assertTrue(result is IngestResult.Created)

            awaitUntil { grid.value.items.size == 1 }
            assertEquals(
                "Anna",
                grid.value.items
                    .single()
                    .uploaderName,
            )
        }

    private fun runningViewModel(): SpaceRunningViewModel {
        val runs = SpaceRunRepository(database, clock)
        return SpaceRunningViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to space.value)),
            spaces = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { FREE_BYTES }),
            runs = runs,
            controller = SpaceRunController(context),
            starter = SpaceStarter(context, runs, StorageProbe { FREE_BYTES }, NoWifi),
            clock = clock,
            hostActions = SpaceHostActions(vaults, cipher),
        )
    }

    /** Sammelt den neuesten Wert eines Flows, damit der Test ihn pollen kann. */
    private fun <T> track(
        flow: Flow<T>,
        initial: T,
    ): Tracked<T> {
        val tracked = Tracked(initial)
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { tracked.value = it } }
        return tracked
    }

    private class Tracked<T>(
        initial: T,
    ) {
        @Volatile
        var value: T = initial
    }

    private suspend fun awaitUntil(check: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!check()) delay(POLL_MS)
        }
    }

    private object NoWifi : WifiNetworkProvider {
        override fun currentAddress(): WifiAddress? = null

        override fun changes(): Flow<WifiAddress?> = flowOf(null)
    }

    /** Liefert die Bytes in Stücken, so wie der Upload sie vom Client bekommt. */
    private class Bytes(
        private val data: ByteArray,
    ) : ChunkSource {
        private var position = 0

        override suspend fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= data.size) return -1
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val NOW = 1_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val GIB = 1_073_741_824L
        const val FREE_BYTES = 10 * GIB
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L

        /** Kleinstes JPEG mit Signatur und Endmarke. Reicht für Erkennung und Ablage, ohne Standort. */
        val JPEG =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xE0.toByte(),
                0x00,
                0x10,
                0x4A,
                0x46,
                0x49,
                0x46,
                0x00,
                0x01,
                0x01,
                0x00,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0xFF.toByte(),
                0xD9.toByte(),
            )
    }
}
