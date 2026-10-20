package io.github.labushuya.emberlight.share.space.run

import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceRunPolicyTest {
    private val gib = 1024L * 1024L * 1024L
    private val start = 1_800_000_000_000L
    private val startElapsed = 5_000_000L

    @Test
    fun aPresetEndsAfterItsDurationFromTheStart() {
        val expires = SpaceRunPolicy.expiresAt(Lifetime.Preset(LifetimePreset.TWO_HOURS), start)
        assertEquals(start + 2 * 3600 * 1000L, expires)
    }

    @Test
    fun aFixedEndIsTheGivenInstant() {
        val until = start + 5 * 60_000L
        assertEquals(until, SpaceRunPolicy.expiresAt(Lifetime.Until(until), start))
    }

    @Test
    fun theSpaceIsOpenBeforeItsEndAndClosedAtIt() {
        val expires = start + 10 * 60_000L

        fun closedAt(wallDelta: Long) =
            SpaceRunPolicy.isClosed(
                expiresAtMillis = expires,
                startedAtMillis = start,
                startedElapsedMillis = startElapsed,
                nowWallMillis = start + wallDelta,
                nowElapsedMillis = startElapsed + wallDelta,
            )
        assertFalse(closedAt(10 * 60_000L - 1))
        assertTrue(closedAt(10 * 60_000L))
    }

    @Test
    fun aClockSetForwardClosesTheSpaceEvenIfTheMeasuredRuntimeIsShorter() {
        // Die Wand-Uhr springt vor und zeigt schon nach dem Ende. Die gemessene Laufzeit ist noch kurz. Das frühere
        // Ergebnis gilt (ADR 0017), also ist der Space beendet.
        val expires = start + 10 * 60_000L
        val closed =
            SpaceRunPolicy.isClosed(
                expiresAtMillis = expires,
                startedAtMillis = start,
                startedElapsedMillis = startElapsed,
                nowWallMillis = start + 11 * 60_000L,
                nowElapsedMillis = startElapsed + 2 * 60_000L,
            )
        assertTrue("Wand-Uhr allein beendet den Space", closed)
    }

    @Test
    fun aClockSetBackwardDoesNotKeepTheSpaceOpenPastTheMeasuredRuntime() {
        // Die Wand-Uhr wurde zurückgestellt: Sie zeigt noch vor dem Ende. Die gemessene Laufzeit ist aber abgelaufen.
        val expires = start + 10 * 60_000L
        val closed =
            SpaceRunPolicy.isClosed(
                expiresAtMillis = expires,
                startedAtMillis = start,
                startedElapsedMillis = startElapsed,
                nowWallMillis = start - 60 * 60_000L,
                nowElapsedMillis = startElapsed + 10 * 60_000L,
            )
        assertTrue("gemessene Laufzeit beendet den Space trotz zurückgestellter Uhr", closed)
    }

    @Test
    fun aStartIsRefusedWhenAnotherSpaceIsActive() {
        assertEquals(
            StartProblem.ANOTHER_ACTIVE,
            SpaceRunPolicy.startProblem(
                status = SpaceStatus.DRAFT,
                storageLimitBytes = gib,
                freeBytes = 100 * gib,
                lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
                nowWallMillis = start,
                anotherActive = true,
            ),
        )
    }

    @Test
    fun aStartNeedsTwoGibOfFreeSpaceBeyondTheLimit() {
        val lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY)
        // Limit 4 GiB, frei 6 GiB: genau 2 GiB Puffer, der Start ist erlaubt.
        assertNull(
            SpaceRunPolicy.startProblem(SpaceStatus.DRAFT, 4 * gib, 6 * gib, lifetime, start, anotherActive = false),
        )
        // Frei 6 GiB minus 1 Byte: der Puffer fehlt.
        assertEquals(
            StartProblem.STORAGE_ABOVE_FREE,
            SpaceRunPolicy.startProblem(
                SpaceStatus.DRAFT,
                4 * gib,
                6 * gib - 1,
                lifetime,
                start,
                anotherActive = false,
            ),
        )
    }

    @Test
    fun aFixedEndInThePastIsRefused() {
        assertEquals(
            StartProblem.END_IN_PAST,
            SpaceRunPolicy.startProblem(
                status = SpaceStatus.DRAFT,
                storageLimitBytes = gib,
                freeBytes = 100 * gib,
                lifetime = Lifetime.Until(start - 1),
                nowWallMillis = start,
                anotherActive = false,
            ),
        )
    }

    @Test
    fun onlyDraftsStartAndMissingSpacesAreReported() {
        val lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY)
        assertEquals(
            StartProblem.NOT_A_DRAFT,
            SpaceRunPolicy.startProblem(SpaceStatus.ENDED, gib, 100 * gib, lifetime, start, anotherActive = false),
        )
        assertEquals(
            StartProblem.NOT_FOUND,
            SpaceRunPolicy.startProblem(null, gib, 100 * gib, lifetime, start, anotherActive = false),
        )
    }

    @Test
    fun aGapAboveTheThresholdIsReportedAsPauseWithItsStart() {
        val detector = PauseDetector(thresholdMillis = 120_000L)
        assertNull(detector.beat(wallMillis = start, elapsedMillis = 1_000L, uptimeMillis = 1_000L))
        assertNull(detector.beat(wallMillis = start + 30_000, elapsedMillis = 31_000L, uptimeMillis = 31_000L))

        val pause =
            detector.beat(
                wallMillis = start + 30_000 + 600_000,
                elapsedMillis = 31_000L + 600_000L,
                uptimeMillis = 31_000L + 30_000L,
            )

        assertEquals(
            PauseEvent(startedAtMillis = start + 30_000, durationMillis = 600_000L, deepSleepMillis = 570_000L),
            pause,
        )
    }

    @Test
    fun aGapAtTheThresholdIsNoPauseAndTheDetectorKeepsRunning() {
        val detector = PauseDetector(thresholdMillis = 120_000L)
        detector.beat(wallMillis = start, elapsedMillis = 0L, uptimeMillis = 0L)
        assertNull(detector.beat(wallMillis = start + 120_000, elapsedMillis = 120_000L, uptimeMillis = 120_000L))
        assertNull(detector.beat(wallMillis = start + 150_000, elapsedMillis = 150_000L, uptimeMillis = 150_000L))
    }

    @Test
    fun theFirstBeatAfterAResetIsNoPause() {
        val detector = PauseDetector(thresholdMillis = 1_000L)
        detector.beat(wallMillis = start, elapsedMillis = 0L, uptimeMillis = 0L)
        detector.reset()
        assertNull(detector.beat(wallMillis = start + 9_000, elapsedMillis = 9_000L, uptimeMillis = 9_000L))
    }
}
