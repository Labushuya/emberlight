package io.github.labushuya.emberlight.share.space.run

import io.github.labushuya.emberlight.share.space.SpaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zustandslogik nach einem Neustart: aktiv, nicht abgelaufen oder abgelaufen, mit oder ohne Akku-Ausnahme (ADR 0017). */
class RestartPolicyTest {
    private val spaceId = SpaceId.generate()
    private val other = SpaceId.generate()
    private val active = ActiveSpan(spaceId, startedAtMillis = START, expiresAtMillis = START + HOUR)

    @Test
    fun withoutAnActiveSpaceThereIsNothingToDo() {
        val decision = decide(active = null, beat = beat(), wall = START + MINUTE, elapsed = ELAPSED + MINUTE)

        assertEquals(RestartDecision.NothingToDo, decision)
    }

    @Test
    fun anActiveSpaceThatIsNotExpiredResumesWithTheBatteryExemption() {
        val decision = decide(active, beat(), wall = START + MINUTE, elapsed = ELAPSED + MINUTE, exempt = true)

        assertEquals(RestartDecision.Resume(spaceId), decision)
    }

    @Test
    fun anActiveSpaceThatIsNotExpiredStaysInterruptedWithoutTheBatteryExemption() {
        val decision = decide(active, beat(), wall = START + MINUTE, elapsed = ELAPSED + MINUTE, exempt = false)

        assertEquals(RestartDecision.Interrupted(spaceId, InterruptReason.NO_BATTERY_EXEMPTION), decision)
    }

    @Test
    fun anExpiredSpaceEndsAtItsPlannedEndEvenWithoutTheExemption() {
        val decision = decide(active, beat(), wall = START + 2 * HOUR, elapsed = ELAPSED + 2 * HOUR, exempt = false)

        assertEquals(RestartDecision.Expired(spaceId, endedAtMillis = START + HOUR), decision)
    }

    @Test
    fun anExpiredSpaceEndsAtTheRecognitionTimeWhenTheFinishIsBeforeIt() {
        // Die Wand-Uhr ist zurückgestellt, die gemessene Laufzeit erreicht die Frist. Der Zeitpunkt ist dann jetzt.
        val wallBack = START + 30 * MINUTE
        val decision = decide(active, beat(), wall = wallBack, elapsed = ELAPSED + HOUR, exempt = true)

        assertEquals(RestartDecision.Expired(spaceId, endedAtMillis = wallBack), decision)
    }

    @Test
    fun aMeasuredExpiryCountsAfterAProcessDeathOnTheSameDevice() {
        // Die Wand-Uhr sagt noch nichts, die gemessene Laufzeit seit dem Start ist um.
        val decision = decide(active, beat(), wall = START + 10 * MINUTE, elapsed = ELAPSED + HOUR, exempt = true)

        assertTrue(decision is RestartDecision.Expired)
    }

    @Test
    fun withoutAMeasurementOnlyTheWallClockCountsAfterARestart() {
        // Nach einem Neustart des Geräts gibt es keine vergleichbare gemessene Laufzeit. Nur die Wand-Uhr gilt.
        val rebooted = beat().copy(startedElapsedMillis = ELAPSED + 5 * HOUR)
        val notYet = decide(active, rebooted, wall = START + 10 * MINUTE, elapsed = ELAPSED + 2 * HOUR, exempt = true)

        assertEquals(RestartDecision.Resume(spaceId), notYet)
    }

    @Test
    fun aMeasurementOfAnotherSpaceIsIgnored() {
        val foreign = beat().copy(spaceId = other)
        val decision = decide(active, foreign, wall = START + 10 * MINUTE, elapsed = ELAPSED + HOUR, exempt = true)

        assertEquals(RestartDecision.Resume(spaceId), decision)
    }

    @Test
    fun aSpaceWithoutAnEndIsNeverExpired() {
        val open = ActiveSpan(spaceId, startedAtMillis = START, expiresAtMillis = null)
        val decision =
            decide(open, beat(), wall = START + 1_000 * HOUR, elapsed = ELAPSED + 1_000 * HOUR, exempt = true)

        assertEquals(RestartDecision.Resume(spaceId), decision)
    }

    @Test
    fun theInterruptionLastsFromTheLastHeartbeatOnTheSameDevice() {
        val gap =
            interruptionMillis(beat(), nowWallMillis = START + 9 * MINUTE, nowElapsedMillis = ELAPSED + 9 * MINUTE)

        assertEquals(9 * MINUTE, gap)
    }

    @Test
    fun theInterruptionFallsBackToTheWallClockAfterAReboot() {
        val gap = interruptionMillis(beat(), nowWallMillis = START + 4 * MINUTE, nowElapsedMillis = 500L)

        assertEquals(4 * MINUTE, gap)
    }

    @Test
    fun theInterruptionIsNeverNegative() {
        val gap = interruptionMillis(beat(), nowWallMillis = START - MINUTE, nowElapsedMillis = ELAPSED - MINUTE)

        assertEquals(0L, gap)
    }

    private fun beat() =
        RunBeat(
            spaceId = spaceId,
            startedAtMillis = START,
            startedElapsedMillis = ELAPSED,
            lastWallMillis = START,
            lastElapsedMillis = ELAPSED,
            port = PORT,
        )

    private fun decide(
        active: ActiveSpan?,
        beat: RunBeat?,
        wall: Long,
        elapsed: Long,
        exempt: Boolean = true,
    ): RestartDecision =
        RestartPolicy.decide(
            active = active,
            beat = beat,
            nowWallMillis = wall,
            nowElapsedMillis = elapsed,
            batteryExempt = exempt,
        )

    private companion object {
        const val START = 1_800_000_000_000L
        const val ELAPSED = 3_600_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val PORT = 8080
    }
}
