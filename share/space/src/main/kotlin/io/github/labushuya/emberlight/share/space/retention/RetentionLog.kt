package io.github.labushuya.emberlight.share.space.retention

import android.util.Log
import io.github.labushuya.emberlight.share.space.SpaceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Protokoll des automatischen Löschens (Paket 3.6d). Eigene Kennung `EmberlightRetention`, analog zu `EmberlightPreview`.
 * Protokolliert werden nur die Space-Kennung, die Art des Fehlers (Klassenname der Ausnahme) und der Versuch. Keine
 * Namen, keine Pfade mit Nutzerinhalten, keine Tokens und keine Fehlermeldungen, die solche Angaben enthalten könnten.
 */
internal object RetentionLog {
    const val TAG = "EmberlightRetention"

    /** Fehlversuche je Space seit dem Start des Prozesses. Die Zählung ist nur im Speicher und beginnt beim Neustart neu. */
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Protokolliert einen fehlgeschlagenen Löschversuch. Der Space bleibt `purging` und wird beim nächsten Lauf fortgesetzt. */
    fun purgeFailed(
        id: SpaceId,
        failure: Throwable,
    ) {
        val attempt = attempts.merge(id.value, 1, Int::plus)
        Log.w(TAG, "Löschung fehlgeschlagen: space=${id.value}, ${failure.javaClass.name}, Versuch $attempt")
    }

    /** Ein erfolgreicher Lauf setzt den Zähler des Space zurück. */
    fun purgeSucceeded(id: SpaceId) {
        attempts.remove(id.value)
    }
}
