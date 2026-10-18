package io.github.labushuya.emberlight.share.server

import kotlinx.coroutines.flow.StateFlow

/**
 * Lokaler HTTP-Server für Space und Gäste (ADR 0012). Aufrufer kennen nur dieses Interface, keine Ktor-Typen.
 *
 * Die Routen und die Zugriffsprüfung legt die jeweilige Implementierung fest. Der Aufrufer gibt nur den Port vor.
 */
interface LocalServer {
    /** Aktueller Zustand. Wechselt auf [ServerState.Running], sobald der Port gebunden ist. */
    val state: StateFlow<ServerState>

    /**
     * Startet den Server auf allen Schnittstellen. [ServerConfig.port] 0 wählt einen freien Port.
     * Liefert den tatsächlich gebundenen Port. Läuft bereits ein Server, wirft die Methode [IllegalStateException].
     */
    suspend fun start(config: ServerConfig): Int

    /** Stoppt den Server. Ohne laufenden Server geschieht nichts. */
    suspend fun stop()
}

sealed interface ServerState {
    data object Stopped : ServerState

    data class Running(
        val port: Int,
    ) : ServerState
}

/** Startparameter. [port] 0 wählt einen freien Port. */
class ServerConfig(
    val port: Int = 0,
) {
    init {
        require(port in 0..MAX_PORT) { "Port muss zwischen 0 und $MAX_PORT liegen." }
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}
