package io.github.labushuya.emberlight.feature.gallery.album

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import io.github.labushuya.emberlight.feature.gallery.timeline.mediaGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.pinchApart
import io.github.labushuya.emberlight.feature.gallery.viewer.VIEWER_OVERLAY_TAG
import io.github.labushuya.emberlight.feature.gallery.viewer.testPlayback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Album-Ansicht im Compose-Test: Titel, Zurück-Pfeil und Zurück-Knopf bei verschwundenem Album, das gemeinsame Raster mit
 * Pinch und Antippen, Berechtigung und Banner.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class AlbumContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cameraItems = TimelineFixtures.longLibrary()

    @Before
    fun setUp() {
        TestThumbnails.install(cameraItems)
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun showsTheAlbumNameAndItsGrid() {
        show(contentState(), album = camera())

        composeRule.onNodeWithText("Camera").assertIsDisplayed()
        composeRule.mediaGrid().assertIsDisplayed()
    }

    @Test
    fun otherAlbumIsShownAsOther() {
        val other = TimelineFixtures.albumsOf(TimelineFixtures.sampleItems()).first { it.bucketId == null }
        show(contentState(), album = other)

        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_album_other)).assertIsDisplayed()
    }

    @Test
    fun backArrowAndTheGoneButtonBothGoBack() {
        var backs = 0
        show(contentState(), album = camera(), onBack = { backs++ })

        composeRule
            .onNode(
                hasContentDescription(context.getString(GalleryR.string.feature_gallery_back)),
            ).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun aVanishedAlbumSaysSoAndOffersBack() {
        var backs = 0
        show(MediaGridState.Gone(MediaAccess.FULL, emptySet()), album = null, onBack = { backs++ })

        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_album_gone)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_back)).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun noAccessShowsThePromptInTheAlbum() {
        show(MediaGridState.PermissionRequired(canRequest = true), album = camera())

        composeRule
            .onNodeWithText(
                context.getString(GalleryR.string.feature_gallery_permission_title),
            ).assertIsDisplayed()
    }

    @Test
    fun partialBannerAppearsInTheAlbum() {
        show(contentState(access = MediaAccess.PARTIAL), album = camera())

        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_partial_text)).assertIsDisplayed()
    }

    @Test
    fun tappingAMediaCellOpensTheViewerOverTheGrid() {
        show(contentState(), album = camera())

        composeRule.onAllNodes(hasContentDescription("Photo from", substring = true)).onFirst().performClick()
        composeRule.waitForIdle()

        // Der Viewer liegt über dem Raster. Das Raster bleibt im Baum, der Viewer ist einmal offen.
        composeRule.onAllNodesWithTag(VIEWER_OVERLAY_TAG).assertCountEquals(1)
        composeRule.onAllNodes(hasContentDescription("Photo from", substring = true)).onFirst().assertExists()
    }

    @Test
    fun pinchInTheAlbumChangesTheStage() {
        val harness = Harness(level = ZoomLevels.DEFAULT_LEVEL)
        harness.show(composeRule)

        composeRule.mediaGrid().performTouchInput { pinchApart() }
        composeRule.waitForIdle()

        assertTrue(harness.changes.isNotEmpty())
        assertEquals(ZoomLevels.DEFAULT_LEVEL - 1, harness.changes.first())
    }

    @Test
    fun albumsGridIsTheSharedMediaGrid() {
        // Die Album-Ansicht zeigt dasselbe Raster wie die Galerie: Tagesüberschriften und Zellen.
        show(contentState(), album = camera())

        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_header_today)).assertIsDisplayed()
        composeRule
            .onAllNodes(
                hasText(context.getString(GalleryR.string.feature_gallery_header_today)) and isHeading(),
            ).assertCountEquals(1)
    }

    /** Festes Zustandsmodell: Jede angeforderte Stufe wird gemeldet und übernommen. */
    private inner class Harness(
        level: Int,
    ) {
        var level by mutableIntStateOf(level)
        val changes = mutableListOf<Int>()

        fun show(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule) {
            rule.setContent {
                EmberlightTheme {
                    AlbumContent(
                        state = AlbumDetailUiState(album = camera(), grid = contentState(level = level)),
                        onBack = {},
                        onRequestPermission = {},
                        onOpenSettings = {},
                        playback = testPlayback(),
                        onZoomLevelChange = { next ->
                            changes += next
                            level = next
                        },
                    )
                }
            }
        }
    }

    private fun show(
        grid: MediaGridState,
        album: Album?,
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            EmberlightTheme {
                AlbumContent(
                    state = AlbumDetailUiState(album = album, grid = grid),
                    onBack = onBack,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    playback = testPlayback(),
                    onZoomLevelChange = {},
                )
            }
        }
    }

    private fun contentState(
        access: MediaAccess = MediaAccess.FULL,
        level: Int = ZoomLevels.DEFAULT_LEVEL,
    ): MediaGridState.Content =
        MediaGridState.Content(
            sections = TimelineGrouping.group(cameraItems, ZoomLevels.groupingFor(level), TODAY, ZONE),
            access = access,
            missingTypes = emptySet(),
            zoomLevel = level,
            today = TODAY,
        )

    private fun camera(): Album =
        TimelineFixtures.albumsOf(cameraItems).first { it.bucketId == TimelineFixtures.CAMERA_BUCKET }
}
