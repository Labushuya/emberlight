package io.github.labushuya.emberlight.feature.space.upload

import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.SpaceId

/** Angaben zu einer ausgewählten Datei: Name und Größe, falls das System sie nennt. */
data class PickedFile(
    val name: String,
    val sizeBytes: Long?,
)

/** Fortschritt einer Datei in Bytes. [totalBytes] ist `null`, wenn die Größe nicht bekannt ist. Dann bleibt er unbestimmt. */
data class UploadProgress(
    val readBytes: Long,
    val totalBytes: Long?,
)

/** Ergebnis eines Versuchs. [Unreadable] heißt: Das System gibt die Datei nicht heraus. */
sealed interface PickedUploadOutcome {
    data class Ingested(
        val result: IngestResult,
    ) : PickedUploadOutcome

    data object Unreadable : PickedUploadOutcome
}

/**
 * Upload des Gastgebers aus der App (Paket 3.5c). Die Implementierung öffnet die Datei über den Inhaltsresolver und gibt nur den
 * Stream an die Vault weiter, denn `share:space` kennt keinen Resolver (ADR 0019, Abschnitt Gastgeber-Upload). Die Schnittstelle
 * ist für Tests ersetzbar.
 */
interface HostUploadPort {
    /** Ob im Space jetzt hochgeladen werden darf: aktiv, nicht abgelaufen und der Server läuft. */
    suspend fun canUpload(spaceId: SpaceId): Boolean

    /** Name und Größe der Datei. Ohne lesbare Angaben kommt ein Ersatzname und `null` als Größe. */
    suspend fun describe(uri: String): PickedFile

    /**
     * Lädt eine Datei hoch wie der HTTP-Upload (Grenzen, Signatur, Standort, Dedupe, Speicher). [keepLocation] ist die
     * Einwilligung für eine Datei, deren Standort nicht entfernbar ist. [onProgress] meldet die gelesenen Bytes.
     */
    suspend fun upload(
        spaceId: SpaceId,
        uri: String,
        keepLocation: Boolean,
        onProgress: (UploadProgress) -> Unit,
    ): PickedUploadOutcome
}
