package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Immutable

/**
 * Angaben zu einem Element für den Infobereich (ADR 0010, Kurzer Blick und Infobereich). Quellenunabhängig: Die Galerie füllt
 * es aus dem MediaStore, ein Space später aus eigenen Daten. Alle Werte sind optional, fehlende Werte werden nicht angezeigt.
 *
 * - [dateEstimated]: Der Zeitstempel stammt nicht aus dem Aufnahmedatum (ADR 0007), der Infobereich weist darauf hin.
 * - [folder] ist der relative Pfad des Ordners, ohne abschließenden Schrägstrich.
 *
 * Ortsdaten gibt es hier bewusst nicht.
 */
@Immutable
data class MediaDetails(
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val albumName: String? = null,
    val folder: String? = null,
    val dateEstimated: Boolean = false,
)
