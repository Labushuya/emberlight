package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
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

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {
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

            assertEquals(TimelineUiState.Loading, vm.uiState.value)
        }

    @Test
    fun noAccessNeverAskedOffersTheDialog() =
        runTest(dispatcher) {
            val vm = viewModel(readyNoAccess(), FakeStore(asked = false))

            collect(vm)
            advanceUntilIdle()

            assertEquals(TimelineUiState.PermissionRequired(canRequest = true), vm.uiState.value)
        }

    @Test
    fun noAccessAfterDeniedForeverOffersOnlySettings() =
        runTest(dispatcher) {
            // Gefragt, und die Systemerklärung ist nicht mehr anzeigbar (rationale = false): nur noch Einstellungen.
            val vm = viewModel(readyNoAccess(), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(TimelineUiState.PermissionRequired(canRequest = false), vm.uiState.value)
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

            assertEquals(TimelineUiState.PermissionRequired(canRequest = true), vm.uiState.value)
            assertTrue(store.askedFlag.value)
            assertEquals(1, repository.refreshCount)
        }

    @Test
    fun permissionResultMarksTheRequestAsAskedOnlyAfterTheAnswer() =
        runTest(dispatcher) {
            val store = FakeStore(asked = false)
            val vm = viewModel(readyNoAccess(), store)
            collect(vm)
            advanceUntilIdle()
            // Während der Dialog offen ist, gilt die Anfrage noch nicht als gestellt. Der Dialog bleibt anbietbar.
            assertEquals(TimelineUiState.PermissionRequired(canRequest = true), vm.uiState.value)
            assertEquals(false, store.askedFlag.value)

            vm.onPermissionResult(showRationale = false)
            advanceUntilIdle()

            assertTrue(store.askedFlag.value)
            assertEquals(TimelineUiState.PermissionRequired(canRequest = false), vm.uiState.value)
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

            assertEquals(TimelineUiState.Empty(MediaAccess.FULL, emptySet()), vm.uiState.value)
        }

    @Test
    fun contentGroupsByDayForDefaultColumns() =
        runTest(dispatcher) {
            val vm =
                viewModel(FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value as TimelineUiState.Content
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

            val content = vm.uiState.value as TimelineUiState.Content
            assertEquals(TimelineHeader.Month(java.time.YearMonth.of(2026, 10)), content.sections.first().header)
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

            val content = vm.uiState.value as TimelineUiState.Content
            assertEquals(ZoomLevels.MAX_LEVEL, content.zoomLevel)
            assertEquals(TimelineHeader.Month(java.time.YearMonth.of(2026, 10)), content.sections.first().header)
        }

    @Test
    fun defaultLevelIsUsedWithoutSavedValue() =
        runTest(dispatcher) {
            val vm =
                viewModel(FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())), FakeStore(asked = true))
            collect(vm)
            advanceUntilIdle()

            assertEquals(ZoomLevels.DEFAULT_LEVEL, (vm.uiState.value as TimelineUiState.Content).zoomLevel)
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
            assertEquals(ZoomLevels.MAX_LEVEL, (vm.uiState.value as TimelineUiState.Content).zoomLevel)
        }

    @Test
    fun storedValueDoesNotOverrideAUserChoiceMadeBeforeItArrives() =
        runTest(dispatcher) {
            val zoomStore = FakeZoomStore(saved = 0)
            val vm =
                viewModel(
                    FakeRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                    FakeStore(asked = true),
                    zoomStore,
                )
            collect(vm)
            vm.onZoomLevelChanged(3)
            advanceUntilIdle()

            assertEquals(3, zoomStore.saved)
            assertEquals(3, zoomLevelOf(vm))
        }

    @Test
    fun missingTypeReachesTheContentState() =
        runTest(dispatcher) {
            val library = TimelineFixtures.library(missingTypes = setOf(MediaType.VIDEO))
            val vm = viewModel(FakeRepository(MediaLibraryState.Ready(library)), FakeStore(asked = true))

            collect(vm)
            advanceUntilIdle()

            assertEquals(setOf(MediaType.VIDEO), (vm.uiState.value as TimelineUiState.Content).missingTypes)
        }

    private fun TestScope.collect(vm: TimelineViewModel) {
        backgroundScope.launch { vm.uiState.collect { } }
    }

    private fun zoomLevelOf(vm: TimelineViewModel): Int = (vm.uiState.value as TimelineUiState.Content).zoomLevel

    private fun viewModel(
        repository: FakeRepository,
        store: FakeStore,
        zoomStore: FakeZoomStore = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL),
    ): TimelineViewModel =
        TimelineViewModel(
            repository = repository,
            permissionPrompts = store,
            zoomLevels = zoomStore,
            defaultDispatcher = dispatcher,
            clock = TimelineFixtures.clock(),
            zone = ZONE,
        )

    private fun readyNoAccess(): FakeRepository = readyNoAccessRepository()

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
            flow.value = level
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
