package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.DEFAULT_COLUMNS
import io.github.labushuya.emberlight.feature.gallery.timeline.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens der Zeitleiste und ihrer Zustände. Goldens liegen in `src/test/screenshots` (ADR 0006). Die Daten sind fest:
 * Heute ist der 16. Oktober 2026, die Vorschaubilder sind Testfarben.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
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
    @Config(qualifiers = "en-w412dp-h915dp")
    fun permissionPhoneEnglish() {
        capture(
            TimelineUiState.PermissionRequired(canRequest = true),
            "src/test/screenshots/GalleryPermission_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun partialBannerPhoneEnglish() {
        capture(
            TimelineUiState.Content(sections(), MediaAccess.PARTIAL, emptySet()),
            "src/test/screenshots/GalleryPartialBanner_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun emptyPhoneEnglish() {
        capture(TimelineUiState.Empty(MediaAccess.FULL, emptySet()), "src/test/screenshots/GalleryEmpty_phone_en.png")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun missingVideosPhoneEnglish() {
        capture(
            // Ohne Videos, wie der Index sie liefert, wenn die Berechtigung dafür fehlt.
            TimelineUiState.Content(
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

    private fun capture(
        state: TimelineUiState,
        path: String,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = state,
                    columns = DEFAULT_COLUMNS,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onMediaClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }

    private fun sections() = TimelineGrouping.group(TimelineFixtures.sampleItems(), GroupingMode.DAY, TODAY, ZONE)

    private fun contentState() = TimelineUiState.Content(sections(), MediaAccess.FULL, emptySet())
}
