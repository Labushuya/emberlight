package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens auf dem großen Display im Querformat, Englisch. Eigene Klasse je Qualifier (siehe [SpaceRunScreenshotSupport]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w900dp-h600dp")
class SpaceRunScreenshotTabletTest : SpaceRunScreenshotSupport() {
    @Test
    fun setupChecklistTabletLandscapeEnglish() {
        capture(
            { SetupContent(setupItems(localNetworkOpen = true), blocked = true) },
            "src/test/screenshots/SpaceSetup_tablet_landscape_en.png",
        )
    }

    @Test
    fun runningTabletLandscapeEnglish() {
        capture(
            { Running(state(usedBytes = 1 * GIB_BYTES)) },
            "src/test/screenshots/SpaceRunning_tablet_landscape_en.png",
        )
    }

    private companion object {
        const val GIB_BYTES = 1024L * 1024L * 1024L
    }
}
