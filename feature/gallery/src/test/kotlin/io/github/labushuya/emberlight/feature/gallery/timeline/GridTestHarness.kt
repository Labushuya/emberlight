package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.GalleryUiState
import io.github.labushuya.emberlight.feature.gallery.album.AlbumList
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.viewer.testPlayback

/** Zählt Kompositionen und welche Zellen gerade im Raster stehen. Ersetzt im Test den Betrieb ohne Zähler. */
internal class GridProbeRecorder : GridProbe {
    val cellCompositions = mutableMapOf<String, Int>()
    val visibleCells = mutableSetOf<String>()
    var gridCompositions = 0
        private set

    override fun gridComposed() {
        gridCompositions++
    }

    override fun cellComposed(uri: String) {
        cellCompositions[uri] = (cellCompositions[uri] ?: 0) + 1
    }

    override fun cellEntered(uri: String) {
        visibleCells += uri
    }

    override fun cellLeft(uri: String) {
        visibleCells -= uri
    }
}

/**
 * Galerie für Compose-Tests mit veränderlichem Zustand.
 *
 * - [tick] erzwingt eine Neukomposition ohne Zustandsänderung.
 * - [generation] baut die ganze Ansicht neu, wie ein Prozess-Neustart ohne gespeicherten Zustand.
 * - [Body] ist die Ansicht selbst, damit ein StateRestorationTester sie über einen Konfigurationswechsel neu aufbauen kann.
 */
internal class GalleryHarness(
    private val items: List<MediaItem>,
    val probe: GridProbeRecorder? = null,
) {
    var generation by mutableIntStateOf(0)
    var tick by mutableIntStateOf(0)
    var zoomLevel by mutableIntStateOf(ZoomLevels.DEFAULT_LEVEL)

    fun show(rule: ComposeContentTestRule) {
        GridProbeHolder.current = probe
        rule.setContent {
            EmberlightTheme {
                Body()
            }
        }
    }

    @Composable
    fun Body() {
        key(generation) {
            // Liest den Zähler, damit eine Änderung nur die Ansicht neu zusammensetzt.
            check(tick >= 0)
            GalleryContent(
                state = GalleryUiState(grid = content(), albums = AlbumList(emptyList())),
                onRequestPermission = {},
                onOpenSettings = {},
                onAlbumClick = {},
                onZoomLevelChange = { zoomLevel = it },
                playback = testPlayback(),
            )
        }
    }

    private fun content(): MediaGridState.Content {
        val level = ZoomLevels.clamp(zoomLevel)
        return MediaGridState.Content(
            sections = TimelineGrouping.group(items, ZoomLevels.groupingFor(level), TODAY, ZONE),
            access = MediaAccess.FULL,
            missingTypes = emptySet(),
            zoomLevel = level,
            today = TODAY,
        )
    }
}
