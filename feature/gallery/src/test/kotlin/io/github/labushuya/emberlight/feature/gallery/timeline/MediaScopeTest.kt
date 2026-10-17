package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geltungsbereich der Rasteransichten: alle Einträge der Galerie oder nur ein Album, und was ein leeres Album bedeutet. */
class MediaScopeTest {
    @Test
    fun albumScopeSelectsOnlyItsOwnItems() {
        val result = state(scope = MediaScope.Bucket(TimelineFixtures.SCREENSHOT_BUCKET))

        val content = result as MediaGridState.Content
        assertEquals(listOf(7L), content.sections.flatMap { it.items }.map { it.id })
    }

    @Test
    fun nullBucketSelectsTheItemsWithoutFolder() {
        val result = state(scope = MediaScope.Bucket(null))

        val content = result as MediaGridState.Content
        assertEquals(listOf(4L), content.sections.flatMap { it.items }.map { it.id })
    }

    @Test
    fun albumWithoutItemsIsGone() {
        val result = state(scope = MediaScope.Bucket(42L))

        assertEquals(MediaGridState.Gone(MediaAccess.FULL, emptySet()), result)
    }

    @Test
    fun goneKeepsTheMissingTypesOfTheLibrary() {
        val result =
            state(
                scope = MediaScope.Bucket(42L),
                library = libraryOf(missingTypes = setOf(MediaType.VIDEO)),
            )

        assertEquals(MediaGridState.Gone(MediaAccess.FULL, setOf(MediaType.VIDEO)), result)
    }

    @Test
    fun partialAccessWithoutTheAlbumsItemsIsGone() {
        val result =
            state(
                scope = MediaScope.Bucket(TimelineFixtures.DOWNLOAD_BUCKET),
                library = libraryOf(access = MediaAccess.PARTIAL, items = listOf(TimelineFixtures.item(9, 1L))),
            )

        assertEquals(MediaGridState.Gone(MediaAccess.PARTIAL, emptySet()), result)
    }

    @Test
    fun noAccessAfterAskingOffersOnlySettingsForAlbums() {
        val result =
            state(scope = MediaScope.Bucket(1L), library = libraryOf(access = MediaAccess.NONE, items = emptyList()))

        assertEquals(MediaGridState.PermissionRequired(canRequest = false), result)
    }

    @Test
    fun galleryWithoutItemsIsEmptyNeverGone() {
        val result = state(scope = MediaScope.All, library = libraryOf(items = emptyList()))

        assertEquals(MediaGridState.Empty(MediaAccess.FULL, emptySet()), result)
    }

    @Test
    fun scopedItemsReturnsTheSameListForTheSameSource() {
        val scoped = ScopedItems(MediaScope.Bucket(TimelineFixtures.CAMERA_BUCKET))
        val source = TimelineFixtures.sampleItems()

        val first = scoped.of(source)
        val second = scoped.of(source)

        assertSame(first, second)
        assertEquals(4, first.size)
    }

    @Test
    fun scopedItemsRecomputesForANewSource() {
        val scoped = ScopedItems(MediaScope.Bucket(TimelineFixtures.CAMERA_BUCKET))
        val first = scoped.of(TimelineFixtures.sampleItems())

        val second = scoped.of(TimelineFixtures.sampleItems())

        assertTrue(first !== second)
        assertEquals(first, second)
    }

    @Test
    fun allScopeReturnsTheSourceUnchanged() {
        val source = TimelineFixtures.sampleItems()

        assertSame(source, ScopedItems(MediaScope.All).of(source))
    }

    @Test
    fun bucketScopeMatchesTheBucketIdIncludingNull() {
        val item = TimelineFixtures.item(1, 1L, bucketId = null, bucketName = null)

        assertTrue(MediaScope.Bucket(null).contains(item))
        assertTrue(!MediaScope.Bucket(TimelineFixtures.CAMERA_BUCKET).contains(item))
        assertTrue(MediaScope.All.contains(item))
    }

    private fun state(
        scope: MediaScope,
        library: MediaLibrary = libraryOf(),
    ): MediaGridState =
        mediaGridState(
            library = MediaLibraryState.Ready(library),
            scope = scope,
            askedBefore = true,
            showRationale = false,
            zoomLevel = ZoomLevels.DEFAULT_LEVEL,
            today = TODAY,
            zone = ZONE,
        )

    private fun libraryOf(
        items: List<io.github.labushuya.emberlight.core.media.model.MediaItem> = TimelineFixtures.sampleItems(),
        access: MediaAccess = MediaAccess.FULL,
        missingTypes: Set<MediaType> = emptySet(),
    ): MediaLibrary = TimelineFixtures.library(items = items, access = access, missingTypes = missingTypes)
}
