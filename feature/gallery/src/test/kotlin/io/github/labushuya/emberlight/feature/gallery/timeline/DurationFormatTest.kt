package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun zeroIsZeroSeconds() {
        assertEquals("0:00", formatDuration(0L))
    }

    @Test
    fun secondsArePaddedToTwoDigits() {
        assertEquals("0:05", formatDuration(5_000L))
        assertEquals("0:42", formatDuration(42_000L))
        assertEquals("0:59", formatDuration(59_000L))
    }

    @Test
    fun minutesDoNotPadToTwoDigits() {
        assertEquals("1:00", formatDuration(60_000L))
        assertEquals("12:07", formatDuration(727_000L))
    }

    @Test
    fun hoursAppearFromOneHour() {
        assertEquals("1:02:03", formatDuration(3_723_000L))
        assertEquals("10:00:00", formatDuration(36_000_000L))
    }

    @Test
    fun partialSecondsRoundToTheNearestSecond() {
        assertEquals("0:00", formatDuration(499L))
        assertEquals("0:01", formatDuration(500L))
        // 59,6 Sekunden zeigen 1:00, nicht 0:60.
        assertEquals("1:00", formatDuration(59_600L))
        // Knapp unter einer Stunde rundet auf 1:00:00 und nicht auf 59:60.
        assertEquals("1:00:00", formatDuration(3_599_600L))
    }

    @Test
    fun negativeOrUnknownValuesFallBackToZero() {
        assertEquals("0:00", formatDuration(-1L))
        assertEquals("0:00", formatDuration(Long.MIN_VALUE + 1))
    }
}
