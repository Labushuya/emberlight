package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineStateTest {
    @Test
    fun loadingStaysLoading() {
        assertEquals(TimelineUiState.Loading, state(MediaLibraryState.Loading))
    }

    @Test
    fun noAccessNeverAskedAllowsTheDialog() {
        val result =
            state(ready(items = emptyList(), access = MediaAccess.NONE), askedBefore = false, showRationale = false)

        assertEquals(TimelineUiState.PermissionRequired(canRequest = true), result)
    }

    @Test
    fun noAccessAskedWithRationaleAllowsTheDialogAgain() {
        val result =
            state(ready(items = emptyList(), access = MediaAccess.NONE), askedBefore = true, showRationale = true)

        assertEquals(TimelineUiState.PermissionRequired(canRequest = true), result)
    }

    @Test
    fun noAccessAskedWithoutRationaleOnlyOffersSettings() {
        // Der Nutzer hat dauerhaft verweigert: Der Dialog erscheint nicht mehr.
        val result =
            state(ready(items = emptyList(), access = MediaAccess.NONE), askedBefore = true, showRationale = false)

        assertEquals(TimelineUiState.PermissionRequired(canRequest = false), result)
    }

    @Test
    fun fullAccessWithoutItemsIsEmpty() {
        val result = state(ready(items = emptyList()))

        assertEquals(TimelineUiState.Empty(MediaAccess.FULL, emptySet()), result)
    }

    @Test
    fun partialAccessWithoutSelectionIsEmptyAndKeepsPartialAccess() {
        val result = state(ready(items = emptyList(), access = MediaAccess.PARTIAL))

        assertEquals(TimelineUiState.Empty(MediaAccess.PARTIAL, emptySet()), result)
    }

    @Test
    fun itemsGiveContentWithSectionsAndAccess() {
        val result = state(ready())

        assertTrue(result is TimelineUiState.Content)
        val content = result as TimelineUiState.Content
        assertEquals(MediaAccess.FULL, content.access)
        assertEquals(emptySet<MediaType>(), content.missingTypes)
        assertEquals(TimelineHeader.Today, content.sections.first().header)
        assertEquals(8, content.sections.sumOf { it.items.size })
    }

    @Test
    fun oneMissingTypeIsReportedInContent() {
        // Nur Fotos freigegeben: die Videos fehlen, die Fotos bleiben sichtbar.
        val library = ready(access = MediaAccess.FULL, missingTypes = setOf(MediaType.VIDEO))

        val content = state(library) as TimelineUiState.Content

        assertEquals(setOf(MediaType.VIDEO), content.missingTypes)
    }

    @Test
    fun oneMissingTypeWithoutItemsIsEmptyWithHint() {
        val library = ready(items = emptyList(), access = MediaAccess.FULL, missingTypes = setOf(MediaType.IMAGE))

        val result = state(library)

        assertEquals(TimelineUiState.Empty(MediaAccess.FULL, setOf(MediaType.IMAGE)), result)
    }

    @Test
    fun zoomStageSelectsGroupingModeNotColumns() {
        val dayStage = state(ready(), zoomLevel = ZoomLevels.LAST_DAY_LEVEL) as TimelineUiState.Content
        val monthStage = state(ready(), zoomLevel = ZoomLevels.LAST_DAY_LEVEL + 1) as TimelineUiState.Content

        assertEquals(TimelineHeader.Today, dayStage.sections.first().header)
        assertEquals(TimelineHeader.Month(java.time.YearMonth.of(2026, 10)), monthStage.sections.first().header)
        assertEquals(ZoomLevels.LAST_DAY_LEVEL + 1, monthStage.zoomLevel)
        assertEquals(TODAY, monthStage.today)
    }

    private fun ready(
        items: List<io.github.labushuya.emberlight.core.media.model.MediaItem> = TimelineFixtures.sampleItems(),
        access: MediaAccess = MediaAccess.FULL,
        missingTypes: Set<MediaType> = emptySet(),
    ): MediaLibraryState.Ready = MediaLibraryState.Ready(TimelineFixtures.library(items, access, missingTypes))

    private fun state(
        library: MediaLibraryState,
        askedBefore: Boolean = true,
        showRationale: Boolean = false,
        zoomLevel: Int = ZoomLevels.DEFAULT_LEVEL,
    ): TimelineUiState =
        timelineUiState(
            library = library,
            askedBefore = askedBefore,
            showRationale = showRationale,
            zoomLevel = zoomLevel,
            today = TODAY,
            zone = ZONE,
        )
}
