package io.github.labushuya.emberlight.core.media.index

import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Plausibilität der Laufzeit: Der Index muss ungefähr linear mit der Anzahl wachsen, also nicht quadratisch.
 *
 * Keine feste Millisekunden-Grenze, die auf CI-Maschinen flackern würde. Gemessen wird das Verhältnis
 * 50.000 zu 10.000 Einträge, jeweils als bestes von fünf Läufen nach einem Aufwärmlauf. Für n · log n ergibt
 * sich ein Verhältnis von etwa 5,9, für lineares Wachstum 5, für quadratisches 25. Die Grenze 12 liegt dazwischen.
 * Der Schwellwert ist bewusst großzügig, damit der Test nicht durch Messrauschen rot wird.
 *
 * Gemessen wird Erzeugung der Einträge mit Zeitstempel-Auflösung, Sortierung, Zusammenführung und Alben. Das
 * Cursor-Mapping ist Android-Code und steht in [ContentResolverMediaStoreGatewayTest].
 */
class MediaIndexPerformanceTest {
    @Test
    fun buildGrowsLinearlyBetween10kAnd50k() {
        val small = synthetic(10_000)
        val large = synthetic(50_000)

        // Aufwärmen, damit die JIT die Pfade kennt.
        MediaIndex.build(small.images, small.videos, MediaAccess.FULL)

        val smallNanos = bestOf(runs = 5) { MediaIndex.build(small.images, small.videos, MediaAccess.FULL) }
        val largeNanos = bestOf(runs = 5) { MediaIndex.build(large.images, large.videos, MediaAccess.FULL) }
        val ratio = largeNanos.toDouble() / smallNanos

        println(
            "MediaIndexPerformance: 10k=${smallNanos / 1_000_000} ms, 50k=${largeNanos / 1_000_000} ms, " +
                "ratio=${"%.2f".format(ratio)} (linear 5.0, n log n 5.9, quadratisch 25.0)",
        )
        assertTrue("Verhältnis 50k/10k = $ratio, erwartet unter 12", ratio < MAX_RATIO)
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

    private companion object {
        const val SEED = 42
        const val MAX_RATIO = 12.0
        const val MAX_MILLIS = 1_700_000_000_000L
    }
}
