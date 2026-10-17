package io.github.labushuya.emberlight.core.media.index

import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.random.Random

/**
 * Komplexität des Index, deterministisch geprüft (ADR 0005). Gezählt werden die Vergleiche des Comparators bei Sortierung
 * und Zusammenführung, nicht die Laufzeit. Die Zählung hängt nur von den Eingaben und dem JDK-Sortieralgorithmus ab, nicht
 * von der Last der Maschine, deshalb ist der Test auf geteilten Runnern nicht flaky.
 *
 * Schranken für 10.000 und 50.000 Einträge:
 * - Höchstens 1,5 · n · log2(n) Vergleiche je Größe. Ein quadratisches Verfahren läge bei 50.000 weit darüber.
 * - Verhältnis 50.000 zu 10.000 höchstens 7,0. Erwartet: linear 5,0, n log n etwa 5,9, quadratisch 25,0, n^1,5 etwa 11,2.
 *
 * Die gemessene Dauer steht nur im Testlog und geht in keine Assertion ein.
 */
class MediaIndexPerformanceTest {
    @Test
    fun buildComparesLikeSortingBetween10kAnd50k() {
        val small = synthetic(10_000)
        val large = synthetic(50_000)

        val smallComparisons = comparisons(small)
        val largeComparisons = comparisons(large)
        val ratio = largeComparisons.toDouble() / smallComparisons

        val smallNanos = bestOf(runs = 3) { MediaIndex.build(small.images, small.videos, MediaAccess.FULL) }
        val largeNanos = bestOf(runs = 3) { MediaIndex.build(large.images, large.videos, MediaAccess.FULL) }
        println(
            "MediaIndexPerformance (nur Log, keine Assertion): 10k=${smallNanos / 1_000_000} ms, " +
                "50k=${largeNanos / 1_000_000} ms, Vergleiche 10k=$smallComparisons, 50k=$largeComparisons, " +
                "ratio=${"%.2f".format(ratio)}",
        )

        assertTrue(
            "50k: $largeComparisons Vergleiche, erwartet höchstens ${bound(50_000)}",
            largeComparisons <= bound(50_000),
        )
        assertTrue(
            "10k: $smallComparisons Vergleiche, erwartet höchstens ${bound(10_000)}",
            smallComparisons <= bound(10_000),
        )
        assertTrue("Verhältnis 50k/10k = $ratio, erwartet höchstens $MAX_RATIO", ratio <= MAX_RATIO)
    }

    @Test
    fun buildKeepsCorrectnessAt50k() {
        val data = synthetic(50_000)
        val library = MediaIndex.build(data.images, data.videos, MediaAccess.FULL)

        assertEquals(data.images.size + data.videos.size, library.items.size)
        // Die Zeitleiste ist durchgehend absteigend sortiert.
        for (i in 1 until library.items.size) {
            assertTrue(MediaIndex.TIMELINE_ORDER.compare(library.items[i - 1], library.items[i]) <= 0)
        }
        assertEquals(library.items.size, library.albums.sumOf { it.itemCount })
    }

    private data class Synthetic(
        val images: List<MediaItem>,
        val videos: List<MediaItem>,
    )

    /** Anzahl der Vergleiche, die ein vollständiger Aufbau des Index braucht. */
    private fun comparisons(data: Synthetic): Long {
        val counter = CountingComparator(MediaIndex.TIMELINE_ORDER)
        MediaIndex.build(data.images, data.videos, MediaAccess.FULL, order = counter)
        return counter.count
    }

    /** Zufällige, aber reproduzierbare Einträge. Die Zeitstempel laufen durch die Auflösung der Fallback-Regeln. */
    private fun synthetic(count: Int): Synthetic {
        val random = Random(SEED)
        val buckets = 200
        val items =
            List(count) { index ->
                val taken = if (random.nextBoolean()) random.nextLong(1L, MAX_MILLIS) else 0L
                val modified = random.nextLong(0L, MAX_MILLIS / 1000)
                val added = random.nextLong(0L, MAX_MILLIS / 1000)
                val resolved = MediaIndex.resolveTimestamp(taken, modified, added)
                val type = if (index % 3 == 0) MediaType.VIDEO else MediaType.IMAGE
                val bucket = random.nextLong(0L, buckets.toLong())
                MediaItem(
                    id = index.toLong(),
                    uri = "content://media/external/images/media/$index",
                    type = type,
                    displayName = "file$index.jpg",
                    mimeType = null,
                    timestamp = resolved.millis,
                    timestampSource = resolved.source,
                    width = 4032,
                    height = 3024,
                    orientation = 0,
                    durationMs = null,
                    sizeBytes = 1_000_000L,
                    bucketId = bucket,
                    bucketName = "Bucket $bucket",
                    relativePath = null,
                    volumeName = null,
                )
            }
        val (videos, images) = items.partition { it.type == MediaType.VIDEO }
        return Synthetic(images = images.sortedBy { it.id }, videos = videos.sortedBy { it.id })
    }

    /** Zählt jeden Vergleich und delegiert die Reihenfolge unverändert an [delegate]. */
    private class CountingComparator(
        private val delegate: Comparator<MediaItem>,
    ) : Comparator<MediaItem> {
        var count: Long = 0
            private set

        override fun compare(
            a: MediaItem,
            b: MediaItem,
        ): Int {
            count++
            return delegate.compare(a, b)
        }
    }

    private inline fun bestOf(
        runs: Int,
        block: () -> Unit,
    ): Long {
        var best = Long.MAX_VALUE
        repeat(runs) {
            val start = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - start)
        }
        return best
    }

    /** Obergrenze 1,5 · n · log2(n) für Vergleiche. */
    private fun bound(n: Int): Long = (1.5 * n * (ln(n.toDouble()) / ln(2.0))).toLong()

    private companion object {
        const val SEED = 42
        const val MAX_RATIO = 7.0
        const val MAX_MILLIS = 1_700_000_000_000L
    }
}
