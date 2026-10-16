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
            val vm = viewModel(FakeRepository(MediaLibraryState.Loading), FakeStore(asked = false))

            assertEquals(MediaGridState.Loading, vm.uiState.value)
        }

    @Test
    fun noAccessNeverAskedOffersTheDialog() =
        runTest(dispatcher) {
            val vm = viewModel(readyNoAccessRepository(), FakeStore(asked = false))

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value)
        }

    @Test
    fun noAccessAfterDeniedForeverOffersOnlySettings() =
        runTest(dispatcher) {
            // Gefragt, und die Systemerklärung ist nicht mehr anzeigbar (rationale = false): nur noch Einstellungen.
            val vm = viewModel(readyNoAccessRepository(), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = false), vm.uiState.value)
        }

    @Test
    fun deniedOnceWithRationaleOffersTheDialogAgain() =
        runTest(dispatcher) {
            val store = FakeStore(asked = true)
            val repository = readyNoAccessRepository()
            val vm = viewModel(repository, store)
            collect(vm)
            advanceUntilIdle()

            vm.onPermissionResult(showRationale = true)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value)
            assertTrue(store.askedFlag.value)
            assertEquals(1, repository.refreshCount)
        }

    @Test
    fun permissionResultMarksTheRequestAsAskedOnlyAfterTheAnswer() =
        runTest(dispatcher) {
            val store = FakeStore(asked = false)
            val vm = viewModel(readyNoAccessRepository(), store)
            collect(vm)
            advanceUntilIdle()
            // Während der Dialog offen ist, gilt die Anfrage noch nicht als gestellt. Der Dialog bleibt anbietbar.
            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value)
            assertEquals(false, store.askedFlag.value)

            vm.onPermissionResult(showRationale = false)
            advanceUntilIdle()

            assertTrue(store.askedFlag.value)
            assertEquals(MediaGridState.PermissionRequired(canRequest = false), vm.uiState.value)
        }

    @Test
    fun firstResumeDoesNotRereadButLaterResumesDo() =
        runTest(dispatcher) {
            val repository = readyNoAccessRepository()
            val vm = viewModel(repository, FakeStore(asked = false))

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
                    FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library(items = emptyList()))),
                    FakeStore(asked = true),
                )

            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.Empty(MediaAccess.FULL, emptySet()), vm.uiState.value)
        }

    @Test
    fun contentGroupsByDayForDefaultColumns() =
        runTest(dispatcher) {
            val vm =
                viewModel(FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value as MediaGridState.Content
            assertEquals(TimelineHeader.Today, content.sections.first().header)
            assertEquals(MediaAccess.FULL, content.access)
        }

    @Test
    fun changingZoomLevelRegroupsByMonth() =
        runTest(dispatcher) {
            val vm =
                viewModel(FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())), FakeStore(asked = true))
            collect(vm)
            advanceUntilIdle()

            vm.onZoomLevelChanged(ZoomLevels.LAST_DAY_LEVEL + 1)
            advanceUntilIdle()

            val content = vm.uiState.value as MediaGridState.Content
            assertEquals(TimelineHeader.Month(YearMonth.of(2026, 10)), content.sections.first().header)
            assertEquals(ZoomLevels.LAST_DAY_LEVEL + 1, content.zoomLevel)
        }

    @Test
    fun savedZoomLevelIsRestoredAtStart() =
        runTest(dispatcher) {
            val zoomStore = FakeZoomStore(saved = ZoomLevels.MAX_LEVEL)
            val vm =
                viewModel(
                    FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakeStore(asked = true),
                    zoomStore,
                )
            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value as MediaGridState.Content
            assertEquals(ZoomLevels.MAX_LEVEL, content.zoomLevel)
            assertEquals(TimelineHeader.Month(YearMonth.of(2026, 10)), content.sections.first().header)
        }

    @Test
    fun defaultLevelIsUsedWithoutSavedValue() =
        runTest(dispatcher) {
            val vm =
                viewModel(
                    FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakeStore(asked = true),
                    FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL),
                )
            collect(vm)
            advanceUntilIdle()

            assertEquals(ZoomLevels.DEFAULT_LEVEL, (vm.uiState.value as MediaGridState.Content).zoomLevel)
        }

    @Test
    fun userChoiceIsPersistedAndClamped() =
        runTest(dispatcher) {
            val zoomStore = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL)
            val vm =
                viewModel(
                    FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakeStore(asked = true),
                    zoomStore,
                )
            collect(vm)
            advanceUntilIdle()

            vm.onZoomLevelChanged(99)
            advanceUntilIdle()

            assertEquals(ZoomLevels.MAX_LEVEL, zoomStore.saved)
            assertEquals(ZoomLevels.MAX_LEVEL, (vm.uiState.value as MediaGridState.Content).zoomLevel)
        }

    @Test
    fun missingTypeReachesTheContentState() =
        runTest(dispatcher) {
            val library = TimelineFixtures.library(missingTypes = setOf(MediaType.VIDEO))
            val vm = viewModel(FakeRepository(MediaLibraryState.Ready(library)), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(setOf(MediaType.VIDEO), (vm.uiState.value as MediaGridState.Content).missingTypes)
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

    private fun readyNoAccessRepository(): FakeRepository =
        FakeRepository(
            MediaLibraryState.Ready(TimelineFixtures.library(items = emptyList(), access = MediaAccess.NONE)),
        )

    private class FakeRepository(
        initial: MediaLibraryState,
    ) : MediaRepository {
        private val state = MutableStateFlow(initial)
        var refreshCount = 0
            private set

        override val library: StateFlow<MediaLibraryState> = state

        override fun refresh() {
            refreshCount++
        }
    }

    private class FakeZoomStore(
        saved: Int,
    ) : ZoomLevelStore {
        private val flow = MutableStateFlow(saved)
        val saved: Int get() = flow.value

        override val level: Flow<Int> = flow

        override suspend fun setLevel(level: Int) {
            flow.value = ZoomLevels.clamp(level)
        }
    }

    private class FakeStore(
        asked: Boolean,
    ) : PermissionPromptStore {
        val askedFlag = MutableStateFlow(asked)
        override val askedBefore: Flow<Boolean> = askedFlag

        override suspend fun markAsked() {
            askedFlag.value = true
        }
    }
}
