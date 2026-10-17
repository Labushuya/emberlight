package io.github.labushuya.emberlight.core.media.model

import androidx.compose.runtime.Immutable

/** Art eines Medieneintrags. */
enum class MediaType {
    IMAGE,
    VIDEO,
}

/** Quelle des effektiven Zeitstempels (siehe ADR 0007). */
enum class TimestampSource {
    DATE_TAKEN,
    DATE_MODIFIED,
    DATE_ADDED,
}

/**
 * Leichter Metadaten-Eintrag aus MediaStore. Enthält keine Bilddaten.
 *
 * - [uri] ist die Content-URI des Eintrags als String. Die UI wandelt sie mit `Uri.parse` um. Ein String hält das
 *   Modell auf der JVM testbar, weil `android.net.Uri` in JVM-Tests nicht funktioniert.
 * - [timestamp] ist der effektive Zeitstempel in Millisekunden, [timestampSource] nennt seine Quelle.
 * - Optionale Werte sind `null`, wenn MediaStore sie nicht liefert oder der Wert unbekannt ist (z. B. Breite 0).
 */
@Immutable
data class MediaItem(
    val id: Long,
    val uri: String,
    val type: MediaType,
    val displayName: String?,
    val mimeType: String?,
    val timestamp: Long,
    val timestampSource: TimestampSource,
    val width: Int?,
    val height: Int?,
    val orientation: Int?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val bucketId: Long?,
    val bucketName: String?,
    val relativePath: String?,
    val volumeName: String?,
)
