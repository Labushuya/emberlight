@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space.run

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows
import java.io.File

/**
 * Gastgeber-Ansicht des laufenden Space (Paket 3.3, ADR 0017): Unterbrechung, Fortsetzen, Ende, Netzwechsel, Pausen und das
 * Beenden eines abgelaufenen Space beim Öffnen. Der Dienst ist nicht gestartet, deshalb prüfen die Tests die Aufträge an ihn.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceRunningViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FixedClock(NOW)
    private val id = SpaceId.generate()
    private var wifi: WifiAddress? = WIFI
    private lateinit var database: SpacesDatabase
    private lateinit var runs: SpaceRunRepository
    private lateinit var collector: Job
    private var latest: SpaceRunningUiState = SpaceRunningUiState()
    private lateinit var spacesDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        runs = SpaceRunRepository(database, clock)
    }

    @After
    fun tearDown() {
        collector.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun anActiveSpaceWithoutAServerIsShownAsInterrupted() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            val vm = viewModel()

            awaitState { it.loaded }

            assertTrue(vm.state.value.interrupted)
            assertEquals(
                SpaceStatus.ACTIVE,
                vm.state.value.record
                    ?.status,
            )
            assertNull(Shadows.shadowOf(context as Application).nextStartedService)
        }

    @Test
    fun anEndedSpaceIsNotShownAsInterrupted() =
        runBlocking {
            seed(SpaceStatus.ENDED, expiresAt = NOW + HOUR)
            viewModel()

            awaitState { it.loaded }

            assertFalse(latest.interrupted)
        }

    @Test
    fun anExpiredSpaceOnOpenIsHandedToTheServiceToEnd() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW - 1)
            viewModel()

            val started = nextStartedService { it.action == SpaceServerService.ACTION_EXPIRE }

            assertEquals(SpaceServerService.ACTION_EXPIRE, started.action)
        }

    @Test
    fun aSpaceThatIsNotExpiredIsLeftAloneOnOpen() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            viewModel()

            awaitState { it.loaded }

            assertNull(Shadows.shadowOf(context as Application).nextStartedService)
        }

    @Test
    fun endingForwardsTheStopCommandToTheService() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            val vm = viewModel()
            awaitState { it.loaded }

            vm.end()

            assertEquals(SpaceServerService.ACTION_STOP, nextStartedService().action)
        }

    @Test
    fun confirmingANewNetworkForwardsTheCommand() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            val vm = viewModel()
            awaitState { it.loaded }

            vm.confirmNetwork()

            assertEquals(SpaceServerService.ACTION_CONFIRM_NETWORK, nextStartedService().action)
        }

    @Test
    fun resumingWithoutWifiReportsTheReasonAndKeepsTheSpaceActive() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            wifi = null
            val vm = viewModel()
            awaitState { it.loaded }

            vm.resume()
            awaitState { it.resumeFailure == StartOutcome.NoWifi }

            assertEquals(SpaceStatus.ACTIVE.name, database.spaces().find(id.value)?.status)
            assertNull(Shadows.shadowOf(context as Application).nextStartedService)
        }

    @Test
    fun resumingWithWifiStartsTheServiceInResumeMode() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            val vm = viewModel()
            awaitState { it.loaded }

            vm.resume()
            val started = nextStartedService { it.action == SpaceServerService.ACTION_START }

            assertTrue(started.getBooleanExtra(SpaceServerService.EXTRA_RESUME, false))
            assertEquals(id.value, started.getStringExtra(SpaceServerService.EXTRA_SPACE_ID))
            assertNull(latest.resumeFailure)
        }

    @Test
    fun aPauseIsShownUntilTheHostAcknowledgesIt() =
        runBlocking {
            seed(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR)
            runs.recordPause(
                id,
                PauseEvent(startedAtMillis = NOW - MINUTE, durationMillis = 10 * MINUTE, deepSleepMillis = 0),
            )
            val vm = viewModel()

            awaitState { it.pause != null }
            vm.acknowledgePauses()
            awaitState { it.pause == null }

            assertTrue(
                database
                    .pauses()
                    .observeOf(id.value)
                    .first()
                    .all { it.acknowledged },
            )
        }

    private fun viewModel(): SpaceRunningViewModel {
        val vm =
            SpaceRunningViewModel(
                savedStateHandle = SavedStateHandle(mapOf("id" to id.value)),
                spaces = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { FREE_BYTES }),
                runs = runs,
                controller = SpaceRunController(context),
                starter = SpaceStarter(context, runs, StorageProbe { FREE_BYTES }, FixedWifi { wifi }),
                clock = clock,
                hostActions = SpaceHostActions(context),
            )
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect { latest = it } }
        return vm
    }

    private suspend fun seed(
        status: SpaceStatus,
        expiresAt: Long?,
    ) {
        database.spaces().upsert(
            SpaceEntity(
                id = id.value,
                name = "Test",
                colorIndex = 0,
                coverSha256 = null,
                lifetimePreset = null,
                lifetimeUntil = expiresAt ?: (NOW + HOUR),
                retentionDays = 7,
                storageLimitBytes = GIB,
                status = status.name,
                createdAt = NOW - DAY,
                updatedAt = NOW - DAY,
                startedAt = NOW - MINUTE,
                expiresAt = expiresAt,
            ),
        )
    }

    /** Wartet, bis der letzte Zustand die Bedingung erfüllt. Die Zeitgrenze fängt einen ausbleibenden Schritt ab. */
    private suspend fun awaitState(condition: (SpaceRunningUiState) -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition(latest)) delay(POLL_MS)
        }
    }

    /** Der nächste Auftrag an den Dienst, der die Bedingung erfüllt. Ohne Treffer scheitert der Test nach der Zeitgrenze. */
    private suspend fun nextStartedService(matches: (Intent) -> Boolean = { true }): Intent {
        val shadow = Shadows.shadowOf(context as Application)
        var found: Intent? = null
        withTimeout(TIMEOUT_MS) {
            while (found == null) {
                val intent = shadow.nextStartedService
                if (intent == null) {
                    delay(POLL_MS)
                } else if (matches(intent)) {
                    found = intent
                }
            }
        }
        return checkNotNull(found)
    }

    private class FixedClock(
        private val wall: Long,
    ) : RunClock {
        override fun wallMillis(): Long = wall

        override fun elapsedMillis(): Long = wall

        override fun uptimeMillis(): Long = wall
    }

    private class FixedWifi(
        private val current: () -> WifiAddress?,
    ) : WifiNetworkProvider {
        override fun currentAddress(): WifiAddress? = current()

        override fun changes(): Flow<WifiAddress?> = flowOf(current())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val GIB = 1024L * 1024L * 1024L
        const val FREE_BYTES = 100 * GIB
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        val WIFI = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
    }
}
