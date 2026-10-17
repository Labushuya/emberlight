package io.github.labushuya.emberlight.core.media.exif

import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface

/**
 * EXIF-Angaben eines Fotos für den Infobereich des Viewers (ADR 0010, Kurzer Blick und Infobereich). Alle Werte sind optional,
 * `null` heißt: nicht vorhanden oder nicht lesbar.
 *
 * Bewusst ohne Ortsdaten. GPS-Felder werden nie gelesen, weder hier noch im Infobereich.
 */
@Immutable
data class PhotoExif(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val flashFired: Boolean? = null,
) {
    /** Keine einzige Angabe vorhanden. */
    val isEmpty: Boolean
        get() =
            make == null && model == null && lensModel == null && fNumber == null && exposureSeconds == null &&
                iso == null && focalLengthMm == null && flashFired == null
}

/**
 * Abbildung der EXIF-Attribute auf [PhotoExif]. Nur die Aufnahmefelder werden gelesen, keine GPS-Felder. Leere Texte und
 * ungültige Zahlen werden zu `null`.
 */
internal fun ExifInterface.toPhotoExif(): PhotoExif =
    PhotoExif(
        make = getAttribute(ExifInterface.TAG_MAKE).cleanText(),
        model = getAttribute(ExifInterface.TAG_MODEL).cleanText(),
        lensModel = getAttribute(ExifInterface.TAG_LENS_MODEL).cleanText(),
        fNumber = getAttributeDouble(ExifInterface.TAG_F_NUMBER, NO_VALUE_DOUBLE).positiveOrNull(),
        exposureSeconds = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, NO_VALUE_DOUBLE).positiveOrNull(),
        iso = getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, NO_VALUE_INT).takeIf { it > 0 },
        focalLengthMm = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, NO_VALUE_DOUBLE).positiveOrNull(),
        flashFired = flashFiredOrNull(getAttributeInt(ExifInterface.TAG_FLASH, NO_VALUE_INT)),
    )

/** Bit 0 des Blitz-Feldes meldet, ob der Blitz ausgelöst hat. Fehlt das Feld, bleibt die Angabe leer. */
internal fun flashFiredOrNull(flash: Int): Boolean? =
    if (flash <
        0
    ) {
        null
    } else {
        (flash and FLASH_FIRED_BIT) == FLASH_FIRED_BIT
    }

private fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun Double.positiveOrNull(): Double? = takeIf { it > 0.0 }

private const val NO_VALUE_DOUBLE = -1.0
private const val NO_VALUE_INT = -1
private const val FLASH_FIRED_BIT = 1
