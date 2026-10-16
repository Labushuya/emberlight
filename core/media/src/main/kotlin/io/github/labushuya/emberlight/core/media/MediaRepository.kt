package io.github.labushuya.emberlight.core.media

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import kotlinx.coroutines.flow.StateFlow

/** Zustand des Index. Vor der ersten Abfrage ist er [Loading], danach [Ready]. */
sealed interface MediaLibraryState {
    /** Erste Abfrage läuft. Die UI zeigt einen Ladezustand, nicht „keine Medien“. */
    data object Loading : MediaLibraryState

    @Immutable
    data class Ready(
        val library: MediaLibrary,
    ) : MediaLibraryState
}

/**
 * Zugriff auf Zeitleiste und Alben. Der Index hält leichte Metadaten im Speicher, ohne Bilddaten (ADR 0007).
 */
interface MediaRepository {
    /**
     * Aktueller Index. Startet mit [MediaLibraryState.Loading] und aktualisiert sich bei Änderungen im MediaStore,
     * entprellt.
     */
    val library: StateFlow<MediaLibraryState>

    /**
     * Erzwingt eine Neuabfrage, auch wenn MediaStore keine Änderung gemeldet hat. Gedacht für die Rückkehr aus den
     * Systemeinstellungen nach einer Berechtigungsänderung. Kehrt sofort zurück, die Abfrage läuft im Hintergrund.
     */
    fun refresh()
}
