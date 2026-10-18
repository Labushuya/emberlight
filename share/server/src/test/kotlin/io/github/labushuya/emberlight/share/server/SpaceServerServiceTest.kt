package io.github.labushuya.emberlight.share.server

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Lebenszyklus des Foreground-Service ohne echtes Gerät (ADR 0012). Die Sperren selbst prüft [ServerLocksTest] mit
 * Fakes, hier geht es um Start, Ablehnung und Stopp.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceServerServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ServiceController<SpaceServerService>? = null

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun stopIntentStopsTheService() {
        val service = createService(SpaceServerService.stopIntent(context))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
    }

    @Test
    fun startWithoutLocalNetworkPermissionIsRefused() {
        // Ohne Berechtigung ACCESS_LOCAL_NETWORK lehnt der Dienst den Start ab (ab targetSdk 37 Pflicht).
        val service = createService(SpaceServerService.startIntent(context, port = 0))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
    }

    @Test
    fun unknownActionStopsTheServiceAfterForegroundCall() {
        val service = createService(Intent("io.github.labushuya.emberlight.share.server.UNKNOWN"))

        service.startCommand(0, 1)

        assertTrue(awaitStoppedBySelf(service))
    }

    private fun createService(intent: Intent): ServiceController<SpaceServerService> =
        Robolectric
            .buildService(SpaceServerService::class.java, intent)
            .create()
            .also { controller = it }

    private fun awaitStoppedBySelf(controller: ServiceController<SpaceServerService>): Boolean {
        val shadow = shadowOf(controller.get())
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (shadow.isStoppedBySelf()) return true
            Thread.sleep(POLL_MS)
        }
        return shadow.isStoppedBySelf()
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
