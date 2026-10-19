package io.github.labushuya.emberlight.share.space

import java.text.Normalizer

/**
 * Bereinigung des Dateinamens, den ein Gast mitschickt (ADR 0014, Upload). Der bereinigte Name steht nur als Metadatum
 * in der Datenbank und in `Content-Disposition`. Als Pfad wird er nie verwendet.
 */
object UploadFileName {
    /** Höchstlänge in Unicode-Codepunkten nach der Bereinigung. */
    const val MAX_CODE_POINTS = 120

    /** Name, wenn nach der Bereinigung nichts übrig bleibt. */
    const val FALLBACK = "datei"

    private val forbidden = setOf('/'.code, '\\'.code, ':'.code, '"'.code)

    /**
     * NFC-Normalisierung, Steuerzeichen und `/ \ : "` entfernen, führende Punkte entfernen, auf
     * [MAX_CODE_POINTS] kürzen. Leer nach der Bereinigung wird zu [FALLBACK].
     */
    fun sanitize(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val kept =
            buildString {
                normalized.codePoints().forEach { codePoint ->
                    if (!Character.isISOControl(codePoint) && codePoint !in forbidden) appendCodePoint(codePoint)
                }
            }
        val trimmed = kept.trim().trimStart('.').trim()
        val limited =
            if (trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) {
                trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_CODE_POINTS))
            } else {
                trimmed
            }
        return limited.ifEmpty { FALLBACK }
    }
}
