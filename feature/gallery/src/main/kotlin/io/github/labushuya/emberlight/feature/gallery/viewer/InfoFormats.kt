package io.github.labushuya.emberlight.feature.gallery.viewer

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Formatierung der Werte im Infobereich (ADR 0010, Kurzer Blick und Infobereich). Zahlen folgen der Sprache (Dezimalkomma im
 * Deutschen). Einheiten wie „mm“, „ISO“ oder „MP“ sind international und stehen nicht in den Texten. Sätze und Namen kommen aus
 * strings.xml.
 */

/** Blende, z. B. „f/1,8“ oder „f/1.8“. */
internal fun formatAperture(
    fNumber: Double,
    locale: Locale,
): String = String.format(locale, "f/%.1f", fNumber)

/**
 * Belichtungszeit. Kürzer als eine Sekunde als Bruch („1/250 s“), sonst in Sekunden („2,5 s“). Bruch auf ganze Zahlen gerundet,
 * weil Kameras Nenner ganzzahlig angeben.
 */
internal fun formatExposure(
    seconds: Double,
    locale: Locale,
): String =
    when {
        seconds < 1.0 -> "1/${(1.0 / seconds).roundToInt()} s"
        seconds == seconds.roundToInt().toDouble() -> "${seconds.roundToInt()} s"
        else -> String.format(locale, "%.1f s", seconds)
    }

/** Lichtempfindlichkeit, z. B. „ISO 200“. */
internal fun formatIso(iso: Int): String = "ISO $iso"

/** Brennweite in Millimeter, ganzzahlig oder mit einer Nachkommastelle. */
internal fun formatFocalLength(
    mm: Double,
    locale: Locale,
): String =
    if (mm == mm.roundToInt().toDouble()) {
        "${mm.roundToInt()} mm"
    } else {
        String.format(locale, "%.1f mm", mm)
    }

/** Megapixel aus Breite und Höhe, z. B. „12,3 MP“. `null`, wenn die Maße fehlen oder nicht positiv sind. */
internal fun formatMegapixels(
    width: Int?,
    height: Int?,
    locale: Locale,
): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val megapixels = width.toDouble() * height.toDouble() / MEGA
    return String.format(locale, "%.1f MP", megapixels)
}

/** Auflösung als „B × H“. */
internal fun formatResolution(
    width: Int?,
    height: Int?,
): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return "$width × $height"
}

/** Dateityp in lesbarer Form, z. B. „JPEG“ für `image/jpeg` oder „MP4“ für `video/mp4`. `null` ohne Angabe. */
internal fun formatMimeType(mimeType: String?): String? {
    val subtype = mimeType?.substringAfter('/', missingDelimiterValue = "")?.trim().orEmpty()
    if (subtype.isEmpty()) return null
    return KNOWN_MIME_LABELS[subtype.lowercase(Locale.ROOT)] ?: subtype.uppercase(Locale.ROOT)
}

private val KNOWN_MIME_LABELS =
    mapOf(
        "3gpp" to "3GP",
        "heic" to "HEIC",
        "heif" to "HEIF",
        "jpeg" to "JPEG",
        "jpg" to "JPEG",
        "quicktime" to "MOV",
        "x-matroska" to "MKV",
    )

private const val MEGA = 1_000_000.0
