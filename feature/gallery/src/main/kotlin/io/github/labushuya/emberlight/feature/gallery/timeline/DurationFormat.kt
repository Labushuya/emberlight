package io.github.labushuya.emberlight.feature.gallery.timeline

import java.util.Locale

/**
 * Dauer eines Videos als `m:ss` oder ab einer Stunde als `h:mm:ss`. Ziffern sind ASCII, damit das Format in allen
 * Sprachen gleich aussieht. Negative oder unbekannte Werte ergeben `0:00`.
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + MILLIS_HALF_SECOND) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_HALF_SECOND = 500L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
