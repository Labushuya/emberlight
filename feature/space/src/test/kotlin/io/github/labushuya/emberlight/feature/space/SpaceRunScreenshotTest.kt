package io.github.labushuya.emberlight.feature.space

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.github.labushuya.emberlight.feature.space.setup.StartOutcomeDialog
import io.github.labushuya.emberlight.share.server.PauseCause
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.space.MediaRow
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.StartProblem
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB
private const val HOUR = 60L * 60L * 1000L
private const val DIALOG_TIMEOUT_MILLIS = 5_000L

/**
 * Goldens für Einrichtung, laufenden Space, beendeten Space und den Startfehler (Paket 3.3, ADR 0006). Telefon in Deutsch
 * und Englisch, dazu ein großes Display. Die Zustände stehen fest, damit die Bilder auf jedem Rechner gleich sind.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpaceRunScreenshotTest {
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

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun setupChecklistPhoneGerman() {
        capture({
            SetupContent(setupItems(localNetworkOpen = true), blocked = true)
        }, "src/test/screenshots/SpaceSetup_phone_de.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun setupChecklistPhoneEnglish() {
        capture(
            { SetupContent(setupItems(localNetworkOpen = false), blocked = false) },
            "src/test/screenshots/SpaceSetup_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun setupChecklistTabletLandscapeEnglish() {
        capture(
            { SetupContent(setupItems(localNetworkOpen = true), blocked = true) },
            "src/test/screenshots/SpaceSetup_tablet_landscape_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun startErrorStorageAboveFreePhoneGerman() {
        captureDialog(
            StartOutcome.Refused(StartProblem.STORAGE_ABOVE_FREE),
            "src/test/screenshots/SpaceStartError_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun startErrorAnotherActivePhoneEnglish() {
        captureDialog(
            StartOutcome.Refused(StartProblem.ANOTHER_ACTIVE),
            "src/test/screenshots/SpaceStartError_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun runningNormalPhoneEnglish() {
        capture({ Running(state(usedBytes = 1 * GIB)) }, "src/test/screenshots/SpaceRunning_phone_en.png")
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun runningStorageWarningPhoneGerman() {
        capture(
            { Running(state(usedBytes = (3.5 * GIB).toLong())) },
            "src/test/screenshots/SpaceRunning_warning_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun runningPauseBannerPhoneEnglish() {
        capture(
            {
                Running(
                    state(
                        usedBytes = 1 * GIB,
                        pause =
                            PauseEvent(
                                startedAtMillis = SpaceFixtures.NOW - HOUR / 2,
                                durationMillis = 7 * 60_000L,
                                deepSleepMillis = 0L,
                            ),
                    ),
                )
            },
            "src/test/screenshots/SpaceRunning_pause_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun runningNetworkChangePhoneGerman() {
        capture(
            {
                Running(
                    state(
                        usedBytes = 1 * GIB,
                        runState =
                            RunState.Paused(
                                spaceId = SPACE_ID,
                                spaceName = SPACE_NAME,
                                cause = PauseCause.NETWORK_CHANGED,
                                expiresAtMillis = SpaceFixtures.NOW + 2 * HOUR,
                                pendingAddress = WifiAddress("10.0.0.7", 24, "10.0.0.1"),
                            ),
                    ),
                )
            },
            "src/test/screenshots/SpaceRunning_network_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun runningTabletLandscapeEnglish() {
        capture({ Running(state(usedBytes = 1 * GIB)) }, "src/test/screenshots/SpaceRunning_tablet_landscape_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun detailEndedPhoneEnglish() {
        capture(
            {
                SpaceDetailContent(
                    record = ended(),
                    coverFile = null,
                    onEdit = {},
                    onDelete = {},
                )
            },
            "src/test/screenshots/SpaceDetailEnded_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun detailEndedPhoneGerman() {
        capture(
            {
                SpaceDetailContent(
                    record = ended(),
                    coverFile = null,
                    onEdit = {},
                    onDelete = {},
                )
            },
            "src/test/screenshots/SpaceDetailEnded_phone_de.png",
        )
    }

    @Composable
    private fun SetupContent(
        items: List<SetupItem>,
        blocked: Boolean,
    ) {
        SpaceSetupContent(
            items = items,
            gate =
                StartGate(
                    blocked = blocked,
                    recommendedOpen =
                        items
                            .filter {
                                !it.required &&
                                    it.status == CheckStatus.OPEN
                            }.map { it.kind },
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
    private fun Running(state: SpaceRunningUiState) {
        SpaceRunningContent(
            state = state,
            onEnd = {},
            onConfirmNetwork = {},
            onAcknowledgePause = {},
            onSetup = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun setupItems(localNetworkOpen: Boolean): List<SetupItem> =
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

    private fun state(
        usedBytes: Long,
        runState: RunState =
            RunState.Running(
                spaceId = SPACE_ID,
                spaceName = SPACE_NAME,
                port = 8787,
                address = WifiAddress("192.168.1.20", 24, "192.168.1.1"),
                startedAtMillis = SpaceFixtures.NOW - HOUR,
                expiresAtMillis = SpaceFixtures.NOW + 2 * HOUR,
            ),
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

    private fun active(): SpaceRecord =
        SpaceFixtures
            .draft(
                SPACE_ID.value,
                SPACE_NAME,
                storageBytes = 4 * GIB,
                status = SpaceStatus.ACTIVE,
            ).copy(
                startedAt = SpaceFixtures.NOW - HOUR,
                expiresAt = SpaceFixtures.NOW + 2 * HOUR,
            )

    private fun ended(): SpaceRecord =
        SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, SPACE_NAME, status = SpaceStatus.ENDED).copy(
            startedAt = SpaceFixtures.NOW - 5 * HOUR,
            expiresAt = SpaceFixtures.NOW - HOUR,
            endedAt = SpaceFixtures.NOW - HOUR,
        )

    private fun media(): List<MediaRow> =
        listOf(
            MediaRow("m1", "image/jpeg", 3 * MIB, SpaceFixtures.NOW - 10 * 60_000L, "Lena", false),
            MediaRow("m2", "video/mp4", 120 * MIB, SpaceFixtures.NOW - 30 * 60_000L, "Tom", false),
            MediaRow("m3", "image/png", 2 * MIB, SpaceFixtures.NOW - 50 * 60_000L, null, true),
        )

    private fun captureDialog(
        outcome: StartOutcome,
        path: String,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StartOutcomeDialog(outcome = outcome, onDismiss = {}, onEdit = {})
                }
            }
        }
        composeRule.waitForIdle()
        // Der Dialog ist ein eigenes Fenster. Erst wenn es im Baum steht, ist die Abdunkelung gesetzt. Sonst wechselt das
        // Bild zwischen den Läufen.
        composeRule.waitUntil(timeoutMillis = DIALOG_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        composeRule.onNode(isDialog()).captureRoboImage(path)
    }

    /**
     * Lässt Robolectric die Hauptschleife abarbeiten, bevor die Aufnahme entsteht. Ohne das bleibt der erste Zeichendurchlauf
     * eines Testlaufs leer (ADR 0006, Befund). Gemessen: Der Leerzustand war im Klassenlauf leer, allein korrekt.
     */
    private fun settle() {
        composeRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun capture(
        content: @Composable () -> Unit,
        path: String,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                content()
            }
        }
        composeRule.waitForIdle()
        settle()
        composeRule.onRoot().captureRoboImage(path)
    }

    private companion object {
        val SPACE_ID: SpaceId = SpaceId.parse(SpaceFixtures.ID_WANDERUNG)!!
        const val SPACE_NAME = "Wanderung am Sonntag"
    }
}
