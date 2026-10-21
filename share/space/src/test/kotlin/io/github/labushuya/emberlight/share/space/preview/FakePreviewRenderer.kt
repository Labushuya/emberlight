package io.github.labushuya.emberlight.share.space.preview

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ersatz für die Plattformdekoder. Schreibt feste Bytes nach [target], damit Tests Größe und Inhalt prüfen können.
 * Jeder Aufruf wird protokolliert. [ok] schaltet Fehler ab, [codec] liefert den Video-Codec.
 */
internal class FakePreviewRenderer(
    var ok: Boolean = true,
    var codec: String? = "avc1",
) : PreviewRenderer {
    /** Protokoll der Aufrufe: Art, Dateiname der Quelle und lange Kante. */
    val calls = CopyOnWriteArrayList<Call>()

    private var latch = CountDownLatch(0)

    /** Hält das Rendern an, nachdem der Aufruf protokolliert ist. Für Tests, die eine Löschung mitten im Rendern auslösen. */
    var gate: CountDownLatch? = null

    data class Call(
        val kind: String,
        val source: String,
        val longEdge: Int,
    )

    /** Erwartet [count] Aufrufe. [awaitCalls] wartet darauf, ohne Schlaf. */
    fun expect(count: Int) {
        latch = CountDownLatch(count)
    }

    fun awaitCalls(): Boolean = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    override fun renderImage(
        source: File,
        mime: String,
        longEdge: Int,
        target: File,
    ): Boolean = record("image", source, longEdge, target)

    override fun renderVideoFrame(
        source: File,
        longEdge: Int,
        target: File,
    ): Boolean = record("video", source, longEdge, target)

    override fun videoCodec(source: File): String? {
        calls += Call("codec", source.name, 0)
        return codec
    }

    private fun record(
        kind: String,
        source: File,
        longEdge: Int,
        target: File,
    ): Boolean {
        calls += Call(kind, source.name, longEdge)
        latch.countDown()
        gate?.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!ok) return false
        target.writeBytes("$kind-$longEdge".toByteArray(Charsets.US_ASCII))
        return true
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
