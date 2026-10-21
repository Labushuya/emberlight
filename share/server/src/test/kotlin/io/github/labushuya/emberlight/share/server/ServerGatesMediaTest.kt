package io.github.labushuya.emberlight.share.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eigene Grenze für Medien-Streams und Downloads (ADR 0019): vier gleichzeitig, getrennt von den Anfrage-Plätzen. */
class ServerGatesMediaTest {
    @Test
    fun fourMediaSlotsThenBusyUntilOneIsReleased() {
        val gates = ServerGates()
        repeat(ServerGates.MEDIA_LIMIT) { assertTrue(gates.tryEnterMedia()) }

        assertFalse(gates.tryEnterMedia())
        gates.leaveMedia()
        assertTrue(gates.tryEnterMedia())
    }

    @Test
    fun mediaSlotsAreSeparateFromRequestSlots() {
        val gates = ServerGates(requestLimit = 1)
        repeat(ServerGates.MEDIA_LIMIT) { assertTrue(gates.tryEnterMedia()) }

        assertTrue(gates.tryEnterRequest())
        assertFalse(gates.tryEnterRequest())
    }
}
