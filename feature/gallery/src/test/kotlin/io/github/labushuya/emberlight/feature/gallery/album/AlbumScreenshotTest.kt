package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import io.github.labushuya.emberlight.feature.gallery.viewer.testPlayback
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden der Album-Ansicht. Goldens liegen in `src/test/screenshots` (ADR 0006). Die Daten sind fest, das Album heißt
 * „Camera“ und hat 404 Einträge, wie in der Alben-Übersicht.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Wie der Index: absteigend nach Zeitstempel sortiert, sonst entstehen doppelte Gruppen.
    private val items =
        (
            TimelineFixtures.sampleItems().filter { it.bucketId == TimelineFixtures.CAMERA_BUCKET } +
                TimelineFixtures.longLibrary()
        ).sortedByDescending { it.timestamp }

    @Before
    fun setUp() {
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun albumPhoneEnglish() {
        val album = TimelineFixtures.albumsOf(items).first { it.bucketId == TimelineFixtures.CAMERA_BUCKET }
        composeRule.setContent {
            EmberlightTheme {
                AlbumContent(
                    state =
                        AlbumDetailUiState(
                            album = album,
                            grid =
                                MediaGridState.Content(
                                    sections =
                                        TimelineGrouping.group(
                                            items,
                                            ZoomLevels.groupingFor(ZoomLevels.DEFAULT_LEVEL),
                                            TODAY,
                                            ZONE,
                                        ),
                                    access = MediaAccess.FULL,
                                    missingTypes = emptySet(),
                                    zoomLevel = ZoomLevels.DEFAULT_LEVEL,
                                    today = TODAY,
                                ),
                        ),
                    onBack = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                    playback = testPlayback(),
                    onZoomLevelChange = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/AlbumDetail_phone_en.png")
    }
}
