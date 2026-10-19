package io.github.labushuya.emberlight.share.server

import kotlinx.coroutines.flow.StateFlow

/**
 * Lokaler HTTP-Server für Space und Gäste (ADR 0012). Aufrufer kennen nur dieses Interface, keine Ktor-Typen.
 *
 * Die Routen und die Zugriffsprüfung legt die jeweilige Implementierung fest. Der Aufrufer gibt Port und Netz vor.
 */
interface LocalServer {
    /** Aktueller Zustand. Wechselt auf [ServerState.Running], sobald der Port gebunden ist. */
    val state: StateFlow<ServerState>

    /**
     * Startet den Server auf der Adresse aus [ServerConfig.subnet]. Liefert den tatsächlich gebundenen Port. Läuft
     * bereits ein Server, wirft die Methode [IllegalStateException].
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

/**
 * Startparameter (ADR 0013, Netz). [subnet] trägt die Adresse der WLAN-Schnittstelle: Der Server bindet nur daran,
 * und nur Absender in diesem Subnetz werden angenommen. [port] 0 wählt einen freien Port.
 *
 * [debugLogging] schaltet die Adressen im Protokoll ein. Nur der Debug-Einstieg setzt es (ADR 0013, Logging).
 */
class ServerConfig(
    val port: Int = 0,
    val subnet: Ipv4Subnet,
    val debugLogging: Boolean = false,
) {
    init {
        require(port in 0..MAX_PORT) { "Port muss zwischen 0 und $MAX_PORT liegen." }
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}
