package io.github.labushuya.emberlight.debugserver

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.server.ServerSetup
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DebugServerReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalSetupProvider = SpaceServerService.serverSetupProvider
    private val sentinel: () -> ServerSetup? = { null }

    @Before
    fun setUp() {
        ShadowLog.clear()
        SpaceServerService.serverSetupProvider = sentinel
    }

    @After
    fun tearDown() {
        SpaceServerService.serverSetupProvider = originalSetupProvider
    }

    @Test
    fun hostAndGuestHashesStartTheServiceAndArePassedThroughUnchanged() {
        send(hostHash = HOST, guestHash = GUEST)

        val started = shadowOf(context as Application).nextStartedService
        assertNotNull(started)
        assertEquals(SpaceServerService.ACTION_START, started.action)

        val setup = checkNotNull(SpaceServerService.serverSetupProvider())
        assertTrue(setup.debugLogging)
        assertEquals(listOf(Role.HOST, Role.GUEST), setup.participants.map { it.principal.role })
        assertEquals(HOST, setup.participants[0].tokenSha256Hex)
        assertEquals(GUEST, setup.participants[1].tokenSha256Hex)
    }

    @Test
    fun hostOnlyCreatesOneParticipant() {
        send(hostHash = HOST)

        assertNotNull(shadowOf(context as Application).nextStartedService)
        val setup = checkNotNull(SpaceServerService.serverSetupProvider())
        assertEquals(listOf(Role.HOST), setup.participants.map { it.principal.role })
    }

    @Test
    fun noTokenFileIsWritten() {
        send(hostHash = HOST, guestHash = GUEST)

        assertFalse(File(context.filesDir, "debug-server").exists())
        assertFalse(File(context.filesDir, "debug-server/tokens.json").exists())
    }

    @Test
    fun oldRawTokenExtrasAreIgnoredWithoutFallback() {
        val intent = Intent(DebugServerReceiver.ACTION_START)
        intent.putExtra("host_token", "0123456789abcdef".repeat(4))
        DebugServerReceiver().onReceive(context, intent)

        assertRefused(secrets = listOf("0123456789abcdef"))
    }

    @Test
    fun missingOrInvalidHostHashRefusesTheStart() {
        listOf(null, "", HOST.uppercase(), HOST.dropLast(1), HOST.replaceRange(0, 1, "z"), " $HOST").forEach { bad ->
            ShadowLog.clear()
            send(hostHash = bad)
            assertRefused(secrets = listOfNotNull(bad?.takeIf { it.isNotEmpty() }))
        }
    }

    @Test
    fun invalidGuestHashRefusesTheStart() {
        listOf("", GUEST.uppercase(), GUEST.dropLast(1), HOST).forEach { bad ->
            ShadowLog.clear()
            send(hostHash = HOST, guestHash = bad)
            assertRefused(secrets = listOfNotNull(bad.takeIf { it.isNotEmpty() }, HOST))
        }
    }

    @Test
    fun equalHostAndGuestHashRefusesTheStart() {
        send(hostHash = HOST, guestHash = HOST)

        assertRefused(secrets = listOf(HOST))
    }

    @Test
    fun invalidPortRefusesTheStart() {
        send(hostHash = HOST, port = -1)
        assertRefused(secrets = listOf(HOST))

        ShadowLog.clear()
        send(hostHash = HOST, port = 65536)
        assertRefused(secrets = listOf(HOST))
    }

    @Test
    fun hashesNeverAppearInLogEvenOnSuccess() {
        send(hostHash = HOST, guestHash = GUEST)

        assertNoLogContains(HOST, GUEST)
        assertTrue(ShadowLog.getLogs().any { it.tag == "EmberlightServer" && it.type == Log.INFO })
    }

    @Test
    fun stopBroadcastDoesNotTouchHashes() {
        DebugServerReceiver().onReceive(context, Intent(DebugServerReceiver.ACTION_STOP))

        assertNotNull(shadowOf(context as Application).nextStartedService)
        assertSame(sentinel, SpaceServerService.serverSetupProvider)
        assertNoLogContains(HOST, GUEST)
    }

    /** Die Ablehnung muss den Server nicht starten, die Einrichtung unangetastet lassen und unter EmberlightServer loggen. */
    private fun assertRefused(secrets: List<String>) {
        assertNull(shadowOf(context as Application).nextStartedService)
        assertSame(sentinel, SpaceServerService.serverSetupProvider)
        assertTrue(
            "Fehlermeldung unter EmberlightServer erwartet",
            ShadowLog.getLogs().any { it.tag == "EmberlightServer" && it.type == Log.ERROR },
        )
        assertNoLogContains(*secrets.toTypedArray())
    }

    private fun assertNoLogContains(vararg secrets: String) {
        val messages = ShadowLog.getLogs().map { it.msg.orEmpty() + " " + it.tag.orEmpty() }
        secrets.forEach { secret ->
            messages.forEach { message -> assertFalse("Hash im Log gefunden", message.contains(secret)) }
        }
    }

    private fun send(
        hostHash: String? = HOST,
        guestHash: String? = null,
        port: Int? = null,
    ) {
        val intent = Intent(DebugServerReceiver.ACTION_START)
        hostHash?.let { intent.putExtra(DebugServerReceiver.EXTRA_HOST_TOKEN_SHA256, it) }
        guestHash?.let { intent.putExtra(DebugServerReceiver.EXTRA_GUEST_TOKEN_SHA256, it) }
        port?.let { intent.putExtra(DebugServerReceiver.EXTRA_PORT, it) }
        DebugServerReceiver().onReceive(context, intent)
    }

    private companion object {
        // SHA-256 über die UTF-8-Bytes von "host-test-token" bzw. "guest-test-token".
        const val HOST = "1ecf73525cdb0100111fd4a8d8b88d21cd8562902bcdef91d1c862e1642f4279"
        const val GUEST = "2b2d8e931fd3ecfb0a60fc4c8f8f9ba4ddef76fbe8150cd065f2a59a8e584989"
    }
}
