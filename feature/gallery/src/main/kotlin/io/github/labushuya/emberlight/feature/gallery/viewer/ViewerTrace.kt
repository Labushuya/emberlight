package io.github.labushuya.emberlight.feature.gallery.viewer

import android.util.Log
import io.github.labushuya.emberlight.feature.gallery.BuildConfig

/**
 * Debug-Marken des Vollbild-Viewers (Feinschliff ADR 0010). Nur im Debug-Build entsteht eine Zeile, unter dem Tag
 * `EmberlightViewer` in logcat. Im Benchmark- und Release-Build ist `BuildConfig.DEBUG` falsch, der Aufruf tut nichts, und R8
 * entfernt den Zweig. [output] ist die Senke: Tests fangen die Marken hier ab, um ihre Reihenfolge zu prüfen.
 */
internal object ViewerTrace {
    private const val TAG = "EmberlightViewer"

    internal var output: (String) -> Unit = { line -> Log.d(TAG, line) }

    /** Schreibt die Marke [event] mit der Medien-ID [id] und dem Zeitpunkt (ms, monoton). */
    fun mark(
        event: String,
        id: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        output("$event id=${id ?: "-"} t=${System.nanoTime() / NANOS_PER_MILLI}")
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
