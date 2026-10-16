package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Albumkarte: Name, Anzahl mit Plural und Zahlenformat der Sprache, Kennzeichnung eines Videos am Titelbild, Sammelalbum
 * „Sonstige“ und der Name für Ordner ohne Namen. Die Karte ist ein einziger Button mit einer Beschreibung.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AlbumCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        TestThumbnails.install(listOf(image(1), video(2)))
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    @Config(qualifiers = "en")
    fun countUsesEnglishPluralAndThousandsSeparator() {
        show(album(bucketId = 1L, name = "Camera", itemCount = 1_234, cover = image(1)))

        composeRule.onNode(hasDescription("Camera, 1,234 items")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun countUsesGermanPluralAndThousandsSeparator() {
        show(album(bucketId = 1L, name = "Camera", itemCount = 1_234, cover = image(1)))

        composeRule.onNode(hasDescription("Camera, 1.234 Elemente")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun singleItemUsesTheSingularForm() {
        show(album(bucketId = 1L, name = "Camera", itemCount = 1, cover = image(1)))

        composeRule.onNode(hasDescription("Camera, 1 Element")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun otherAlbumIsCalledOther() {
        show(album(bucketId = null, name = "", itemCount = 2, cover = image(1)))

        composeRule.onNode(hasDescription("Other, 2 items")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun otherAlbumIsCalledSonstigeInGerman() {
        show(album(bucketId = null, name = "", itemCount = 2, cover = image(1)))

        composeRule.onNode(hasDescription("Sonstige, 2 Elemente")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun folderWithoutNameGetsAFallbackTitle() {
        show(album(bucketId = 9L, name = "", itemCount = 1, cover = image(1)))

        composeRule.onNode(hasDescription("Unnamed album, 1 item")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun videoCoverIsMarked() {
        show(album(bucketId = 1L, name = "Camera", itemCount = 3, cover = video(2)))

        composeRule.onAllNodes(hasTestTag(ALBUM_VIDEO_BADGE_TAG), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "en")
    fun imageCoverIsNotMarkedAsVideo() {
        show(album(bucketId = 1L, name = "Camera", itemCount = 3, cover = image(1)))

        composeRule.onAllNodes(hasTestTag(ALBUM_VIDEO_BADGE_TAG), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en")
    fun cardIsOneButtonThatReportsItsBucketOnTap() {
        var taps = 0
        show(album(bucketId = 1L, name = "Camera", itemCount = 3, cover = image(1))) { taps++ }

        composeRule.onNode(hasDescription("Camera, 3 items")).performClick()

        assertEquals(1, taps)
    }

    private fun show(
        album: Album,
        onClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            EmberlightTheme {
                AlbumCard(album = album, onClick = onClick)
            }
        }
    }

    private fun hasDescription(text: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    private fun album(
        bucketId: Long?,
        name: String,
        itemCount: Int,
        cover: MediaItem,
    ): Album =
        Album(
            bucketId = bucketId,
            name = name,
            cover = cover,
            itemCount = itemCount,
            latestTimestamp = cover.timestamp,
        )

    private fun image(id: Long): MediaItem = TimelineFixtures.item(id, TimelineFixtures.millis("2026-10-16T09:30"))

    private fun video(id: Long): MediaItem =
        TimelineFixtures.item(id, TimelineFixtures.millis("2026-10-16T09:30"), MediaType.VIDEO, durationMs = 42_000L)
}
