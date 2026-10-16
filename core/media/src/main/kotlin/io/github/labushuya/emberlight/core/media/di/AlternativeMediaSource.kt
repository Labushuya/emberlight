package io.github.labushuya.emberlight.core.media.di

import io.github.labushuya.emberlight.core.media.MediaRepository
import kotlinx.coroutines.flow.Flow

/**
 * Alternative Quelle für Medien, anstelle des MediaStore. Nur Entwicklungs- und Mess-Builds liefern eine Implementierung
 * (Demo-Bibliothek, ADR 0011). Im Release-Build gibt es keine, dann bleibt der MediaStore allein.
 */
interface AlternativeMediaSource {
    /** Die Quelle selbst, wenn sie aktiv ist. */
    val repository: MediaRepository

    /** `true`, solange die Alternative statt des MediaStore dient. Ändert sich der Wert, wechselt der Index. */
    val active: Flow<Boolean>
}
