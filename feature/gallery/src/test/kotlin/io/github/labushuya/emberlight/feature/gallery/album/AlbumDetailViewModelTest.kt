package io.github.labushuya.emberlight.feature.gallery.album

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.FakeLibraryRepository
import io.github.labushuya.emberlight.feature.gallery.FakePromptStore
import io.github.labushuya.emberlight.feature.gallery.FakeZoomStore
import io.github.labushuya.emberlight.feature.gallery.GalleryViewModel
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Album-ViewModel. Läuft unter Robolectric, weil `toRoute` die Route aus einem Bundle liest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlbumDetailViewModelTest {
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
            val vm = albumViewModel(FakeLibraryRepository(MediaLibraryState.Loading), bucketId = 1L)

            assertEquals(AlbumDetailUiState.Loading, vm.uiState.value)
        }

    @Test
    fun showsOnlyTheItemsOfTheAlbumAndTheAlbumItself() =
        runTest(dispatcher) {
            val vm = albumViewModel(readyRepository(), bucketId = TimelineFixtures.CAMERA_BUCKET)
            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value.grid as MediaGridState.Content
            assertEquals(listOf(9L, 8L, 6L, 5L), content.sections.flatMap { it.items }.map { it.id })
            assertEquals(
                TimelineFixtures.CAMERA_BUCKET,
                vm.uiState.value.album
                    ?.bucketId,
            )
        }

    @Test
    fun theOtherAlbumUsesTheNullBucket() =
        runTest(dispatcher) {
            val vm = albumViewModel(readyRepository(), bucketId = null)
            collect(vm)
            advanceUntilIdle()

            val content = vm.uiState.value.grid as MediaGridState.Content
            assertEquals(listOf(4L), content.sections.flatMap { it.items }.map { it.id })
            val album = vm.uiState.value.album
            assertTrue(album != null && album.bucketId == null)
        }

    @Test
    fun aMissingAlbumIsGoneWithoutAnAlbum() =
        runTest(dispatcher) {
            val vm = albumViewModel(readyRepository(), bucketId = 42L)
            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.Gone(MediaAccess.FULL, emptySet()), vm.uiState.value.grid)
            assertNull(vm.uiState.value.album)
        }

    @Test
    fun anAlbumThatDisappearsWhileOpenBecomesGone() =
        runTest(dispatcher) {
            val repository = readyRepository()
            val vm = albumViewModel(repository, bucketId = TimelineFixtures.SCREENSHOT_BUCKET)
            collect(vm)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.grid is MediaGridState.Content)

            // Der Nutzer löscht das Screenshot-Bild: das Album existiert nicht mehr.
            repository.update(
                MediaLibraryState.Ready(
                    TimelineFixtures.library(items = TimelineFixtures.sampleItems().filter { it.id != 7L }),
                ),
            )
            advanceUntilIdle()

            assertEquals(MediaGridState.Gone(MediaAccess.FULL, emptySet()), vm.uiState.value.grid)
            assertNull(vm.uiState.value.album)
        }

    @Test
    fun noAccessShowsThePromptInTheAlbumToo() =
        runTest(dispatcher) {
            val repository =
                FakeLibraryRepository(
                    MediaLibraryState.Ready(TimelineFixtures.library(items = emptyList(), access = MediaAccess.NONE)),
                )
            val vm = albumViewModel(repository, bucketId = 1L, prompts = FakePromptStore(asked = false))
            collect(vm)
            advanceUntilIdle()

            assertEquals(MediaGridState.PermissionRequired(canRequest = true), vm.uiState.value.grid)
        }

    @Test
    fun missingVideoTypeReachesTheAlbum() =
        runTest(dispatcher) {
            val library = TimelineFixtures.library(missingTypes = setOf(MediaType.VIDEO))
            val vm = albumViewModel(FakeLibraryRepository(MediaLibraryState.Ready(library)), bucketId = 1L)
            collect(vm)
            advanceUntilIdle()

            assertEquals(setOf(MediaType.VIDEO), (vm.uiState.value.grid as MediaGridState.Content).missingTypes)
        }

    @Test
    fun zoomLevelIsSharedWithTheGallery() =
        runTest(dispatcher) {
            val zoom = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL)
            val gallery = galleryViewModel(readyRepository(), zoom)
            val album = albumViewModel(readyRepository(), bucketId = TimelineFixtures.CAMERA_BUCKET, zoom = zoom)
            collect(gallery)
            collect(album)
            advanceUntilIdle()

            album.onZoomLevelChanged(ZoomLevels.MAX_LEVEL)
            advanceUntilIdle()

            assertEquals(ZoomLevels.MAX_LEVEL, (album.uiState.value.grid as MediaGridState.Content).zoomLevel)
            assertEquals(ZoomLevels.MAX_LEVEL, (gallery.uiState.value.grid as MediaGridState.Content).zoomLevel)
            assertEquals(ZoomLevels.MAX_LEVEL, zoom.saved)
        }

    @Test
    fun zoomLevelChosenInTheGalleryIsSeenInTheAlbum() =
        runTest(dispatcher) {
            val zoom = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL)
            val gallery = galleryViewModel(readyRepository(), zoom)
            val album = albumViewModel(readyRepository(), bucketId = TimelineFixtures.CAMERA_BUCKET, zoom = zoom)
            collect(gallery)
            collect(album)
            advanceUntilIdle()

            gallery.onZoomLevelChanged(ZoomLevels.LAST_DAY_LEVEL + 1)
            advanceUntilIdle()

            assertEquals(ZoomLevels.LAST_DAY_LEVEL + 1, (album.uiState.value.grid as MediaGridState.Content).zoomLevel)
        }

    @Test
    fun albumZoomPersistsTheStageLikeTheGallery() =
        runTest(dispatcher) {
            val zoom = FakeZoomStore(saved = ZoomLevels.DEFAULT_LEVEL)
            val album = albumViewModel(readyRepository(), bucketId = TimelineFixtures.CAMERA_BUCKET, zoom = zoom)
            collect(album)
            advanceUntilIdle()

            album.onZoomLevelChanged(99)
            advanceUntilIdle()

            assertEquals(ZoomLevels.MAX_LEVEL, zoom.saved)
        }

    @Test
    fun permissionAnswerRereadsTheIndex() =
        runTest(dispatcher) {
            val repository = readyRepository()
            val vm = albumViewModel(repository, bucketId = 1L)
            collect(vm)
            advanceUntilIdle()

            vm.onPermissionResult(showRationale = false)
            advanceUntilIdle()

            assertEquals(1, repository.refreshCount)
        }

    private fun TestScope.collect(vm: AlbumDetailViewModel) {
        backgroundScope.launch { vm.uiState.collect { } }
    }

    private fun TestScope.collect(vm: GalleryViewModel) {
        backgroundScope.launch { vm.uiState.collect { } }
    }

    private fun readyRepository(): FakeLibraryRepository =
        FakeLibraryRepository(MediaLibraryState.Ready(TimelineFixtures.library()))

    private fun albumViewModel(
        repository: FakeLibraryRepository,
        bucketId: Long?,
        prompts: FakePromptStore = FakePromptStore(asked = true),
        zoom: ZoomLevelStore = FakeZoomStore(),
    ): AlbumDetailViewModel =
        AlbumDetailViewModel(
            repository = repository,
            permissionPrompts = prompts,
            zoomLevels = zoom,
            defaultDispatcher = dispatcher,
            clock = TimelineFixtures.clock(),
            zone = ZONE,
            savedStateHandle = SavedStateHandle(mapOf("bucketId" to bucketId)),
        )

    private fun galleryViewModel(
        repository: FakeLibraryRepository,
        zoom: ZoomLevelStore,
    ): GalleryViewModel =
        GalleryViewModel(
            repository = repository,
            permissionPrompts = FakePromptStore(asked = true),
            zoomLevels = zoom,
            defaultDispatcher = dispatcher,
            clock = TimelineFixtures.clock(),
            zone = ZONE,
        )
}
