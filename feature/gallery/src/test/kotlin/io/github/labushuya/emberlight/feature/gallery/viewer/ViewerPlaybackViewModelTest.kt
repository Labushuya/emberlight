package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Wiedergabe des Overlays: ein Player, kein Autoplay, Abspielen nur auf Antippen, Freigabe beim Seitenwechsel, Pause im
 * Hintergrund, Stumm über Seiten hinweg. Die Liste kommt vom Raster und ist nicht Teil dieses Halters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerPlaybackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Videos und Fotos wie in den Zeitleisten-Fixtures: 9 ist ein Video, 8 ein Foto, 7 ein Video.
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
    fun playPressedLoadsAndPlaysTheVideo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)

            vm.onPlayPressed(9, uriOf(9))
            advanceUntilIdle()

            assertEquals(VideoPlaybackState(attachedId = 9, playRequested = true), vm.playback.value)
            val player = players.created.single()
            assertTrue(player.calls.contains("play"))
            assertEquals(
                uriOf(9),
                player.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    .toString(),
            )
        }

    @Test
    fun aDemoVideoIsNotPlayableAndSaysSo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)

            vm.onPlayPressed(9, "emberlight-demo://media/9")
            advanceUntilIdle()

            assertTrue(vm.unplayable.value)
            assertNull(vm.player)
            assertTrue(players.created.isEmpty())

            vm.onUnplayableShown()
            assertFalse(vm.unplayable.value)
        }

    @Test
    fun settlingOnAnotherPageReleasesThePlayer() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)
            vm.onPlayPressed(9, uriOf(9))

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
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)
            vm.onPlayPressed(9, uriOf(9))

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
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)

            vm.onToggleMute()
            vm.onPlayPressed(9, uriOf(9))
            advanceUntilIdle()

            assertTrue(vm.muted.value)
            assertEquals(0f, players.created.single().volume, 0f)
        }

    @Test
    fun playerIsReusedForTheSecondVideo() =
        runTest(dispatcher) {
            val players = RecordingPlayers(context)
            val vm = ViewerPlaybackViewModel(players)
            vm.onPageSettled(9)
            vm.onPlayPressed(9, uriOf(9))
            vm.onPageSettled(8)
            vm.onPageSettled(7)

            vm.onPlayPressed(7, uriOf(7))
            advanceUntilIdle()

            // Nur ein Player, auch wenn mehrere Videos nacheinander laufen. Er wird nicht je Video neu gebaut.
            assertEquals(1, players.created.size)
            assertNotNull(vm.player)
        }

    private fun uriOf(id: Long): String = items.first { it.id == id }.uri
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
}

/** Fabrik, die jeden Player aufzeichnet. Ein Player entsteht je Aufruf von [create]; der Halter ruft sie nur einmal auf. */
internal class RecordingPlayers(
    private val context: Context,
) : VideoPlayerFactory {
    val created = mutableListOf<RecordingPlayer>()

    override fun create(): Player {
        val player = RecordingPlayer(TestExoPlayerBuilder(context).build())
        created += player
        return player
    }
}
