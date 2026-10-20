package io.github.labushuya.emberlight.share.server

/** Was ein Netzsignal für den laufenden Server bedeutet (ADR 0013, Netz; ADR 0017, Fortsetzen). */
sealed interface NetworkAction {
    /** Nichts ändert sich. */
    data object Keep : NetworkAction

    /** Das WLAN ist verschwunden. Der Server pausiert. */
    data object Lost : NetworkAction

    /** Ein anderes Netz oder eine andere Adresse. Der Server pausiert, bis der Gastgeber bestätigt. */
    data class Changed(
        val address: WifiAddress,
    ) : NetworkAction

    /** Dasselbe Netz ist zurück. Der Server bindet neu an dieselbe Adresse. */
    data class Resume(
        val address: WifiAddress,
    ) : NetworkAction
}

/**
 * Entscheidet über Netzsignale. Dasselbe Netz heißt: gleiche IPv4-Adresse, gleiches Präfix und gleiches Gateway (ADR 0017).
 * Ohne Standortrecht lässt sich der Name des WLAN nicht prüfen, deshalb zählt die Adresse.
 */
object NetworkPolicy {
    /** Gleiches Netz: IPv4-Adresse, Präfix und Gateway stimmen überein. */
    fun sameNetwork(
        a: WifiAddress,
        b: WifiAddress,
    ): Boolean = a.ipv4 == b.ipv4 && a.prefixLength == b.prefixLength && a.gateway == b.gateway

    /** Der Server läuft an [bound]. [current] ist das WLAN jetzt, `null` wenn keines mit Adresse steht. */
    fun whileRunning(
        bound: WifiAddress,
        current: WifiAddress?,
    ): NetworkAction =
        when {
            current == null -> NetworkAction.Lost
            sameNetwork(bound, current) -> NetworkAction.Keep
            else -> NetworkAction.Changed(current)
        }

    /**
     * Der Server ist pausiert. [lastBound] ist die Adresse vor der Pause. Kommt dasselbe Netz zurück, folgt eine
     * automatische Fortsetzung. Ein anderes Netz wird gemeldet. Ohne WLAN bleibt es bei der Pause.
     */
    fun whilePaused(
        lastBound: WifiAddress,
        current: WifiAddress?,
    ): NetworkAction =
        when {
            current == null -> NetworkAction.Keep
            sameNetwork(lastBound, current) -> NetworkAction.Resume(current)
            else -> NetworkAction.Changed(current)
        }
}
