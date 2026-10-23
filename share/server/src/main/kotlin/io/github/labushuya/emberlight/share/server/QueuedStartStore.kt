package io.github.labushuya.emberlight.share.server

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.labushuya.emberlight.share.space.SpaceId

/**
 * Merkt den Start, der im Nachlauf des vorigen Space wartet (ADR 0017, Nachtrag 22.10.2026). Stirbt der Dienst während des
 * Wartens, findet der Neustart die Merkung und setzt den Space zurück auf Entwurf. Eigene Einstellungsdatei, keine
 * Datenbankmigration. Der Eintrag gilt nur, solange ein Start wartet: Übernahme, Abbruch und Verwerfen löschen ihn.
 */
internal class QueuedStartStore(
    private val prefs: SharedPreferences,
) {
    fun mark(spaceId: SpaceId) {
        prefs.edit { putString(KEY, spaceId.value) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    /** Der wartende Start aus einem früheren Lauf, `null` wenn keiner wartet. */
    fun queued(): SpaceId? = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let(::SpaceId)

    private companion object {
        const val KEY = "queued-start"
    }
}
