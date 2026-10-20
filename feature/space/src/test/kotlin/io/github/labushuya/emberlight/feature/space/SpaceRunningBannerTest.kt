package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.run.SpaceRunningContent
import io.github.labushuya.emberlight.feature.space.run.SpaceRunningUiState
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.server.PauseCause
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Banner „Space wurde unterbrochen“ in der Gastgeber-Ansicht (ADR 0017): Anzeige nur bei Unterbrechung, „Fortsetzen“ ruft
 * den Befehl auf, und ein gescheiterter Versuch nennt den Grund.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRunningBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun anInterruptedSpaceShowsTheBannerAndResumeCallsBack() {
        var resumed = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceRunningContent(
                    state = state(runState = RunState.Idle),
                    onEnd = {},
                    onConfirmNetwork = {},
                    onAcknowledgePause = {},
                    onSetup = {},
                    onResume = { resumed++ },
                    onRemoveRequest = {},
                    onRotateRequest = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_interrupted_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_interrupted_resume)).performClick()

        assertEquals(1, resumed)
    }

    @Test
    fun aRunningServerShowsNoBanner() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceRunningContent(
                    state = state(runState = RunState.Running(SPACE_ID, "Test", 8787, WIFI, NOW, NOW + HOUR)),
                    onEnd = {},
                    onConfirmNetwork = {},
                    onAcknowledgePause = {},
                    onSetup = {},
                    onResume = {},
                    onRemoveRequest = {},
                    onRotateRequest = {},
                )
            }
        }

        assertAbsent(context.getString(R.string.space_interrupted_title))
        assertAbsent(context.getString(R.string.space_interrupted_resume))
    }

    @Test
    fun aPausedServerIsNotShownAsInterrupted() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceRunningContent(
                    state =
                        state(
                            runState = RunState.Paused(SPACE_ID, "Test", PauseCause.NETWORK_LOST, NOW + HOUR, null),
                        ),
                    onEnd = {},
                    onConfirmNetwork = {},
                    onAcknowledgePause = {},
                    onSetup = {},
                    onResume = {},
                    onRemoveRequest = {},
                    onRotateRequest = {},
                )
            }
        }

        assertAbsent(context.getString(R.string.space_interrupted_title))
    }

    @Test
    fun aRefusedResumeNamesTheReasonNextToTheButton() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceRunningContent(
                    state = state(runState = RunState.Idle, resumeFailure = StartOutcome.NoWifi),
                    onEnd = {},
                    onConfirmNetwork = {},
                    onAcknowledgePause = {},
                    onSetup = {},
                    onResume = {},
                    onRemoveRequest = {},
                    onRotateRequest = {},
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.space_setup_error_no_wifi))
            .assertIsDisplayed()
    }

    /** Der Knoten fehlt im Baum. Ein Knoten mit diesem Text darf nicht da sein. */
    private fun assertAbsent(text: String) {
        assertTrue(composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    private fun state(
        runState: RunState,
        resumeFailure: StartOutcome? = null,
    ): SpaceRunningUiState =
        SpaceRunningUiState(
            record =
                SpaceRecord(
                    id = SPACE_ID,
                    settings =
                        SpaceSettings(
                            name = "Test",
                            colorIndex = 0,
                            lifetime = Lifetime.Until(NOW + HOUR),
                            retentionDays = 7,
                            storageLimitBytes = GIB,
                        ),
                    coverSha256 = null,
                    status = SpaceStatus.ACTIVE,
                    createdAt = NOW - DAY,
                    updatedAt = NOW - DAY,
                    startedAt = NOW - HOUR,
                    expiresAt = NOW + HOUR,
                ),
            loaded = true,
            runState = runState,
            nowMillis = NOW,
            resumeFailure = resumeFailure,
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        const val GIB = 1024L * 1024L * 1024L
        val SPACE_ID = SpaceId.generate()
        val WIFI = WifiAddress("192.168.1.20", 24)
    }
}
