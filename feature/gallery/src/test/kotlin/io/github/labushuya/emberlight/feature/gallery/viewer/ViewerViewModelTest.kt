package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.FakeLibraryRepository
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineGrouping
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Viewer-ViewModel: Liste und Reihenfolge wie im Raster, Startindex, verschwindende Elemente, leere Liste und entzogene
 * Berechtigung, dazu die Steuerung des einen Players.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Reihenfolge des Index: 9, 8, 7, 6, 5, 4, 3, 2 (9 ist ein Video, 4 hat keinen Ordner).
    private val items = TimelineFixtures.sampleItems()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun whileTheIndexLoadsTheViewerWaits() =
        runTest(dispatcher) {
            val vm = viewModel(FakeLibraryRepository(MediaLibraryState.Loading), mediaId = 9)
            observe(vm)
            advanceUntilIdle()

            assertEquals(ViewerUiState.Loading, vm.uiState.value)
        }

    @Test
    fun allScopeListsTheSameOrderAsTheTimelineGrid() =
        runTest(dispatcher) {
            val vm = viewModel(readyRepository(items), mediaId = 5, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            val grid = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE).flatMap { it.items }
            assertEquals(grid.map { it.id }, ready.items.map { it.id })
            assertEquals(ready.items.indexOfFirst { it.id == 5L }, ready.index)
        }

    @Test
    fun albumScopeKeepsOnlyTheAlbumInIndexOrder() =
        runTest(dispatcher) {
            val downloads = TimelineFixtures.DOWNLOAD_BUCKET
            val vm = viewModel(readyRepository(items), mediaId = 3, bucketId = downloads, scopeAll = false)
            observe(vm)
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            assertEquals(items.filter { it.bucketId == downloads }.map { it.id }, ready.items.map { it.id })
            assertEquals(listOf(3L, 2L), ready.items.map { it.id })
            assertEquals(0, ready.index)
        }

    @Test
    fun albumScopeWithoutFolderIsTheOtherAlbum() =
        runTest(dispatcher) {
            val vm = viewModel(readyRepository(items), mediaId = 4, bucketId = null, scopeAll = false)
            observe(vm)
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            assertEquals(listOf(4L), ready.items.map { it.id })
        }

    @Test
    fun startsAtTheElementFromTheRoute() =
        runTest(dispatcher) {
            val vm = viewModel(readyRepository(items), mediaId = 6, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            assertEquals(3, ready.index)
            assertEquals(6L, ready.items[ready.index].id)
        }

    @Test
    fun disappearingElementMovesToTheNeighbourAtTheSamePlace() =
        runTest(dispatcher) {
            val repository = readyRepository(items)
            val vm = viewModel(repository, mediaId = 6, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            // 6 wird gelöscht. Das Element 5 rückt an dessen Stelle (Index 3).
            repository.update(ready(items.filterNot { it.id == 6L }))
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            assertEquals(3, ready.index)
            assertEquals(5L, ready.items[ready.index].id)
        }

    @Test
    fun pageSettledIsRememberedWhenTheListChanges() =
        runTest(dispatcher) {
            val repository = readyRepository(items)
            val vm = viewModel(repository, mediaId = 9, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            vm.onPageSettled(4)
            repository.update(ready(items.filterNot { it.id == 9L }))
            advanceUntilIdle()

            val ready = vm.uiState.value as ViewerUiState.Ready
            assertEquals(4L, ready.items[ready.index].id)
        }

    @Test
    fun emptyListCloses() =
        runTest(dispatcher) {
            val repository = readyRepository(items)
            val vm = viewModel(repository, mediaId = 9, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            repository.update(ready(emptyList()))
            advanceUntilIdle()

            assertEquals(ViewerUiState.Closed, vm.uiState.value)
        }

    @Test
    fun lostPermissionCloses() =
        runTest(dispatcher) {
            val repository = readyRepository(items)
            val vm = viewModel(repository, mediaId = 9, bucketId = null, scopeAll = true)
            observe(vm)
            advanceUntilIdle()

            repository.update(
                MediaLibraryState.Ready(TimelineFixtures.library(items = items, access = MediaAccess.NONE)),
            )
            advanceUntilIdle()

            assertEquals(ViewerUiState.Closed, vm.uiState.value)
        }

    @Test
    fun playPressedLoadsAndPlaysTheVideo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 9, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()

            vm.onPlayPressed(9)
            advanceUntilIdle()

            assertEquals(VideoPlaybackState(attachedId = 9, playRequested = true), vm.playback.value)
            val player = players.created.single()
            assertTrue(player.calls.contains("play"))
            assertEquals(
                items.first { it.id == 9L }.uri,
                player.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    .toString(),
            )
        }

    @Test
    fun playPressedOnAPhotoCreatesNoPlayer() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 8, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()

            vm.onPlayPressed(8)
            advanceUntilIdle()

            assertNull(vm.player)
            assertTrue(players.created.isEmpty())
            assertNull(vm.playback.value.attachedId)
        }

    @Test
    fun settlingOnAnotherPageReleasesThePlayer() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 9, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()
            vm.onPlayPressed(9)

            vm.onPageSettled(8)
            advanceUntilIdle()

            assertNull(vm.playback.value.attachedId)
            assertTrue(
                players.created
                    .single()
                    .calls
                    .contains("stop"),
            )
        }

    @Test
    fun stoppedInTheBackgroundPausesTheVideo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 9, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()
            vm.onPlayPressed(9)

            vm.onStopped()
            advanceUntilIdle()

            assertFalse(vm.playback.value.playRequested)
            assertTrue(
                players.created
                    .single()
                    .calls
                    .contains("pause"),
            )
        }

    @Test
    fun muteAppliesToThePlayerEvenWhenItIsCreatedLater() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 9, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()

            vm.onToggleMute()
            vm.onPlayPressed(9)
            advanceUntilIdle()

            assertTrue(vm.muted.value)
            assertEquals(0f, players.created.single().volume, 0f)
        }

    @Test
    fun playerIsReusedForTheSecondVideo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = viewModel(readyRepository(items), mediaId = 9, bucketId = null, scopeAll = true, players = players)
            observe(vm)
            advanceUntilIdle()
            vm.onPlayPressed(9)
            vm.onPageSettled(8)
            vm.onPageSettled(7)

            vm.onPlayPressed(7)
            advanceUntilIdle()

            // Nur ein Player, auch wenn mehrere Videos nacheinander laufen. Der Player wird nicht je Video neu gebaut.
            assertEquals(1, players.created.size)
            assertNotNull(vm.player)
        }

    private fun TestScope.observe(vm: ViewerViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    private fun readyRepository(
        items: List<io.github.labushuya.emberlight.core.media.model.MediaItem>,
    ): FakeLibraryRepository = FakeLibraryRepository(ready(items))

    private fun ready(items: List<io.github.labushuya.emberlight.core.media.model.MediaItem>): MediaLibraryState.Ready =
        MediaLibraryState.Ready(TimelineFixtures.library(items = items))

    private fun viewModel(
        repository: FakeLibraryRepository,
        mediaId: Long,
        bucketId: Long? = null,
        scopeAll: Boolean = true,
        players: RecordingPlayers = RecordingPlayers(context),
    ): ViewerViewModel =
        ViewerViewModel(
            repository = repository,
            playerFactory = players,
            savedStateHandle =
                SavedStateHandle(
                    mapOf("mediaId" to mediaId, "bucketId" to bucketId, "scopeAll" to scopeAll),
                ),
        )
}

/** Player, der seine Aufrufe mitschreibt. Dahinter läuft ein Test-ExoPlayer ohne Decoder. */
internal class RecordingPlayer(
    delegate: Player,
) : ForwardingPlayer(delegate) {
    val calls = mutableListOf<String>()

    override fun play() {
        calls += "play"
        super.play()
    }

    override fun pause() {
        calls += "pause"
        super.pause()
    }

    override fun stop() {
        calls += "stop"
        super.stop()
    }

    override fun clearMediaItems() {
        calls += "clear"
        super.clearMediaItems()
    }
}

/** Fabrik, die jeden angelegten Player festhält, damit der Test ihn prüfen kann. */
internal class RecordingPlayers(
    private val context: Context,
) : VideoPlayerFactory {
    val created = mutableListOf<RecordingPlayer>()

    override fun create(): Player = RecordingPlayer(TestExoPlayerBuilder(context).build()).also { created += it }
}
