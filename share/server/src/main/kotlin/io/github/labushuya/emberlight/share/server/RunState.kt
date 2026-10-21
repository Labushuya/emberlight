package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.SpaceId

/** Was der Betrieb gerade tut. Die Oberfläche liest den Stand über [SpaceServerService.runState]. */
sealed interface RunState {
    /** Kein Space läuft. Der Dienst ist beendet oder wartet nicht. */
    data object Idle : RunState

    /** Der Server nimmt Anfragen an. */
    data class Running(
        val spaceId: SpaceId,
        val spaceName: String,
        val port: Int,
        val address: WifiAddress,
        val startedAtMillis: Long,
        /** Geplantes Ende in UTC-Millisekunden, `null` ohne Ende (Debug-Einstieg). */
        val expiresAtMillis: Long?,
        /** `true`, wenn der feste Port belegt war und der Server auf einem neuen läuft. Gäste brauchen dann den neuen QR-Code (Paket 3.4). */
        val addressChanged: Boolean = false,
        /** `true`, wenn die Einladung nach einem Neustart neu entstand, weil das Geheimnis nicht lesbar war (ADR 0018). */
        val inviteRenewed: Boolean = false,
    ) : RunState

    /**
     * Der Server nimmt keine Anfragen an, der Space bleibt aktiv (ADR 0013, Netz). [cause] sagt, warum.
     * [pendingAddress] ist das neue WLAN, wenn der Gastgeber den Wechsel bestätigen muss.
     */
    data class Paused(
        val spaceId: SpaceId,
        val spaceName: String,
        val cause: PauseCause,
        val expiresAtMillis: Long?,
        val pendingAddress: WifiAddress?,
    ) : RunState
}

/** Grund einer Pause durch das Netz. */
enum class PauseCause {
    /** Das WLAN ist weg. Kommt dasselbe Netz zurück, setzt der Server automatisch fort. */
    NETWORK_LOST,

    /** Ein anderes Netz. Der Gastgeber bestätigt den Wechsel in der App, weil sich die Adresse ändert. */
    NETWORK_CHANGED,
}

/**
 * Wird vor jeder Anfrage gefragt (ADR 0017, Ablauf). Ist der Space abgelaufen, antwortet der Server mit `410`
 * (`space_closed`) und meldet das Ende an den Betrieb. Die Zugriffsprüfung bleibt davon unberührt.
 */
interface SpaceClosure {
    /** `true`, wenn der Space abgelaufen ist. Die Abfrage ist billig und blockiert nicht. */
    fun isClosed(): Boolean

    /** Eine Anfrage hat den abgelaufenen Space erreicht. Der Aufruf kehrt sofort zurück. */
    fun onClosedRequest()

    companion object {
        /** Ohne Ende: der Server lehnt nie wegen des Ablaufs ab. */
        val Open: SpaceClosure =
            object : SpaceClosure {
                override fun isClosed(): Boolean = false

                override fun onClosedRequest() {}
            }
    }
}
