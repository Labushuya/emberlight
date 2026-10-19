package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ChunkSource
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Lesen des Körpers mit injizierter Uhr (ADR 0013). Jede Lesung ist ein Abfragezyklus. Die Uhr springt pro Zyklus um
 * eine Sekunde, damit Leerlauf in Sekunden simuliert wird, ohne echte Wartezeit.
 */
class BodyChunkSourceTest {
    /** Springt bei jedem Abruf um [stepNanos] weiter. */
    private class SteppingClock(
        private val stepNanos: Long,
    ) : MonotonicClock {
        private var now = 0L

        override fun nanos(): Long {
            now += stepNanos
            return now
        }
    }

    private fun source(channel: ByteChannel): ChunkSource =
        BodyChunkSource(
            channel,
            SteppingClock(NANOS_PER_SECOND),
            TransferPolicy(),
            pollMillis = 1,
            expectedLength = null,
        )

    @Test
    fun stalledBodyIsAbortedAsIdleWithoutRealWaiting() {
        val channel = ByteChannel()
        val source = source(channel)

        val abort =
            assertThrows(TransferAbortedException::class.java) {
                runBlocking { source.read(ByteArray(16), 0, 16) }
            }

        assertEquals(TransferViolation.IDLE, abort.violation)
    }

    @Test
    fun deliveredBytesAreReturnedAndEndOfBodyIsMinusOne() {
        val channel = ByteChannel()
        val source = source(channel)
        val buffer = ByteArray(16)

        val count =
            runBlocking {
                channel.writeFully(byteArrayOf(1, 2, 3, 4, 5))
                channel.flush()
                val read = source.read(buffer, 0, buffer.size)
                channel.close()
                read
            }
        val end = runBlocking { source.read(buffer, 0, buffer.size) }

        assertEquals(5, count)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), buffer.copyOf(5).toList())
        assertEquals(-1, end)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
