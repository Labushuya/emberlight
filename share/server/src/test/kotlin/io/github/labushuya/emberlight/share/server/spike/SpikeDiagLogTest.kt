package io.github.labushuya.emberlight.share.server.spike

import io.github.labushuya.emberlight.share.server.AccessToken
import io.github.labushuya.emberlight.share.server.installEmberlightModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpikeDiagLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatLineKeepsTheThreeClocksInOrder() {
        assertEquals(
            "wall=100 elapsed=200 up=300 beat cpuLock=true",
            SpikeDiagLog.formatLine(wallMs = 100L, elapsedMs = 200L, uptimeMs = 300L, event = "beat cpuLock=true"),
        )
    }

    @Test
    fun formatLineFlattensLineBreaks() {
        val line = SpikeDiagLog.formatLine(1L, 2L, 3L, "a\nb\rc")
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertEquals("wall=1 elapsed=2 up=3 a b c", line)
    }

    @Test
    fun keepNewestDropsTheOldestLinesFirst() {
        // Jede Zeile hat 3 Byte plus Zeilenumbruch, also 4 Byte. Platz für zwei Zeilen: 8 Byte.
        val lines = listOf("aaa", "bbb", "ccc")
        assertEquals(listOf("bbb", "ccc"), SpikeDiagLog.keepNewest(lines, maxBytes = 8L))
    }

    @Test
    fun keepNewestReturnsEverythingWhenItFits() {
        val lines = listOf("aaa", "bbb")
        assertEquals(lines, SpikeDiagLog.keepNewest(lines, maxBytes = 100L))
    }

    @Test
    fun keepNewestOfEmptyListIsEmpty() {
        assertTrue(SpikeDiagLog.keepNewest(emptyList(), maxBytes = 10L).isEmpty())
    }

    @Test
    fun keepNewestDropsALineThatAloneIsTooLarge() {
        assertTrue(SpikeDiagLog.keepNewest(listOf("zu-lang-für-das-limit"), maxBytes = 5L).isEmpty())
    }

    @Test
    fun appendCreatesTheFileAndKeepsOrder() {
        val file = File(tmp.root, "sub/diag.log")
        val log = SpikeDiagLog(file, maxBytes = 1_000L)

        log.append("eins")
        log.append("zwei")

        assertEquals("eins\nzwei\n", file.readText())
    }

    @Test
    fun appendTrimsToTheNewestLinesWhenTheLimitIsReached() {
        val file = File(tmp.root, "diag.log")
        val log = SpikeDiagLog(file, maxBytes = 100L)

        repeat(60) { log.append("zeile-%02d".format(it)) }

        assertTrue(file.length() <= 100L)
        val kept = file.readLines()
        assertEquals("zeile-59", kept.last())
        assertTrue(kept.first() > "zeile-00")
    }

    @Test
    fun requestLogRecordsArrivalAndCompletionWithoutTheToken() =
        testApplication {
            val token = AccessToken.of("testtoken-0123456789")
            val events = mutableListOf<String>()
            application { installEmberlightModule(token, routes = {}, requestLog = { events += it }) }

            client.get("/health") { header(HttpHeaders.Authorization, "Bearer testtoken-0123456789") }

            assertEquals(2, events.size)
            assertTrue(events[0].startsWith("req-in method=GET path=/health"))
            assertTrue(events[1].startsWith("req-out path=/health status=200"))
            assertFalse(events.any { it.contains("testtoken") })
        }
}
