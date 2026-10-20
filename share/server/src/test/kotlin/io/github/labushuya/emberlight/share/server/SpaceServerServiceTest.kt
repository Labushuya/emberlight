package io.github.labushuya.emberlight.share.server

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceId
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
    private var controller: ServiceController<SpaceServerService>? = null

    @Before
    fun setUp() {
        // Ab API 37 mit targetSdk 37 braucht der Start diese Berechtigung. Der Test erteilt sie, wie der Benutzer es täte.
        Shadows
            .shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(LOCAL_NETWORK_PERMISSION)
        SpaceServerService.serverSetupProvider =
            { ServerSetup(spaceId = spaceId, spaceName = "Test", debugLogging = false) }
        SpaceServerService.serverFactory = { _, _, _ ->
            serverStarts++
            FakeLocalServer()
        }
        SpaceServerService.networkFactory = { _ -> FixedNetwork(WIFI) }
        SpaceServerService.locksFactory = { _ -> locks }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        SpaceServerService.serverSetupProvider = originalSetup
        SpaceServerService.serverFactory = originalServer
        SpaceServerService.networkFactory = originalNetwork
        SpaceServerService.locksFactory = originalLocks
        SpaceServerService.foregroundStarter = originalForeground
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
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
