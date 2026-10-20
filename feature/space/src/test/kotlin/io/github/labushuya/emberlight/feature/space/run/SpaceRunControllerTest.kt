package io.github.labushuya.emberlight.feature.space.run

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.server.SpaceServerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** Befehle der Oberfläche an den Dienst: Ende, Netzbestätigung und Ablauf. Das Ende geht nie direkt, nur als Auftrag. */
@RunWith(RobolectricTestRunner::class)
class SpaceRunControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadow = Shadows.shadowOf(context as Application)

    @Test
    fun endSendsTheStopCommandAsAServiceIntent() {
        SpaceRunController(context).end()

        assertEquals(SpaceServerService.ACTION_STOP, shadow.nextStartedService.action)
    }

    @Test
    fun confirmNetworkSendsTheConfirmationCommand() {
        SpaceRunController(context).confirmNetwork()

        assertEquals(SpaceServerService.ACTION_CONFIRM_NETWORK, shadow.nextStartedService.action)
    }

    @Test
    fun expireSendsTheExpiryCommand() {
        SpaceRunController(context).expire()

        assertEquals(SpaceServerService.ACTION_EXPIRE, shadow.nextStartedService.action)
        assertNull(shadow.nextStartedService)
    }
}
