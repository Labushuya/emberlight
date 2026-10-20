package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.space.run.StartProblem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens auf dem Telefon, Deutsch. Eigene Klasse je Qualifier (siehe [SpaceRunScreenshotSupport]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceRunScreenshotGermanTest : SpaceRunScreenshotSupport() {
    @Test
    fun setupChecklistPhoneGerman() {
        capture({
            SetupContent(setupItems(localNetworkOpen = true), blocked = true)
        }, "src/test/screenshots/SpaceSetup_phone_de.png")
    }

    @Test
    fun startErrorStorageAboveFreePhoneGerman() {
        captureOutcome(
            StartOutcome.Refused(StartProblem.STORAGE_ABOVE_FREE),
            "src/test/screenshots/SpaceStartError_phone_de.png",
        )
    }

    @Test
    fun runningInterruptedPhoneGerman() {
        capture(
            { Running(state(usedBytes = 1 * GIB_BYTES, runState = RunState.Idle)) },
            "src/test/screenshots/SpaceRunning_interrupted_phone_de.png",
        )
    }

    @Test
    fun runningStorageWarningPhoneGerman() {
        capture(
            { Running(state(usedBytes = (3.5 * GIB_BYTES).toLong())) },
            "src/test/screenshots/SpaceRunning_warning_phone_de.png",
        )
    }

    @Test
    fun runningNetworkChangePhoneGerman() {
        capture(
            { Running(state(usedBytes = 1 * GIB_BYTES, runState = pausedNetworkChanged())) },
            "src/test/screenshots/SpaceRunning_network_phone_de.png",
        )
    }

    @Test
    fun detailEndedPhoneGerman() {
        capture({
            SpaceDetailContent(record = ended(), coverFile = null, onEdit = {
            }, onDelete = {})
        }, "src/test/screenshots/SpaceDetailEnded_phone_de.png")
    }

    private companion object {
        const val GIB_BYTES = 1024L * 1024L * 1024L
    }
}
