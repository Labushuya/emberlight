package io.github.labushuya.emberlight.share.server

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.MemoryRunBeatStore
import io.github.labushuya.emberlight.share.space.run.RunBeat
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.SystemRunClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

/**
 * Integration des Dienstes unter Robolectric (ADR 0017): Intents, Vordergrund und Übergang in den Betrieb. Server, Sperren
 * und WLAN sind Fakes. Die Logik des Betriebs prüft [SpaceRunnerTest], hier geht es um die Verdrahtung.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceServerServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaceId = SpaceId.generate()
    private val locks = ServerLocks(FakeServerLock(), FakeServerLock())
    private var serverStarts = 0
    private val originalSetup = SpaceServerService.serverSetupProvider
    private val originalServer = SpaceServerService.serverFactory
    private val originalNetwork = SpaceServerService.networkFactory
    private val originalLocks = SpaceServerService.locksFactory
    private val originalForeground = SpaceServerService.foregroundStarter
    private val originalBeats = SpaceServerService.beatStoreFactory
    private val originalBattery = SpaceServerService.batteryExemptFactory
    private val originalCipher = SpaceServerService.inviteCipherFactory
    private var controller: ServiceController<SpaceServerService>? = null

    @Before
    fun setUp() {
        // Ab API 37 mit targetSdk 37 braucht der Start diese Berechtigung. Der Test erteilt sie, wie der Benutzer es täte.
        Shadows
            .shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(LOCAL_NETWORK_PERMISSION)
        SpaceServerService.serverSetupProvider =
            { ServerSetup(spaceId = spaceId, spaceName = "Test", debugLogging = false) }
        SpaceServerService.inviteCipherFactory = { InviteSecretCipherFake() }
        SpaceServerService.serverFactory = { _, _, _ ->
            serverStarts++
            FakeLocalServer()
        }
        SpaceServerService.networkFactory = { _ -> FixedNetwork(WIFI) }
        SpaceServerService.locksFactory = { _ -> locks }
        SpaceServerService.beatStoreFactory = { MemoryRunBeatStore() }
        SpaceServerService.batteryExemptFactory = { true }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        // Der Space dieses Tests endet, damit die geteilte Datenbank nach dem Test keinen aktiven Space mehr hat.
        runBlocking {
            SpaceRunRepository(SpacesDatabase.shared(context), SystemRunClock).end(spaceId, System.currentTimeMillis())
        }
        SpaceServerService.serverSetupProvider = originalSetup
        SpaceServerService.serverFactory = originalServer
        SpaceServerService.inviteCipherFactory = originalCipher
        SpaceServerService.networkFactory = originalNetwork
        SpaceServerService.locksFactory = originalLocks
        SpaceServerService.foregroundStarter = originalForeground
        SpaceServerService.beatStoreFactory = originalBeats
        SpaceServerService.batteryExemptFactory = originalBattery
    }

    @Test
    fun aStartRunsTheServerAndAStopEndsItAndReleasesTheLocks() =
        runBlocking {
            val service = create()

            service.onStartCommand(SpaceServerService.startIntent(context, spaceId, port = 0), 0, 1)
            awaitState<RunState.Running>()
            assertEquals(1, serverStarts)
            assertTrue((locks.cpuHeld && locks.wifiHeld))

            service.onStartCommand(SpaceServerService.stopIntent(context), 0, 2)
            awaitState<RunState.Idle>()
            assertTrue(!locks.cpuHeld && !locks.wifiHeld)
        }

    @Test
    fun aRefusedForegroundStartNeverRunsTheServer() =
        runBlocking {
            SpaceServerService.foregroundStarter =
                SpaceServerService.ForegroundStarter { _, _, _ ->
                    throw SecurityException("verweigert")
                }
            val service = create()

            service.onStartCommand(SpaceServerService.startIntent(context, spaceId, port = 0), 0, 1)

            assertEquals(RunState.Idle, SpaceServerService.runState.value)
            assertEquals(0, serverStarts)
        }

    @Test
    fun aStopWhileIdleDoesNotStartAnything() =
        runBlocking {
            val service = create()

            service.onStartCommand(SpaceServerService.stopIntent(context), 0, 1)

            assertEquals(RunState.Idle, SpaceServerService.runState.value)
            assertEquals(0, serverStarts)
        }

    @Test
    fun aRestartWithoutAStoredRunNeverEntersTheForeground() =
        runBlocking {
            var foregroundCalls = 0
            SpaceServerService.foregroundStarter =
                SpaceServerService.ForegroundStarter { _, _, _ -> foregroundCalls++ }
            val service = create()

            service.onStartCommand(null, 0, 1)

            assertEquals(0, foregroundCalls)
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
            assertEquals(0, serverStarts)
        }

    @Test
    fun aRestartResumesAnActiveSpaceAndRecordsTheInterruption() =
        runBlocking {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            seedActive(startedAt = now - 10 * MINUTE, expiresAt = now + HOUR)
            storeBeat(now - 10 * MINUTE, elapsed - 10 * MINUTE, now - 9 * MINUTE, elapsed - 9 * MINUTE)
            SpaceServerService.serverSetupProvider = { null }
            val service = create()

            service.onStartCommand(null, 0, 1)

            awaitState<RunState.Running>()
            assertEquals(1, serverStarts)
            val pause =
                SpacesDatabase
                    .shared(context)
                    .pauses()
                    .observeOf(spaceId.value)
                    .first()
                    .single()
            assertTrue(
                "Die Unterbrechung reicht vom letzten Herzschlag bis zum Neustart.",
                pause.durationMs >= 9 * MINUTE,
            )
        }

    @Test
    fun aRestartWithoutTheBatteryExemptionStaysIdleAndKeepsTheSpaceActive() =
        runBlocking {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            seedActive(startedAt = now - 10 * MINUTE, expiresAt = now + HOUR)
            storeBeat(now - 10 * MINUTE, elapsed - 10 * MINUTE, now - 9 * MINUTE, elapsed - 9 * MINUTE)
            SpaceServerService.serverSetupProvider = { null }
            SpaceServerService.batteryExemptFactory = { false }
            val service = create()

            service.onStartCommand(null, 0, 1)

            awaitTrue { Shadows.shadowOf(service).isStoppedBySelf }
            assertEquals(RunState.Idle, SpaceServerService.runState.value)
            assertEquals(0, serverStarts)
            assertEquals(
                SpaceStatus.ACTIVE.name,
                SpacesDatabase
                    .shared(context)
                    .spaces()
                    .find(spaceId.value)
                    ?.status,
            )
        }

    @Test
    fun aRestartOfAnExpiredRunEndsTheSpaceAtItsPlannedEnd() =
        runBlocking {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            seedActive(startedAt = now - 2 * HOUR, expiresAt = now - HOUR)
            storeBeat(now - 2 * HOUR, elapsed - 2 * HOUR, now - 90 * MINUTE, elapsed - 90 * MINUTE)
            SpaceServerService.serverSetupProvider = { null }
            val service = create()

            service.onStartCommand(null, 0, 1)

            awaitTrue {
                SpacesDatabase
                    .shared(context)
                    .spaces()
                    .find(spaceId.value)
                    ?.status == SpaceStatus.ENDED.name
            }
            assertEquals(
                now - HOUR,
                SpacesDatabase
                    .shared(context)
                    .spaces()
                    .find(spaceId.value)
                    ?.endedAt,
            )
            assertEquals(0, serverStarts)
        }

    /** Ein aktiver Space in der Datenbank, wie ihn der Start über die Oberfläche hinterlässt. */
    private fun seedActive(
        startedAt: Long,
        expiresAt: Long,
    ) {
        runBlocking {
            SpacesDatabase.shared(context).spaces().upsert(
                SpaceEntity(
                    id = spaceId.value,
                    name = "Test",
                    colorIndex = 0,
                    coverSha256 = null,
                    lifetimePreset = null,
                    lifetimeUntil = expiresAt,
                    retentionDays = 7,
                    storageLimitBytes = 1L,
                    status = SpaceStatus.ACTIVE.name,
                    createdAt = startedAt,
                    updatedAt = startedAt,
                    startedAt = startedAt,
                    expiresAt = expiresAt,
                ),
            )
        }
    }

    /** Legt den letzten Herzschlag in den Speicher, den der Dienst beim Neustart liest. */
    private fun storeBeat(
        startedWall: Long,
        startedElapsed: Long,
        lastWall: Long,
        lastElapsed: Long,
    ) {
        val store = MemoryRunBeatStore()
        store.save(RunBeat(spaceId, startedWall, startedElapsed, lastWall, lastElapsed, port = 0))
        SpaceServerService.beatStoreFactory = { store }
    }

    /** Wartet, bis die Bedingung gilt. Die Zeitgrenze fängt einen ausbleibenden Schritt ab, statt zu hängen. */
    private suspend fun awaitTrue(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun create(): SpaceServerService {
        val controller = Robolectric.buildService(SpaceServerService::class.java).create()
        this.controller = controller
        return controller.get()
    }

    /** Wartet, bis der Zustand den Typ [T] hat. Die Zeitgrenze fängt einen Fehler ab, statt zu hängen. */
    private suspend inline fun <reified T : RunState> awaitState() {
        withTimeout(TIMEOUT_MS) { SpaceServerService.runState.first { it is T } }
    }

    /** Ein WLAN, das ohne Änderung bleibt. */
    private class FixedNetwork(
        private val address: WifiAddress,
    ) : WifiNetworkProvider {
        override fun currentAddress(): WifiAddress = address

        override fun changes(): Flow<WifiAddress?> = flowOf(address)
    }

    private companion object {
        val WIFI = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
