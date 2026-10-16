package io.github.labushuya.emberlight.feature.gallery

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineSection
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineUiState
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Zustände der Zeitleiste mit festen Daten. Die Texte kommen aus den Ressourcen, damit der Test für Deutsch und
 * Englisch gleich bleibt. Vorschaubilder sind deterministische Testfarben (coil-test).
 */
@RunWith(AndroidJUnit4::class)
class GalleryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun permissionDialogIsRequestedByItsButton() {
        var requests = 0
        setContent(TimelineUiState.PermissionRequired(canRequest = true), onRequest = { requests++ })

        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_permission_allow)).performClick()

        assertEquals(1, requests)
    }

    @Test
    fun permissionWithoutDialogOpensSettingsInstead() {
        var requests = 0
        var settings = 0
        setContent(
            TimelineUiState.PermissionRequired(canRequest = false),
            onRequest = { requests++ },
            onOpenSettings = { settings++ },
        )

        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_permission_open_settings)).performClick()

        assertEquals(0, requests)
        assertEquals(1, settings)
        composeRule
            .onNodeWithText(
                string(GalleryR.string.feature_gallery_permission_settings_title),
            ).assertIsDisplayed()
    }

    @Test
    fun partialBannerOffersSelectMoreAndAllowAll() {
        var more = 0
        var all = 0
        setContent(partialContent(), onRequest = { more++ }, onOpenSettings = { all++ })

        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_partial_text)).assertIsDisplayed()
        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_partial_more)).performClick()
        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_partial_all)).performClick()

        assertEquals(1, more)
        assertEquals(1, all)
    }

    @Test
    fun gridShowsDayHeadingsAndCells() {
        setContent(contentState())

        composeRule
            .onNode(hasText(string(GalleryR.string.feature_gallery_header_today)) and isHeading())
            .assertIsDisplayed()
        composeRule
            .onNode(hasText(string(GalleryR.string.feature_gallery_header_yesterday)) and isHeading())
            .assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("Photo from", substring = true)).onFirst().assertIsDisplayed()
    }

    @Test
    fun videoCellShowsDurationAndSpokenLabel() {
        setContent(contentState())

        composeRule.onNodeWithText("0:42", useUnmergedTree = true).assertIsDisplayed()

        composeRule
            .onNode(hasContentDescription("Video, 0:42, from", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun hourLongVideoShowsHoursInItsBadge() {
        val hourLong =
            TimelineFixtures.item(
                4,
                TimelineFixtures.millis("2026-07-02T21:10"),
                MediaType.VIDEO,
                durationMs = 3_723_000L,
            )
        val sections = TimelineGrouping.group(listOf(hourLong), GroupingMode.DAY, TODAY, ZONE)
        setContent(contentOf(sections, MediaAccess.FULL, emptySet()))

        composeRule.onNodeWithText("1:02:03", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyStateExplainsItself() {
        setContent(TimelineUiState.Empty(MediaAccess.FULL, emptySet()))

        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_empty_title)).assertIsDisplayed()
    }

    @Test
    fun missingVideosGiveAHintWithARequestAction() {
        var requests = 0
        setContent(
            contentOf(
                sections = sections(),
                access = MediaAccess.FULL,
                missingTypes = setOf(MediaType.VIDEO),
            ),
            onRequest = { requests++ },
        )

        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_missing_videos)).assertIsDisplayed()
        composeRule.onNodeWithText(string(GalleryR.string.feature_gallery_permission_allow)).performClick()

        assertEquals(1, requests)
    }

    private fun setContent(
        state: TimelineUiState,
        onRequest: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            EmberlightTheme {
                GalleryContent(
                    state = state,
                    onZoomLevelChange = {},
                    onRequestPermission = onRequest,
                    onOpenSettings = onOpenSettings,
                    onMediaClick = {},
                )
            }
        }
    }

    private fun contentOf(
        sections: List<TimelineSection>,
        access: MediaAccess,
        missingTypes: Set<MediaType>,
        zoomLevel: Int = ZoomLevels.DEFAULT_LEVEL,
    ): TimelineUiState.Content = TimelineUiState.Content(sections, access, missingTypes, zoomLevel, TODAY)

    private fun sections() = TimelineGrouping.group(TimelineFixtures.sampleItems(), GroupingMode.DAY, TODAY, ZONE)

    private fun contentState() = contentOf(sections(), MediaAccess.FULL, emptySet())

    private fun partialContent() = contentOf(sections(), MediaAccess.PARTIAL, emptySet())

    private fun string(id: Int): String = context.getString(id)
}
