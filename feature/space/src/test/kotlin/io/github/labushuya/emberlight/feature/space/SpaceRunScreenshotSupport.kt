package io.github.labushuya.emberlight.feature.space

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.run.SpaceRunningContent
import io.github.labushuya.emberlight.feature.space.run.SpaceRunningUiState
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.feature.space.setup.CheckStatus
import io.github.labushuya.emberlight.feature.space.setup.OemFamily
import io.github.labushuya.emberlight.feature.space.setup.SetupChecks
import io.github.labushuya.emberlight.feature.space.setup.SetupEnvironment
import io.github.labushuya.emberlight.feature.space.setup.SetupItem
import io.github.labushuya.emberlight.feature.space.setup.SetupPermissions
import io.github.labushuya.emberlight.feature.space.setup.SpaceSetupContent
import io.github.labushuya.emberlight.feature.space.setup.StartGate
import io.github.labushuya.emberlight.feature.space.setup.StartOutcomeContent
import io.github.labushuya.emberlight.share.server.PauseCause
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.space.MediaRow
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows
import java.util.Locale
import java.util.TimeZone

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB
private const val HOUR = 60L * 60L * 1000L

/**
 * Gemeinsame Grundlage der Goldens für Einrichtung, laufenden Space und beendeten Space (Paket 3.3, ADR 0006).
 *
 * Jede Konfiguration (Sprache, Größe) hat eine eigene Testklasse. Robolectric legt beim Wechsel der Qualifier eine neue
 * Umgebung an, und die erste Aufnahme darin bleibt leer (ADR 0006, Befund). Deshalb wechselt keine Klasse die Konfiguration.
 */
abstract class SpaceRunScreenshotSupport {
    @get:Rule
    val composeRule = createComposeRule()

    private var previousZone: TimeZone? = null
    private var previousLocale: Locale? = null

    @Before
    fun fixZoneAndLocale() {
        previousZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(SpaceFixtures.ZONE))
    }

    @After
    fun restoreZoneAndLocale() {
        previousZone?.let { TimeZone.setDefault(it) }
        previousLocale?.let { Locale.setDefault(it) }
    }

    /** Einrichtung mit den Punkten aus den Prüfungen. [localNetworkOpen] lässt den Pflicht-Punkt offen. */
    protected fun setupItems(localNetworkOpen: Boolean): List<SetupItem> =
        SetupChecks.items(
            object : SetupEnvironment {
                override val sdkInt: Int = 37
                override val targetSdk: Int = 37
                override val notificationsGranted: Boolean = false
                override val localNetworkGranted: Boolean = !localNetworkOpen
                override val batteryIgnored: Boolean = false
                override val backgroundRestricted: Boolean = true
                override val standbyRestricted: Boolean = false
                override val oemConfirmed: Boolean = false

                override fun wasAsked(permission: String): Boolean = permission == SetupPermissions.NOTIFICATIONS

                override fun canAskAgain(permission: String): Boolean = permission != SetupPermissions.NOTIFICATIONS
            },
        )

    @Composable
    protected fun SetupContent(
        items: List<SetupItem>,
        blocked: Boolean,
    ) {
        SpaceSetupContent(
            items = items,
            gate =
                StartGate(
                    blocked = blocked,
                    recommendedOpen = items.filter { !it.required && it.status == CheckStatus.OPEN }.map { it.kind },
                ),
            startMode = true,
            starting = false,
            family = OemFamily.HONOR,
            oemConfirmed = false,
            oemFailed = false,
            onAction = {},
            onOemOpen = {},
            onOemConfirm = {},
            onStart = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Composable
    protected fun Running(state: SpaceRunningUiState) {
        SpaceRunningContent(
            state = state,
            onEnd = {},
            onConfirmNetwork = {},
            onAcknowledgePause = {},
            onSetup = {},
            onResume = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    protected fun state(
        usedBytes: Long,
        runState: RunState = runningState(),
        pause: PauseEvent? = null,
    ): SpaceRunningUiState =
        SpaceRunningUiState(
            record = active(),
            loaded = true,
            usedBytes = usedBytes,
            media = media(),
            runState = runState,
            pause = pause,
            nowMillis = SpaceFixtures.NOW,
        )

    protected fun runningState(): RunState.Running =
        RunState.Running(
            spaceId = SPACE_ID,
            spaceName = SPACE_NAME,
            port = 8787,
            address = WifiAddress("192.168.1.20", 24, "192.168.1.1"),
            startedAtMillis = SpaceFixtures.NOW - HOUR,
            expiresAtMillis = SpaceFixtures.NOW + 2 * HOUR,
        )

    protected fun pausedNetworkChanged(): RunState.Paused =
        RunState.Paused(
            spaceId = SPACE_ID,
            spaceName = SPACE_NAME,
            cause = PauseCause.NETWORK_CHANGED,
            expiresAtMillis = SpaceFixtures.NOW + 2 * HOUR,
            pendingAddress = WifiAddress("10.0.0.7", 24, "10.0.0.1"),
        )

    protected fun pauseEvent(): PauseEvent =
        PauseEvent(startedAtMillis = SpaceFixtures.NOW - HOUR / 2, durationMillis = 7 * 60_000L, deepSleepMillis = 0L)

    protected fun active(): SpaceRecord =
        SpaceFixtures
            .draft(SPACE_ID.value, SPACE_NAME, storageBytes = 4 * GIB, status = SpaceStatus.ACTIVE)
            .copy(startedAt = SpaceFixtures.NOW - HOUR, expiresAt = SpaceFixtures.NOW + 2 * HOUR)

    protected fun ended(): SpaceRecord =
        SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, SPACE_NAME, status = SpaceStatus.ENDED).copy(
            startedAt = SpaceFixtures.NOW - 5 * HOUR,
            expiresAt = SpaceFixtures.NOW - HOUR,
            endedAt = SpaceFixtures.NOW - HOUR,
        )

    protected fun media(): List<MediaRow> =
        listOf(
            MediaRow("m1", "image/jpeg", 3 * MIB, SpaceFixtures.NOW - 10 * 60_000L, "Lena", false),
            MediaRow("m2", "video/mp4", 120 * MIB, SpaceFixtures.NOW - 30 * 60_000L, "Tom", false),
            MediaRow("m3", "image/png", 2 * MIB, SpaceFixtures.NOW - 50 * 60_000L, null, true),
        )

    /** Fehler des Startversuchs als Inhalt, ohne Dialogfenster. Der Dialog selbst hat eine Abdunkelung. */
    protected fun captureOutcome(
        outcome: StartOutcome,
        path: String,
    ) {
        capture(
            {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor) {
                        StartOutcomeContent(outcome = outcome, onDismiss = {}, onEdit = {})
                    }
                }
            },
            path,
        )
    }

    protected fun capture(
        content: @Composable () -> Unit,
        path: String,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        settle()
        composeRule.onRoot().captureRoboImage(path)
    }

    /** Lässt Robolectric die Hauptschleife abarbeiten, bevor die Aufnahme entsteht. */
    private fun settle() {
        composeRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private companion object {
        val SPACE_ID: SpaceId = SpaceId.parse(SpaceFixtures.ID_WANDERUNG)!!
        const val SPACE_NAME = "Wanderung am Sonntag"
    }
}
