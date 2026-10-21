package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.space.run.StartProblem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens auf dem Telefon, Englisch. Eigene Klasse je Qualifier (siehe [SpaceRunScreenshotSupport]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRunScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun setupChecklistPhoneEnglish() {
        capture({
            SetupContent(setupItems(localNetworkOpen = false), blocked = false)
        }, "src/test/screenshots/SpaceSetup_phone_en.png")
    }

    @Test
    fun startErrorAnotherActivePhoneEnglish() {
        captureOutcome(
            StartOutcome.Refused(StartProblem.ANOTHER_ACTIVE),
            "src/test/screenshots/SpaceStartError_phone_en.png",
        )
    }

    @Test
    fun runningPauseBannerPhoneEnglish() {
        capture(
            { Running(state(usedBytes = 1 * GIB_BYTES, pause = pauseEvent())) },
            "src/test/screenshots/SpaceRunning_pause_phone_en.png",
        )
    }

    @Test
    fun runningInterruptedPhoneEnglish() {
        capture(
            { Running(state(usedBytes = 1 * GIB_BYTES, runState = RunState.Idle)) },
            "src/test/screenshots/SpaceRunning_interrupted_phone_en.png",
        )
    }

    @Test
    fun runningStartQueuedPhoneEnglish() {
        capture(
            { Running(state(usedBytes = 0L, runState = RunState.Idle, startQueued = true)) },
            "src/test/screenshots/SpaceRunning_startQueued_phone_en.png",
        )
    }

    @Test
    fun detailEndedPhoneEnglish() {
        capture({
            SpaceDetailContent(record = ended(), coverFile = null, onEdit = {
            }, onDelete = {})
        }, "src/test/screenshots/SpaceDetailEnded_phone_en.png")
    }

    private companion object {
        const val GIB_BYTES = 1024L * 1024L * 1024L
    }
}
