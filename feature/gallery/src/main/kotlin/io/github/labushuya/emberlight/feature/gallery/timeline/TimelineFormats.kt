package io.github.labushuya.emberlight.feature.gallery.timeline

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Datumstexte für Überschriften und Bildbeschreibungen in der Sprache der App. Die Muster kommen aus
 * `DateFormat.getBestDateTimePattern`, damit die Reihenfolge von Tag, Monat und Jahr je Sprache stimmt.
 * Ein Objekt pro Sprache, nicht pro Zelle, das ist die Kostenstelle der Zeitleiste.
 */
internal class TimelineFormats(
    locale: Locale,
    private val zone: ZoneId,
) {
    private val dayWithWeekday = formatter(locale, "EEEEdMMMM")
    private val dayWithYear = formatter(locale, "EEEEdMMMMy")
    private val month = formatter(locale, "MMMMy")
    private val longDate = formatter(locale, "dMMMMy")

    fun dayHeader(
        date: LocalDate,
        withYear: Boolean,
    ): String = (if (withYear) dayWithYear else dayWithWeekday).format(date)

    fun monthHeader(yearMonth: YearMonth): String = month.format(yearMonth)

    /** Datum eines Eintrags in der Zone der Galerie, etwa „16. Oktober 2026“. */
    fun longDate(timestampMillis: Long): String = longDate.format(Instant.ofEpochMilli(timestampMillis).atZone(zone))

    private companion object {
        fun formatter(
            locale: Locale,
            skeleton: String,
        ): DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}
