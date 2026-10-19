package io.github.labushuya.emberlight.share.server

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Lebenszyklus des Foreground-Service ohne echten Server und ohne echte Sperren (ADR 0012). Server und Sperren sind
 * Fakes über die Test-Nähte der Companion. Der Socket-Pfad steht in [KtorLocalServerTest].
 */
@RunWith(RobolectricTestRunner::class)
class SpaceServerServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ServiceController<SpaceServerService>? = null

    private lateinit var server: FakeLocalServer
    private lateinit var cpu: FakeServerLock
    private lateinit var wifi: FakeServerLock

    private val originalServerFactory = SpaceServerService.serverFactory
    private val originalSetupProvider = SpaceServerService.serverSetupProvider
    private val fakeNetwork = FakeWifiNetworkProvider { currentWifi }
    private val originalNetworkFactory = SpaceServerService.networkFactory
    private var currentWifi: WifiAddress? = WifiAddress("127.0.0.1", 8)
    private val originalLocksFactory = SpaceServerService.locksFactory
    private val originalForegroundStarter = SpaceServerService.foregroundStarter

    @Before
    fun setUp() {
        server = FakeLocalServer()
        cpu = FakeServerLock()
        wifi = FakeServerLock()
        SpaceServerService.serverFactory = { _, _ -> server }
        SpaceServerService.serverSetupProvider = { testSetup() }
        SpaceServerService.networkFactory = { fakeNetwork }
        SpaceServerService.locksFactory = { ServerLocks(cpu, wifi) }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
        controller = null
        SpaceServerService.serverFactory = originalServerFactory
        SpaceServerService.serverSetupProvider = originalSetupProvider
        SpaceServerService.networkFactory = originalNetworkFactory
        SpaceServerService.locksFactory = originalLocksFactory
        SpaceServerService.foregroundStarter = originalForegroundStarter
    }

    @Test
    fun stopIntentStopsTheService() {
        val service = createService(SpaceServerService.stopIntent(context))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
    }

    @Test
    @Config(sdk = [37])
    fun startWithoutLocalNetworkPermissionIsRefusedOnApi37() {
        // Ab targetSdk 37 und API 37 ist ACCESS_LOCAL_NETWORK Pflicht. Ohne Berechtigung darf der Server nicht starten.
        assertEquals(37, context.applicationInfo.targetSdkVersion)
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
    }

    @Test
    @Config(sdk = [36])
    fun startWithoutLocalNetworkPermissionIsAllowedBelowApi37() {
        // Unter API 37 gibt es die Berechtigung nicht. Der Start ist erlaubt, auch wenn die App auf 37 zielt.
        assertEquals(37, context.applicationInfo.targetSdkVersion)
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitTrue { server.starts == 1 && cpu.isHeld && wifi.isHeld })
        assertFalse(shadowOf(service.get()).isStoppedBySelf())
    }

    @Test
    @Config(sdk = [37])
    fun startWithLocalNetworkPermissionIsAllowedOnApi37() {
        grantLocalNetwork()
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitTrue { server.starts == 1 && cpu.isHeld && wifi.isHeld })
    }

    @Test
    fun unknownActionStopsTheServiceAfterForegroundCall() {
        val service = createService(Intent("io.github.labushuya.emberlight.share.server.UNKNOWN"))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
    }

    @Test
    fun nullIntentReturnsNotStickyAndStops() {
        val service = Robolectric.buildService(SpaceServerService::class.java).create().also { controller = it }

        val result = service.get().onStartCommand(null, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
    }

    @Test
    fun onDestroyReleasesBothLocks() {
        grantLocalNetwork()
        val service = createService(SpaceServerService.startIntent(context, port = 0))
        service.startCommand(0, 1)
        assertTrue(awaitTrue { cpu.isHeld && wifi.isHeld })

        destroy()

        // Die Freigabe in onDestroy geschieht synchron, nicht erst im Hintergrund.
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertTrue(awaitTrue { server.stops >= 1 })
    }

    @Test
    fun failedServerStartCleansUpLocksAndStops() {
        grantLocalNetwork()
        server = FakeLocalServer(startFailure = IOException("Port belegt"))
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertEquals(0, cpu.acquires)
        assertTrue(server.stops >= 1)
    }

    @Test
    fun failedWifiLockAcquireCleansUpAndStops() {
        grantLocalNetwork()
        wifi = FakeServerLock(failOnAcquire = true)
        SpaceServerService.locksFactory = { ServerLocks(cpu, wifi) }
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
    }

    @Test
    fun securityExceptionOnStartForegroundStopsWithoutStart() {
        SpaceServerService.foregroundStarter = { _, _, _ -> throw SecurityException("verweigert") }
        val service = createService(SpaceServerService.startIntent(context))

        val result = service.get().onStartCommand(SpaceServerService.startIntent(context), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
    }

    @Test
    @Config(sdk = [31])
    fun foregroundStartNotAllowedStopsWithoutStart() {
        SpaceServerService.foregroundStarter = { _, _, _ ->
            throw ForegroundServiceStartNotAllowedException("aus dem Hintergrund verweigert")
        }
        val service = createService(SpaceServerService.startIntent(context))

        val result = service.get().onStartCommand(SpaceServerService.startIntent(context), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
    }

    @Test
    fun unexpectedIllegalStateFromStartForegroundIsNotSwallowed() {
        SpaceServerService.foregroundStarter = { _, _, _ -> throw IllegalStateException("unbekannt") }
        val service = createService(SpaceServerService.startIntent(context))

        val thrown = runCatching { service.get().onStartCommand(SpaceServerService.startIntent(context), 0, 1) }

        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun stopDuringStartLeavesNoLockHeld() {
        grantLocalNetwork()
        val gate = CompletableDeferred<Unit>()
        server = FakeLocalServer(startGate = gate)
        val service = createService(SpaceServerService.startIntent(context, port = 0))
        service.startCommand(0, 1)
        assertTrue(awaitTrue { server.starts == 1 })

        // Stopp, während der Server noch startet. Der Start darf danach keine Sperre mehr erwerben.
        service.get().onStartCommand(SpaceServerService.stopIntent(context), 0, 2)
        gate.complete(Unit)

        assertTrue(awaitStoppedBySelf(service))
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertEquals(0, cpu.acquires)
        assertEquals(0, wifi.acquires)
    }

    @Test
    fun withoutSetupTheServiceDoesNotStart() {
        grantLocalNetwork()
        SpaceServerService.serverSetupProvider = { null }
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
        assertFalse(cpu.isHeld)
    }

    @Test
    fun withoutWifiAddressTheServiceDoesNotStart() {
        grantLocalNetwork()
        currentWifi = null
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
        assertEquals(0, server.starts)
        assertFalse(wifi.isHeld)
    }

    @Test
    fun wifiAddressChangeStopsTheServerAndReleasesLocks() {
        grantLocalNetwork()
        val service = createService(SpaceServerService.startIntent(context, port = 0))
        service.startCommand(0, 1)
        assertTrue(awaitTrue { server.starts == 1 && cpu.isHeld && wifi.isHeld })

        fakeNetwork.emit(WifiAddress("192.168.1.77", 24))

        assertTrue(awaitTrue { server.stops >= 1 && !cpu.isHeld && !wifi.isHeld })
    }

    @Test
    fun lostWifiStopsTheServer() {
        grantLocalNetwork()
        val service = createService(SpaceServerService.startIntent(context, port = 0))
        service.startCommand(0, 1)
        assertTrue(awaitTrue { server.starts == 1 && cpu.isHeld })

        fakeNetwork.emit(null)

        assertTrue(awaitTrue { server.stops >= 1 && !cpu.isHeld })
    }

    private fun grantLocalNetwork() {
        shadowOf(context.applicationContext as Application).grantPermissions(LOCAL_NETWORK_PERMISSION)
    }

    private fun createService(intent: Intent): ServiceController<SpaceServerService> =
        Robolectric
            .buildService(SpaceServerService::class.java, intent)
            .create()
            .also { controller = it }

    private fun destroy() {
        controller?.destroy()
        controller = null
    }

    private fun awaitStoppedBySelf(controller: ServiceController<SpaceServerService>): Boolean {
        val shadow = shadowOf(controller.get())
        return awaitTrue { shadow.isStoppedBySelf() }
    }

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

/** Test-WLAN: liefert den aktuellen Stand und löst Wechsel über [changes] aus. */
internal class FakeWifiNetworkProvider(
    private val current: () -> WifiAddress?,
) : WifiNetworkProvider {
    /** StateFlow, damit ein Wechsel auch dann ankommt, wenn der Beobachter erst kurz danach abonniert. */
    private val updates = kotlinx.coroutines.flow.MutableStateFlow<Update?>(null)

    override fun currentAddress(): WifiAddress? = current()

    /** Erster Wert ist der Stand beim Abonnieren, danach der letzte und jeder weitere gemeldete Wechsel. */
    override fun changes(): kotlinx.coroutines.flow.Flow<WifiAddress?> =
        kotlinx.coroutines.flow.flow {
            emit(current())
            updates.filterNotNull().collect { emit(it.address) }
        }

    /** Simuliert einen Wechsel der Adresse oder den Verlust des WLAN. */
    fun emit(address: WifiAddress?) {
        updates.value = Update(address)
    }

    private class Update(
        val address: WifiAddress?,
    )
}

/** Einrichtung für die Service-Tests: ein Space mit einem Gastgeber. */
internal fun testSetup(): ServerSetup =
    ServerSetup(
        spaceId =
            io.github.labushuya.emberlight.share.space.SpaceId
                .generate(),
        spaceName = "Test",
        participants =
            listOf(
                ParticipantSeed(
                    principal =
                        io.github.labushuya.emberlight.share.space.Principal(
                            io.github.labushuya.emberlight.share.space.ParticipantId
                                .generate(),
                            io.github.labushuya.emberlight.share.space.Role.HOST,
                        ),
                    displayName = "Gastgeberin",
                    tokenSha256Hex = "0".repeat(64),
                ),
            ),
    )
