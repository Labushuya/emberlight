package io.github.labushuya.emberlight.feature.space.upload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** Der Adapter von Stream zu Quelle (Paket 3.5c): liefert die Bytes unverändert und meldet den Fortschritt je Lesen. */
class InputStreamChunkSourceTest {
    @Test
    fun deliversEveryByteAndReportsTheRunningTotal() {
        val data = ByteArray(10) { it.toByte() }
        val reported = mutableListOf<Long>()
        val source = InputStreamChunkSource(ByteArrayInputStream(data)) { reported += it }
        val collected = mutableListOf<Byte>()
        val buffer = ByteArray(4)

        runBlocking {
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                for (i in 0 until count) collected += buffer[i]
            }
        }

        assertEquals(data.toList(), collected)
        assertEquals(listOf(4L, 8L, 10L), reported)
    }

    @Test
    fun anEmptyStreamEndsAtOnceWithoutProgress() {
        val reported = mutableListOf<Long>()
        val source = InputStreamChunkSource(ByteArrayInputStream(ByteArray(0))) { reported += it }

        val count = runBlocking { source.read(ByteArray(8), 0, 8) }

        assertEquals(-1, count)
        assertEquals(emptyList<Long>(), reported)
    }
}
