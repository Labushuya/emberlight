package io.github.labushuya.emberlight.feature.space.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zustandslogik der Gastgeber-Videowiedergabe (Hotfix 2). Ohne Player, ohne Gerät. */
class HostVideoPlaybackTest {
    private fun reduce(
        from: HostVideoState,
        vararg events: HostVideoEvent,
    ): HostVideoState = events.fold(from) { state, event -> HostVideoPlayback.reduce(state, event) }

    @Test
    fun aNewViewShowsNoSurfaceAndDoesNotPlay() {
        val state = HostVideoState()

        assertFalse(state.surface)
        assertFalse(state.playing)
    }

    @Test
    fun playPressedShowsTheSurfaceAndWaitsForThePlayerToReportPlaying() {
        val afterPress = reduce(HostVideoState(), HostVideoEvent.PlayPressed)

        assertTrue(afterPress.surface)
        assertFalse("Die Wiedergabe meldet sich erst über den Player", afterPress.playing)

        val playing = reduce(afterPress, HostVideoEvent.PlayingChanged(true))
        assertTrue(playing.playing)
    }

    @Test
    fun pauseAndStopEndPlaybackButKeepTheSurface() {
        val playing = reduce(HostVideoState(), HostVideoEvent.PlayPressed, HostVideoEvent.PlayingChanged(true))

        val paused = reduce(playing, HostVideoEvent.PausePressed)
        assertFalse(paused.playing)
        assertTrue(paused.surface)

        val stopped = reduce(playing, HostVideoEvent.Stopped)
        assertFalse(stopped.playing)
    }

    @Test
    fun endOfVideoMarksItEndedAndANewPlayClearsIt() {
        val ended = reduce(HostVideoState(), HostVideoEvent.PlayPressed, HostVideoEvent.Ended)
        assertTrue(ended.ended)
        assertFalse(ended.playing)

        val again = reduce(ended, HostVideoEvent.PlayPressed, HostVideoEvent.PlayingChanged(true))
        assertFalse(again.ended)
        assertTrue(again.playing)
    }

    @Test
    fun failureHidesTheSurfaceAndStopsPlayback() {
        val failed = reduce(HostVideoState(), HostVideoEvent.PlayPressed, HostVideoEvent.Failed)

        assertTrue(failed.failed)
        assertFalse(failed.surface)
        assertFalse(failed.playing)
    }

    @Test
    fun aPausedVideoIsNotEndedJustBecauseItStopped() {
        val paused = reduce(HostVideoState(), HostVideoEvent.PlayPressed, HostVideoEvent.Stopped)

        assertEquals(false, paused.ended)
    }
}
