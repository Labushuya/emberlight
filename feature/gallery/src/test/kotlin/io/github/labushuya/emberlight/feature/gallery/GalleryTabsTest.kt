package io.github.labushuya.emberlight.feature.gallery

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.album.ALBUM_CARD_TAG
import io.github.labushuya.emberlight.feature.gallery.album.AlbumList
import io.github.labushuya.emberlight.feature.gallery.album.albumsForDisplay
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Reiter der Galerie: Umschalten per Antippen, erhaltene Scrollposition, Alben-Karten mit Reihenfolge und Anzahl, die
 * Antwort auf Antippen und der Hinweis-Banner in beiden Reitern.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class GalleryTabsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems() + TimelineFixtures.longLibrary())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun startsOnPhotosAndSwitchesOnTap() {
        show(sampleState())

        tab(photos()).assertIsSelected()
        tab(albums()).performClick()
        composeRule.waitForIdle()

        tab(albums()).assertIsSelected()
        composeRule.onAllNodes(hasTestTag(ALBUM_CARD_TAG)).onFirst().assertIsDisplayed()
    }

    @Test
    fun scrollPositionOfThePhotosTabSurvivesTheSwitch() {
        show(longState())
        grid().performScrollToIndex(120)
        composeRule.waitForIdle()
        assertTodayHeaderHidden()

        tab(albums()).performClick()
        composeRule.waitForIdle()
        tab(photos()).performClick()
        composeRule.waitForIdle()

        // Hätte der Reiter seine Position verloren, stünde die Überschrift „Heute“ wieder oben.
        assertTodayHeaderHidden()
    }

    @Test
    fun albumCardsShowNameAndCountInOrderWithOtherLast() {
        show(sampleState())
        tab(albums()).performClick()
        composeRule.waitForIdle()

        val labels =
            composeRule
                .onAllNodes(hasTestTag(ALBUM_CARD_TAG))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.ContentDescription].first() }
        assertEquals(
            listOf("Camera, 4 items", "Screenshots, 1 item", "Downloads, 2 items", "Other, 1 item"),
            labels,
        )
    }

    @Test
    fun tappingAnAlbumReportsItsBucketAndNullForOther() {
        val clicks = mutableListOf<Long?>()
        show(sampleState(), onAlbumClick = { clicks += it })
        tab(albums()).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(descriptionStartsWith("Downloads")).performClick()
        composeRule.onNode(descriptionStartsWith("Other")).performClick()

        assertEquals(listOf(TimelineFixtures.DOWNLOAD_BUCKET, null), clicks)
    }

    @Test
    fun partialBannerAppearsInBothTabs() {
        val banner = context.getString(GalleryR.string.feature_gallery_partial_text)
        show(sampleState(access = MediaAccess.PARTIAL))

        composeRule.onNodeWithText(banner).assertIsDisplayed()
        tab(albums()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(banner).assertIsDisplayed()
    }

    @Test
    fun missingVideoBannerAppearsInBothTabs() {
        val banner = context.getString(GalleryR.string.feature_gallery_missing_videos)
        show(sampleState(missingTypes = setOf(MediaType.VIDEO)))

        composeRule.onNodeWithText(banner).assertIsDisplayed()
        tab(albums()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(banner).assertIsDisplayed()
    }

    @Test
    fun noAccessShowsTheSamePromptInBothTabs() {
        val title = context.getString(GalleryR.string.feature_gallery_permission_title)
        val grid = MediaGridState.PermissionRequired(canRequest = true)
        show(GalleryUiState(grid = grid, albums = AlbumList(emptyList())))

        composeRule.onNodeWithText(title).assertIsDisplayed()
        tab(albums()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    private fun assertTodayHeaderHidden() {
        val today = context.getString(GalleryR.string.feature_gallery_header_today)
        composeRule.onAllNodes(hasText(today) and isHeading()).assertCountEquals(0)
    }

    private fun show(
        state: GalleryUiState,
        onAlbumClick: (Long?) -> Unit = {},
    ) {
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = state,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onMediaClick = {},
                    onAlbumClick = onAlbumClick,
                    onZoomLevelChange = {},
                )
            }
        }
    }

    /** Sechs Einträge mit vier Ordnern, wie in den Beispieldaten, ohne die langen Testdaten. */
    private fun sampleState(
        access: MediaAccess = MediaAccess.FULL,
        missingTypes: Set<MediaType> = emptySet(),
    ): GalleryUiState = stateOf(TimelineFixtures.sampleItems(), access, missingTypes)

    private fun longState(): GalleryUiState =
        stateOf(TimelineFixtures.sampleItems() + TimelineFixtures.longLibrary(), MediaAccess.FULL, emptySet())

    private fun stateOf(
        items: List<MediaItem>,
        access: MediaAccess,
        missingTypes: Set<MediaType>,
    ): GalleryUiState =
        GalleryUiState(
            grid =
                MediaGridState.Content(
                    sections =
                        TimelineGrouping.group(
                            items.sortedByDescending { it.timestamp },
                            GroupingMode.DAY,
                            TODAY,
                            ZONE,
                        ),
                    access = access,
                    missingTypes = missingTypes,
                    zoomLevel = ZoomLevels.DEFAULT_LEVEL,
                    today = TODAY,
                ),
            albums = AlbumList(albumsForDisplay(TimelineFixtures.albumsOf(items))),
        )

    private fun photos() = context.getString(GalleryR.string.feature_gallery_tab_photos)

    private fun albums() = context.getString(GalleryR.string.feature_gallery_tab_albums)

    private fun tab(label: String) = composeRule.onNode(isSelectable() and hasText(label))

    /** Das Raster der Fotos: das einzige Element mit Spaltenbeschreibung. */
    private fun grid() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
}

/** Trifft den Knoten, dessen Beschreibung mit [prefix] beginnt. */
private fun descriptionStartsWith(prefix: String): SemanticsMatcher =
    SemanticsMatcher("content description starts with $prefix") { node ->
        node.config.contains(SemanticsProperties.ContentDescription) &&
            node.config[SemanticsProperties.ContentDescription].any { it.startsWith(prefix) }
    }
