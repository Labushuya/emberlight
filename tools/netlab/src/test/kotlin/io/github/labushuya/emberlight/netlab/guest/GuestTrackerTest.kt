package io.github.labushuya.emberlight.netlab.guest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestTrackerTest {
    private var now = 0L
    private val tracker = GuestTracker(clock = { now }, ignored = setOf("192.168.49.1"))

    @Test
    fun countsDistinctAddressesOnce() {
        tracker.touch("192.168.49.20")
        tracker.touch("192.168.49.20")
        tracker.touch("192.168.49.21")
        assertEquals(2, tracker.snapshot(activeWindowMs = 60_000).total)
    }

    @Test
    fun ignoresOwnHostAddress() {
        tracker.touch("192.168.49.1")
        assertEquals(0, tracker.snapshot(activeWindowMs = 60_000).total)
    }

    @Test
    fun activeOnlyWithinWindow() {
        now = 0
        tracker.touch("192.168.49.20")
        now = 50_000
        tracker.touch("192.168.49.21")
        now = 100_000

        val snapshot = tracker.snapshot(activeWindowMs = 60_000)
        assertEquals(2, snapshot.total)
        assertEquals(1, snapshot.active)
        assertFalse(snapshot.entries.first { it.address == "192.168.49.20" }.active)
        assertTrue(snapshot.entries.first { it.address == "192.168.49.21" }.active)
    }

    @Test
    fun lastSeenUpdatesOnNewContact() {
        now = 1_000
        tracker.touch("192.168.49.20")
        now = 9_000
        tracker.touch("192.168.49.20")
        assertEquals(
            9_000L,
            tracker
                .snapshot(60_000)
                .entries
                .single()
                .lastSeenMs,
        )
    }

    @Test
    fun entriesAreSortedNewestFirst() {
        now = 1_000
        tracker.touch("192.168.49.20")
        now = 2_000
        tracker.touch("192.168.49.21")
        val addresses = tracker.snapshot(60_000).entries.map { it.address }
        assertEquals(listOf("192.168.49.21", "192.168.49.20"), addresses)
    }

    @Test
    fun resetClearsEverything() {
        tracker.touch("192.168.49.20")
        tracker.reset()
        assertEquals(0, tracker.snapshot(60_000).total)
    }
}
