package io.github.labushuya.emberlight.feature.gallery

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.album.AlbumList
import io.github.labushuya.emberlight.feature.gallery.album.albumsForDisplay
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineSection
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import io.github.labushuya.emberlight.feature.gallery.viewer.testPlayback
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Goldens der Zeitleiste und ihrer Zustände. Goldens liegen in `src/test/screenshots` (ADR 0006). Die Daten sind fest:
 * Heute ist der 16. Oktober 2026, die Vorschaubilder sind Testfarben.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Die langen Testdaten hängen hinten an, damit die Farben der kurzen Einträge gleich bleiben.
        TestThumbnails.install(TimelineFixtures.sampleItems() + TimelineFixtures.longLibrary())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun timelinePhoneEnglish() {
        capture(contentState(), "src/test/screenshots/GalleryTimeline_phone_en.png")
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun timelinePhoneGerman() {
        capture(contentState(), "src/test/screenshots/GalleryTimeline_phone_de.png")
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun timelineTabletLandscapeEnglish() {
        capture(contentState(), "src/test/screenshots/GalleryTimeline_tablet_landscape_en.png")
    }

    @Test
    @Config(qualifiers = "en-w673dp-h841dp")
    fun timelineFoldableInnerDisplayEnglish() {
        capture(contentState(), "src/test/screenshots/GalleryTimeline_foldInner_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun timelinePhoneStageZeroEnglish() {
        // Größte Kacheln: zwei Spalten, Tagesgruppen.
        capture(levelContent(0), "src/test/screenshots/GalleryTimeline_phone_level0_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun timelinePhoneStageFourEnglish() {
        // Kleinste Kacheln: sieben Spalten, Monatsgruppen.
        capture(levelContent(ZoomLevels.MAX_LEVEL), "src/test/screenshots/GalleryTimeline_phone_level4_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun fastScrollBubblePhoneEnglish() {
        setContent(
            contentOf(
                TimelineGrouping.group(TimelineFixtures.longLibrary(), GroupingMode.DAY, TODAY, ZONE),
                MediaAccess.FULL,
                emptySet(),
            ),
        )
        composeRule.waitForIdle()
        // Der Griff wird gehalten, die Blase bleibt sichtbar. Erst danach wird losgelassen.
        val rail =
            composeRule.onNodeWithContentDescription(
                context.getString(GalleryR.string.feature_gallery_fast_scroll),
            )
        rail.performTouchInput {
            down(center)
            moveTo(Offset(center.x, height * 0.3f))
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/GalleryFastScroll_phone_en.png")
        rail.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun permissionPhoneEnglish() {
        capture(
            MediaGridState.PermissionRequired(canRequest = true),
            "src/test/screenshots/GalleryPermission_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun partialBannerPhoneEnglish() {
        capture(
            contentOf(sections(), MediaAccess.PARTIAL, emptySet()),
            "src/test/screenshots/GalleryPartialBanner_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun emptyPhoneEnglish() {
        capture(MediaGridState.Empty(MediaAccess.FULL, emptySet()), "src/test/screenshots/GalleryEmpty_phone_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun missingVideosPhoneEnglish() {
        capture(
            // Ohne Videos, wie der Index sie liefert, wenn die Berechtigung dafür fehlt.
            contentOf(
                sections =
                    TimelineGrouping.group(
                        TimelineFixtures.sampleItems().filter { it.type == MediaType.IMAGE },
                        GroupingMode.DAY,
                        TODAY,
                        ZONE,
                    ),
                access = MediaAccess.FULL,
                missingTypes = setOf(MediaType.VIDEO),
            ),
            "src/test/screenshots/GalleryMissingVideos_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun albumsPhoneEnglish() {
        captureAlbums(
            context.getString(GalleryR.string.feature_gallery_tab_albums),
            "src/test/screenshots/GalleryAlbums_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun albumsPhoneGerman() {
        captureAlbums(
            context.getString(GalleryR.string.feature_gallery_tab_albums),
            "src/test/screenshots/GalleryAlbums_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w673dp-h841dp")
    fun albumsFoldableInnerDisplayEnglish() {
        captureAlbums(
            context.getString(GalleryR.string.feature_gallery_tab_albums),
            "src/test/screenshots/GalleryAlbums_foldInner_en.png",
        )
    }

    /** Öffnet den Alben-Reiter per Antippen, wie der Nutzer es täte, und nimmt das Bild auf. */
    private fun captureAlbums(
        tabLabel: String,
        path: String,
    ) {
        setContent(GalleryUiState(grid = contentOf(sections(), MediaAccess.FULL, emptySet()), albums = albumList()))
        // Der Reiter wird über die Bedienungshilfen-Aktion gewählt. Ein Touch würde die Ripple-Spur ins Bild malen.
        composeRule.onNodeWithText(tabLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }

    private fun albumList(): AlbumList =
        AlbumList(
            albumsForDisplay(
                TimelineFixtures.albumsOf(TimelineFixtures.sampleItems() + TimelineFixtures.longLibrary()),
            ),
        )

    private fun setContent(state: GalleryUiState) {
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = state,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    playback = testPlayback(),
                    onAlbumClick = {},
                    onAbout = {},
                    onZoomLevelChange = {},
                )
            }
        }
    }

    private fun capture(
        state: MediaGridState,
        path: String,
    ) {
        setContent(state)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }

    private fun setContent(state: MediaGridState) {
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = GalleryUiState(grid = state, albums = AlbumList(emptyList())),
                    onZoomLevelChange = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                    playback = testPlayback(),
                    onAlbumClick = {},
                    onAbout = {},
                )
            }
        }
    }

    private fun contentOf(
        sections: List<TimelineSection>,
        access: MediaAccess,
        missingTypes: Set<MediaType>,
        zoomLevel: Int = ZoomLevels.DEFAULT_LEVEL,
    ): MediaGridState.Content = MediaGridState.Content(sections, access, missingTypes, zoomLevel, TODAY)

    private fun levelContent(level: Int): MediaGridState.Content =
        contentOf(
            TimelineGrouping.group(TimelineFixtures.sampleItems(), ZoomLevels.groupingFor(level), TODAY, ZONE),
            MediaAccess.FULL,
            emptySet(),
            level,
        )

    private fun sections() = TimelineGrouping.group(TimelineFixtures.sampleItems(), GroupingMode.DAY, TODAY, ZONE)

    private fun contentState() = contentOf(sections(), MediaAccess.FULL, emptySet())
}
