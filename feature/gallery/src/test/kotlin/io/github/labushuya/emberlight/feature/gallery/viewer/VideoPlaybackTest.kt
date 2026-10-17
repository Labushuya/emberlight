package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Zustandsmaschine der Videowiedergabe: welche Seite spielt, Pause bei Seitenwechsel und ON_STOP, Freigabe. */
class VideoPlaybackTest {
    private val uri9 = "content://media/external/video/media/9"

    @Test
    fun startsIdleWithNothingAttached() {
        assertEquals(VideoPlaybackState(attachedId = null, playRequested = false), VideoPlaybackState())
    }

    @Test
    fun playPressedLoadsAndPlaysThePage() {
        val (state, commands) = VideoPlayback.reduce(VideoPlaybackState(), VideoPlaybackEvent.PlayPressed(9, uri9))

        assertEquals(VideoPlaybackState(attachedId = 9, playRequested = true), state)
        assertEquals(listOf(VideoPlaybackCommand.Load(uri9), VideoPlaybackCommand.Play), commands)
    }

    @Test
    fun playPressedOnTheAttachedPageResumesWithoutReloading() {
        val paused = VideoPlaybackState(attachedId = 9, playRequested = false)

        val (state, commands) = VideoPlayback.reduce(paused, VideoPlaybackEvent.PlayPressed(9, uri9))

        assertEquals(VideoPlaybackState(attachedId = 9, playRequested = true), state)
        assertEquals(listOf(VideoPlaybackCommand.Play), commands)
    }

    @Test
    fun playPressedWhilePlayingDoesNothing() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.PlayPressed(9, uri9))

        assertEquals(playing, state)
        assertEquals(emptyList<VideoPlaybackCommand>(), commands)
    }

    @Test
    fun pageChangeReleasesThePlayerAndStopsPlayback() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.PageSettled(8))

        assertEquals(VideoPlaybackState(), state)
        assertEquals(listOf(VideoPlaybackCommand.Release), commands)
    }

    @Test
    fun settlingOnTheAttachedPageKeepsItAttached() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.PageSettled(9))

        assertEquals(playing, state)
        assertEquals(emptyList<VideoPlaybackCommand>(), commands)
    }

    @Test
    fun settlingWithoutAnAttachedPageDoesNothing() {
        val (state, commands) = VideoPlayback.reduce(VideoPlaybackState(), VideoPlaybackEvent.PageSettled(8))

        assertEquals(VideoPlaybackState(), state)
        assertEquals(emptyList<VideoPlaybackCommand>(), commands)
    }

    @Test
    fun stoppedInTheBackgroundPausesButKeepsTheVideoLoaded() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.Stopped)

        assertEquals(VideoPlaybackState(attachedId = 9, playRequested = false), state)
        assertEquals(listOf(VideoPlaybackCommand.Pause), commands)
    }

    @Test
    fun stoppedWhilePausedChangesNothing() {
        val paused = VideoPlaybackState(attachedId = 9, playRequested = false)

        val (state, commands) = VideoPlayback.reduce(paused, VideoPlaybackEvent.Stopped)

        assertEquals(paused, state)
        assertEquals(emptyList<VideoPlaybackCommand>(), commands)
    }

    @Test
    fun pausePressedPauses() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.PausePressed)

        assertEquals(VideoPlaybackState(attachedId = 9, playRequested = false), state)
        assertEquals(listOf(VideoPlaybackCommand.Pause), commands)
    }

    @Test
    fun endOfVideoStopsRequestingPlayback() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.Ended)

        assertEquals(VideoPlaybackState(attachedId = 9, playRequested = false), state)
        assertEquals(listOf(VideoPlaybackCommand.Pause), commands)
    }

    @Test
    fun playerFailureReturnsThePageToTheStill() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.Failed)

        assertEquals(VideoPlaybackState(), state)
        assertEquals(listOf(VideoPlaybackCommand.Release), commands)
    }

    @Test
    fun playingAnotherVideoLoadsTheNewOne() {
        val playing = VideoPlaybackState(attachedId = 9, playRequested = true)
        val uri7 = "content://media/external/video/media/7"

        val (state, commands) = VideoPlayback.reduce(playing, VideoPlaybackEvent.PlayPressed(7, uri7))

        assertEquals(VideoPlaybackState(attachedId = 7, playRequested = true), state)
        assertEquals(listOf(VideoPlaybackCommand.Load(uri7), VideoPlaybackCommand.Play), commands)
    }
}
