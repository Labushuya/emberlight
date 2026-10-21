package io.github.labushuya.emberlight.share.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Drosselung des Beitritts mit fester Uhr (ADR 0018). Keine echten Wartezeiten. */
class JoinLimiterTest {
    private var now = 0L
    private val limiter = JoinLimiter(nowMillis = { now })

    @Test
    fun tenFailuresFromOneAddressBlockTheEleventhAttempt() {
        repeat(JoinLimiter.PER_ADDRESS) { limiter.recordFailure("10.0.0.2") }

        assertEquals(JoinLimiter.WINDOW_MILLIS / 1_000, limiter.retryAfterSeconds("10.0.0.2"))
        assertNull(limiter.retryAfterSeconds("10.0.0.3"))
    }

    @Test
    fun nineFailuresStillAllowAnotherAttempt() {
        repeat(JoinLimiter.PER_ADDRESS - 1) { limiter.recordFailure("10.0.0.2") }

        assertNull(limiter.retryAfterSeconds("10.0.0.2"))
    }

    @Test
    fun theBlockEndsWhenTheOldestFailureLeavesTheWindow() {
        limiter.recordFailure("10.0.0.2")
        now = 60_000L
        repeat(JoinLimiter.PER_ADDRESS - 1) { limiter.recordFailure("10.0.0.2") }
        assertEquals(JoinLimiter.WINDOW_MILLIS / 1_000 - 60, limiter.retryAfterSeconds("10.0.0.2"))

        now = JoinLimiter.WINDOW_MILLIS
        assertNull(limiter.retryAfterSeconds("10.0.0.2"))
    }

    @Test
    fun retryAfterRoundsUpToWholeSeconds() {
        repeat(JoinLimiter.PER_ADDRESS) { limiter.recordFailure("a") }
        now = JoinLimiter.WINDOW_MILLIS - 1_500L

        assertEquals(2L, limiter.retryAfterSeconds("a"))
    }

    @Test
    fun globalLimitBlocksEveryAddress() {
        repeat(JoinLimiter.GLOBAL) { index -> limiter.recordFailure("10.1.0.${index % 200}") }

        assertEquals(true, limiter.retryAfterSeconds("192.168.0.9") != null)
    }

    @Test
    fun successfulAttemptsAreNotCounted() {
        // Nur Fehlversuche rufen recordFailure auf. Ohne Aufruf bleibt alles frei.
        repeat(100) { assertNull(limiter.retryAfterSeconds("10.0.0.2")) }
    }
}
