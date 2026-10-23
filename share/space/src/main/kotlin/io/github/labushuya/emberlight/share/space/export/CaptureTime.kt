package io.github.labushuya.emberlight.share.space.export

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Aufnahmezeit für `DATE_TAKEN` (Paket 3.7, ADR 0022). Der Space speichert nur den Zeitpunkt des Hochladens. Die echte
 * Aufnahmezeit steht im EXIF-Feld `DateTimeOriginal` der gespeicherten Datei. Sie wird übernommen, wenn sie da ist.
 * Sonst bleibt das Datum leer, und das System setzt das Datum des Hinzufügens. Das Hochladen ist nie das Aufnahmedatum.
 *
 * Videos haben hier keinen EXIF-Eintrag, sie bekommen kein Datum.
 */
object CaptureTime {
    private const val EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss"

    /** Liest die Aufnahmezeit aus einer Bilddatei. Fehler ergeben `null`, sie stoppen die Übernahme nicht. */
    fun ofFile(
        file: File,
        mime: String,
    ): Long? {
        if (!mime.startsWith("image/")) return null
        return try {
            val text = ExifInterface(file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            text?.let { parseExif(it, TimeZone.getDefault()) }
        } catch (unreadable: Exception) {
            null
        }
    }

    /**
     * Wandelt `yyyy:MM:dd HH:mm:ss` in Millisekunden um, in der angegebenen Zeitzone. Ungültige Werte, auch mit
     * Resten am Ende, ergeben `null`. Die EXIF-Zeit hat keine Zeitzone, deshalb gilt die Zone des Geräts.
     */
    fun parseExif(
        text: String,
        zone: TimeZone,
    ): Long? {
        val format =
            SimpleDateFormat(EXIF_PATTERN, Locale.US).apply {
                isLenient = false
                timeZone = zone
            }
        val position = ParsePosition(0)
        val date = format.parse(text.trim(), position) ?: return null
        if (position.index != text.trim().length) return null
        return date.time
    }
}
