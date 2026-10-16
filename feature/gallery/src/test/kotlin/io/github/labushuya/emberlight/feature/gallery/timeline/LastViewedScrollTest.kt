package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.GalleryUiState
import io.github.labushuya.emberlight.feature.gallery.album.AlbumList
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.viewer.LastViewedMediaId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Rückkehr aus dem Viewer: Das Raster liegt mit dem zuletzt angesehenen Element schon im ersten Frame richtig (ADR 0010).
 * Die Uhr läuft von Hand, ein einziger Frame reicht. Ein Scrollen erst nach dem Layout würde das Element im ersten Frame
 * des Rückwegs verfehlen.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class LastViewedScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var hostZone: TimeZone? = null
    private var handled = 0

    @Before
    fun setUp() {
        // Die Datumsbeschriftung der Zellen folgt der Zone des Rechners, die Fixtures liegen in Berlin. UTC hält beides gleich.
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TestThumbnails.install(TimelineFixtures.longLibrary())
        composeRule.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun elementFarBelowIsInTheGridAfterTheFirstFrame() {
        val library = TimelineFixtures.longLibrary()
        val target = library[ELEMENT_INDEX]
        show(library, lastViewedId = target.id)

        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription(cellLabel(ELEMENT_INDEX)).assertIsDisplayed()
        assertEquals(1, handled)
    }

    @Test
    fun elementAlreadyInViewKeepsThePosition() {
        val library = TimelineFixtures.longLibrary()
        show(library, lastViewedId = library[1].id)

        composeRule.mainClock.advanceTimeByFrame()

        // Die Überschrift „Heute“ bleibt oben, weil das Element sichtbar ist und nicht gescrollt wird.
        composeRule.onNodeWithContentDescription(cellLabel(1)).assertIsDisplayed()
        assertEquals(1, handled)
    }

    @Test
    fun aMissingElementLeavesThePositionAndClearsTheMessage() {
        val library = TimelineFixtures.longLibrary()
        show(library, lastViewedId = 99_999L)

        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()
        assertEquals(1, handled)
    }

    private fun show(
        library: List<MediaItem>,
        lastViewedId: Long,
    ) {
        val state =
            GalleryUiState(
                grid =
                    MediaGridState.Content(
                        sections = TimelineGrouping.group(library, GroupingMode.DAY, TODAY, ZONE),
                        access = MediaAccess.FULL,
                        missingTypes = emptySet<MediaType>(),
                        zoomLevel = ZoomLevels.DEFAULT_LEVEL,
                        today = TODAY,
                    ),
                albums = AlbumList(emptyList()),
            )
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = state,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onMediaClick = {},
                    onAlbumClick = {},
                    onZoomLevelChange = {},
                    lastViewed = LastViewedMediaId(mediaId = lastViewedId, onHandled = { handled++ }),
                )
            }
        }
    }

    /** Beschreibung der Zelle an Position [index] der Bibliothek, wie das Raster sie ausgibt. */
    private fun cellLabel(index: Int): String = "Photo from ${DATE.format(TODAY.minusDays(index * 2L))}"

    private companion object {
        /** Ein Element weit unten, das man nur durch Scrollen erreicht. */
        const val ELEMENT_INDEX = 150
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    }
}
