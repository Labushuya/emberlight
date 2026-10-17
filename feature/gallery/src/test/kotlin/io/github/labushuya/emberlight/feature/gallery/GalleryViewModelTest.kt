package io.github.labushuya.emberlight.feature.gallery

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineHeader
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading() =
        runTest(dispatcher) {
            val vm = viewModel(FakeLibraryRepository(MediaLibraryState.Loading), FakePromptStore(asked = false))

            assertEquals(MediaGridState.Loading, vm.uiState.value.grid)
        }

    @Test
    fun noAccessNeverAskedOffersTheDialog() =
        runTest(dispatcher) {
            val vm = viewModel(readyNoAccessRepository(), FakePromptStore(asked = false))

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value.grid)
        }

    @Test
    fun noAccessAfterDeniedForeverOffersOnlySettings() =
        runTest(dispatcher) {
            // Gefragt, und die Systemerklärung ist nicht mehr anzeigbar (rationale = false): nur noch Einstellungen.
            val vm = viewModel(readyNoAccessRepository(), FakePromptStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = false), vm.uiState.value.grid)
        }

    @Test
    fun deniedOnceWithRationaleOffersTheDialogAgain() =
        runTest(dispatcher) {
            val store = FakePromptStore(asked = true)
            val repository = readyNoAccessRepository()
            val vm = viewModel(repository, store)
            collect(vm)
            advanceUntilIdle()

            vm.onPermissionResult(showRationale = true)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value.grid)
            assertTrue(store.askedFlag.value)
            assertEquals(1, repository.refreshCount)
        }

    @Test
    fun permissionResultMarksTheRequestAsAskedOnlyAfterTheAnswer() =
        runTest(dispatcher) {
            val store = FakePromptStore(asked = false)
            val vm = viewModel(readyNoAccessRepository(), store)
            collect(vm)
            advanceUntilIdle()
            // Während der Dialog offen ist, gilt die Anfrage noch nicht als gestellt. Der Dialog bleibt anbietbar.
            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value.grid)
            assertEquals(false, store.askedFlag.value)

            vm.onPermissionResult(showRationale = false)
            advanceUntilIdle()

            assertTrue(store.askedFlag.value)
            assertEquals(MediaGridState.PermissionRequired(canRequest = false), vm.uiState.value.grid)
        }

    @Test
    fun firstResumeDoesNotRereadButLaterResumesDo() =
        runTest(dispatcher) {
            val repository = readyNoAccessRepository()
            val vm = viewModel(repository, FakePromptStore(asked = false))

            vm.onResumed(showRationale = false)
            assertEquals(0, repository.refreshCount)

            vm.onResumed(showRationale = false)
            assertEquals(1, repository.refreshCount)
        }

    @Test
    fun emptyLibraryWithAccessIsEmpty() =
        runTest(dispatcher) {
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library(items = emptyList()))),
                    FakePromptStore(asked = true),
                )

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.Empty(MediaAccess.FULL, emptySet()), vm.uiState.value.grid)
        }

    @Test
    fun contentGroupsByDayForDefaultColumns() =
        runTest(dispatcher) {
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakePromptStore(asked = true),
                )

            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value.grid as MediaGridState.Content
            assertEquals(TimelineHeader.Today, content.sections.first().header)
            assertEquals(MediaAccess.FULL, content.access)
        }

    @Test
    fun changingZoomLevelRegroupsByMonth() =
        runTest(dispatcher) {
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakePromptStore(asked = true),
                )
            collect(vm)
            advanceUntilIdle()

            vm.onZoomLevelChanged(ZoomLevels.LAST_DAY_LEVEL + 1)
            advanceUntilIdle()

            val content = vm.uiState.value.grid as MediaGridState.Content
            assertEquals(TimelineHeader.Month(YearMonth.of(2026, 10)), content.sections.first().header)
            assertEquals(ZoomLevels.LAST_DAY_LEVEL + 1, content.zoomLevel)
        }

    @Test
    fun savedZoomLevelIsRestoredAtStart() =
        runTest(dispatcher) {
            val zoomStore = FakeZoomStore(saved = ZoomLevels.MAX_LEVEL)
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakePromptStore(asked = true),
                    zoomStore,
                )
            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value.grid as MediaGridState.Content
            assertEquals(ZoomLevels.MAX_LEVEL, content.zoomLevel)
            assertEquals(TimelineHeader.Month(YearMonth.of(2026, 10)), content.sections.first().header)
        }

    @Test
    fun defaultLevelIsUsedWithoutSavedValue() =
        runTest(dispatcher) {
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakePromptStore(asked = true),
                    FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL),
                )
            collect(vm)
            advanceUntilIdle()

            assertEquals(ZoomLevels.DEFAULT_LEVEL, (vm.uiState.value.grid as MediaGridState.Content).zoomLevel)
        }

    @Test
    fun userChoiceIsPersistedAndClamped() =
        runTest(dispatcher) {
            val zoomStore = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL)
            val vm =
                viewModel(
                    FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakePromptStore(asked = true),
                    zoomStore,
                )
            collect(vm)
            advanceUntilIdle()

            vm.onZoomLevelChanged(99)
            advanceUntilIdle()

            assertEquals(ZoomLevels.MAX_LEVEL, zoomStore.saved)
            assertEquals(ZoomLevels.MAX_LEVEL, (vm.uiState.value.grid as MediaGridState.Content).zoomLevel)
        }

    @Test
    fun missingTypeReachesTheContentState() =
        runTest(dispatcher) {
            val library = TimelineFixtures.library(missingTypes = setOf(MediaType.VIDEO))
            val vm = viewModel(FakeLibraryRepository(MediaLibraryState.Ready(library)), FakePromptStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(setOf(MediaType.VIDEO), (vm.uiState.value.grid as MediaGridState.Content).missingTypes)
        }

    private fun TestScope.collect(vm: GalleryViewModel) {
        backgroundScope.launch { vm.uiState.collect { } }
    }

    private fun viewModel(
        repository: MediaRepository,
        store: PermissionPromptStore,
        zoomStore: ZoomLevelStore = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL),
    ): GalleryViewModel =
        GalleryViewModel(
            repository = repository,
            permissionPrompts = store,
            zoomLevels = zoomStore,
            defaultDispatcher = dispatcher,
            clock = TimelineFixtures.clock(),
            zone = ZONE,
        )

    private fun readyNoAccessRepository(): FakeLibraryRepository =
        FakeLibraryRepository(
            MediaLibraryState.Ready(TimelineFixtures.library(items = emptyList(), access = MediaAccess.NONE)),
        )
}
