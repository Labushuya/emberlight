package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.media.HostMediaItem
import io.github.labushuya.emberlight.feature.space.media.HostMediaUiState
import io.github.labushuya.emberlight.feature.space.media.MediaFilter
import io.github.labushuya.emberlight.feature.space.run.HostMediaSection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden der Namen in den Kacheln (Hotfix 2). Das Außendisplay des Honor Magic V2 hat 1060×2376 px bei 480 dpi, das sind
 * 353×792 dp (xxhdpi). Dort liegen drei Spalten. Die Namen müssen sichtbar bleiben, auch lange wie „Maximilian“.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w353dp-h792dp-xxhdpi")
class SpaceHostMediaGridNamesOuterDisplayDeScreenshotTest : SpaceNamesScreenshotSupport() {
    @Test
    fun namesOnTheOuterDisplayWithThreeColumns() {
        capture({ namesColumn() }, "src/test/screenshots/SpaceHostMediaGridNames_aussen_de.png")
    }
}

/**
 * Golden der Namen auf dem Innendisplay. Die Größe ist eine Annahme (673×728 dp, Breite ab 600 dp, also fünf Spalten).
 * Vor dem Abnehmen mit dem echten Gerät prüfen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w673dp-h728dp-xxhdpi")
class SpaceHostMediaGridNamesInnerDisplayDeScreenshotTest : SpaceNamesScreenshotSupport() {
    @Test
    fun namesOnTheInnerDisplayWithFiveColumns() {
        capture({ namesColumn() }, "src/test/screenshots/SpaceHostMediaGridNames_innen_de.png")
    }
}

/** Gemeinsame Hilfe: Die Kacheln mit realistischen Namen, ohne Vorschau (Platzhalter), damit nur der Name zählt. */
abstract class SpaceNamesScreenshotSupport : SpaceRunScreenshotSupport() {
    @androidx.compose.runtime.Composable
    protected fun namesColumn() =
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            HostMediaSection(state = namesState(), onFilter = {}, onOpen = {})
        }

    private fun namesState(): HostMediaUiState =
        HostMediaUiState(
            loaded = true,
            filter = MediaFilter.ALL,
            items =
                listOf("Anna", "Maximilian", "Sophie", "Konstantin", "Jo", "Bärbel", "Lukas", "Christopher-Alexander")
                    .mapIndexed { index, name ->
                        HostMediaItem(
                            id = "name-$index",
                            isVideo = false,
                            isHeic = false,
                            mine = false,
                            uploaderName = name,
                            uploaderRemoved = false,
                            locationKept = false,
                            previewState = "PENDING",
                            previewFile = null,
                            sizeBytes = 1_000_000L,
                            createdAt = SpaceFixtures.NOW - index * 60_000L,
                        )
                    },
        )
}
