package io.github.labushuya.emberlight.feature.gallery.viewer

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Titel des Viewers: Datum und Uhrzeit eines Elements, etwa „16. Oktober 2026, 09:30“ bzw. „October 16, 2026, 09:30“. Das
 * Muster kommt wie bei den Überschriften der Zeitleiste aus `DateFormat.getBestDateTimePattern`, damit Reihenfolge und Wörter
 * je Sprache stimmen. Ein Objekt pro Sprache.
 */
internal class ViewerFormats(
    locale: Locale,
    private val zone: ZoneId,
) {
    private val dateTime =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMMyHHmm"), locale)

    fun title(timestampMillis: Long): String = dateTime.format(Instant.ofEpochMilli(timestampMillis).atZone(zone))
}
