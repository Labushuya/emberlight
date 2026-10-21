package io.github.labushuya.emberlight.share.space.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Hintergrundfaden der Vorschauen: niedrige Priorität, Daemon, Ende mit [PreviewWorkers.close] (ADR 0019). */
@RunWith(RobolectricTestRunner::class)
class PreviewWorkersTest {
    @Test
    fun theWorkerRunsOnADaemonThreadWithTheLowestNormalPriority() {
        val workers = PreviewWorkers()
        try {
            val seen = CompletableDeferred<Pair<Int, Boolean>>()
            workers.scope.launch {
                val thread = Thread.currentThread()
                seen.complete(thread.priority to thread.isDaemon)
            }

            val (priority, daemon) = runBlocking { withTimeout(TIMEOUT_MILLIS) { seen.await() } }

            assertEquals(Thread.MIN_PRIORITY, priority)
            assertTrue(daemon)
        } finally {
            workers.close()
        }
    }

    @Test
    fun closingTheWorkersCancelsTheScope() {
        val workers = PreviewWorkers()

        workers.close()

        assertFalse(workers.scope.isActive)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
