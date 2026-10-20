package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.run.PauseDetector
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Befehle an den Betrieb. Sie laufen über einen Kanal und werden nacheinander verarbeitet (ADR 0017, Ablauf). */
internal sealed interface RunCommand {
    data class Start(
        val spaceId: SpaceId,
        val port: Int,
    ) : RunCommand

    data class Stop(
        val reason: StopReason,
    ) : RunCommand

    /** Regelmäßiger Herzschlag: Ablauf prüfen, Pausen erkennen, Benachrichtigung aktualisieren. */
    data object Heartbeat : RunCommand

    /** Die Frist ist erreicht: Wecker oder eine Anfrage an einen abgelaufenen Space. */
    data object Expire : RunCommand

    /** Der Netzstand hat sich geändert. `null` heißt: kein WLAN mit Adresse. */
    data class Network(
        val address: WifiAddress?,
    ) : RunCommand

    /** Der Gastgeber bestätigt den Wechsel in ein neues Netz. */
    data object ConfirmNetwork : RunCommand
}

internal enum class StopReason {
    /** Der Gastgeber beendet den Space in der App. Der Space wird `ENDED`, das Ende ist jetzt. */
    USER,

    /** Die Frist ist um. Der Space wird `ENDED`, das Ende ist der Zeitpunkt der Frist. */
    EXPIRED,

    /** Der Dienst wird vom System beendet. Der Space bleibt aktiv, bis er beim nächsten Start abgeschlossen wird. */
    DESTROYED,
}

/** Ereignisse für Hinweise und Benachrichtigungen. Die Texte stehen in `strings.xml`, nicht hier. */
internal sealed interface RunEvent {
    /** Der Space kann nicht gestartet werden (kein aktiver Eintrag, Frist vorbei). */
    data object NotStartable : RunEvent

    /** Kein WLAN mit Adresse beim Start. */
    data object NoWifi : RunEvent

    /** Der Server ließ sich nicht starten. */
    data object StartFailed : RunEvent

    /** Der Server ließ sich nach einer Pause nicht wieder binden. Er bleibt pausiert. */
    data object ResumeFailed : RunEvent

    /** Das System hat den Dienst pausiert, eine Lücke im Herzschlag. */
    data class PauseDetected(
        val event: PauseEvent,
    ) : RunEvent

    /** Das WLAN ist weg. Der Server pausiert und setzt fort, sobald dasselbe Netz zurück ist. */
    data object NetworkLost : RunEvent

    /** Ein anderes Netz. Der Gastgeber muss den Wechsel bestätigen. */
    data class NetworkChanged(
        val address: WifiAddress,
    ) : RunEvent
}

/** Schnittstelle zur Umgebung des Betriebs: Speicher, Netz, Sperren, Wecker und Hinweise. Der Dienst setzt sie um. */
internal interface RunHost {
    /** Einrichtung eines aktiven Space, `null` wenn er nicht gestartet werden kann. */
    suspend fun setupFor(spaceId: SpaceId): ServerSetup?

    /** Öffnet Speicher und Server, noch ohne Bindung. */
    suspend fun openServer(
        setup: ServerSetup,
        closure: SpaceClosure,
    ): OpenedServer

    fun currentWifi(): WifiAddress?

    /** Netzänderungen. Der erste Wert ist der aktuelle Stand. */
    fun wifiChanges(): Flow<WifiAddress?>

    fun acquireLocks()

    fun releaseLocks()

    /** Wecker für das Ende, ungenau (ADR 0017). */
    fun scheduleExpiry(atMillis: Long)

    fun cancelExpiry()

    suspend fun endSpace(
        spaceId: SpaceId,
        endedAtMillis: Long,
    )

    suspend fun abortStart(spaceId: SpaceId)

    suspend fun recordPause(
        spaceId: SpaceId,
        event: PauseEvent,
    )

    fun onEvent(event: RunEvent)

    /** Aktualisiert die Dauermeldung des laufenden Space (z. B. „noch 2 Std.“). */
    fun onRunningTick(state: RunState.Running)

    /** Der Betrieb ist beendet, der Dienst darf enden. */
    fun onIdle()
}

/** Ein geöffneter Server. [release] gibt Speicher und Index frei. */
internal class OpenedServer(
    val server: LocalServer,
    val release: () -> Unit,
)

/**
 * Betrieb des Space als serieller Verarbeiter (ADR 0017). Start, Stopp, Pause und Fortsetzen laufen über einen Kanal und
 * werden nacheinander abgearbeitet. Damit überholt kein Stopp einen Start, und Sperren bleiben nie hängen. Die Klasse
 * kennt keine Android-Typen. Der Dienst ([SpaceServerService]) liefert die Umgebung über [RunHost].
 *
 * Zustände: [Phase.Idle] (kein Space), [Phase.Running] (Server nimmt Anfragen an), [Phase.Paused] (Space aktiv, Server
 * aus, wegen Netz). Eine Pause durch das System erkennt der Herzschlag (siehe [PauseDetector]).
 */
internal class SpaceRunner(
    private val scope: CoroutineScope,
    private val clock: RunClock,
    private val host: RunHost,
    private val state: MutableStateFlow<RunState>,
    private val heartbeatMillis: Long = SpaceRunPolicy.HEARTBEAT_INTERVAL_MS,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
) : SpaceClosure {
    private val commands = Channel<RunCommand>(Channel.UNLIMITED)
    private val pauses = PauseDetector()
    private var phase: Phase = Phase.Idle
    private var jobs: Jobs? = null

    /** Frist für die Abfrage aus den Anfragen. Gesetzt, solange der Server läuft oder pausiert ist. */
    @Volatile
    private var deadline: Deadline? = null

    /** Startet den Verarbeiter. Einmal aufrufen. */
    fun run(): Job = scope.launch { for (command in commands) handle(command) }

    /** Reiht einen Befehl ein. Blockiert nicht, auch aus Netz-Threads und Anfragen heraus. */
    fun submit(command: RunCommand) {
        commands.trySend(command)
    }

    /** Beendet den Verarbeiter. Der Dienst ruft das in `onDestroy`. Der Befehl gibt die Sperren frei. */
    fun shutdown() {
        submit(RunCommand.Stop(StopReason.DESTROYED))
        commands.close()
    }

    override fun isClosed(): Boolean {
        val frame = deadline ?: return false
        return SpaceRunPolicy.isClosed(
            expiresAtMillis = frame.expiresAtMillis,
            startedAtMillis = frame.startedAtMillis,
            startedElapsedMillis = frame.startedElapsedMillis,
            nowWallMillis = clock.wallMillis(),
            nowElapsedMillis = clock.elapsedMillis(),
        )
    }

    override fun onClosedRequest() {
        submit(RunCommand.Expire)
    }

    private suspend fun handle(command: RunCommand) {
        when (command) {
            is RunCommand.Start -> start(command)
            is RunCommand.Stop -> stop(command.reason)
            RunCommand.Heartbeat -> heartbeat()
            RunCommand.Expire -> if (phase !is Phase.Idle) stop(StopReason.EXPIRED) else host.onIdle()
            is RunCommand.Network -> network(command.address)
            RunCommand.ConfirmNetwork -> confirm()
        }
    }

    private suspend fun start(command: RunCommand.Start) {
        if (phase !is Phase.Idle) return
        val setup = host.setupFor(command.spaceId)
        if (setup == null) {
            host.onEvent(RunEvent.NotStartable)
            host.onIdle()
            return
        }
        val now = clock.wallMillis()
        val expires = setup.expiresAtMillis
        if (expires != null && expires <= now) {
            // Die Frist lief schon, bevor der Dienst den Space aufnehmen konnte: Der Space ist beendet, nicht Entwurf.
            if (setup.persisted) host.endSpace(setup.spaceId, expires)
            fail(setup, RunEvent.NotStartable, revert = false)
            return
        }
        val wifi = host.currentWifi()
        if (wifi == null) {
            fail(setup, RunEvent.NoWifi, revert = true)
            return
        }
        val opened =
            try {
                host.openServer(setup, this)
            } catch (failure: Exception) {
                fail(setup, RunEvent.StartFailed, revert = true)
                return
            }
        val port =
            try {
                val bound = opened.server.start(serverConfig(command.port, wifi, setup))
                host.acquireLocks()
                bound
            } catch (failure: Exception) {
                closeQuietly(opened)
                fail(setup, RunEvent.StartFailed, revert = true)
                return
            }

        val startedAt = setup.startedAtMillis ?: now
        deadline = expires?.let { Deadline(it, startedAt, clock.elapsedMillis()) }
        phase = Phase.Running(setup, opened, wifi, port)
        pauses.reset()
        startJobs()
        expires?.let { host.scheduleExpiry(it) }
        publish(runningState(setup, port, wifi, startedAt))
    }

    /**
     * Der Start scheitert. Mit [revert] geht der Space zurück auf Entwurf (wenn er gespeichert ist), sonst bleibt sein
     * Zustand. Der Dienst endet danach.
     */
    private suspend fun fail(
        setup: ServerSetup,
        event: RunEvent,
        revert: Boolean,
    ) {
        if (setup.persisted && revert) host.abortStart(setup.spaceId)
        host.onEvent(event)
        deadline = null
        phase = Phase.Idle
        publish(RunState.Idle)
        host.onIdle()
    }

    private suspend fun stop(reason: StopReason) {
        val current = phase
        if (current is Phase.Idle) {
            host.onIdle()
            return
        }
        jobs?.cancel()
        jobs = null
        host.cancelExpiry()
        // Bei Ablauf bleibt die Frist kurz gesetzt. Die Antwort 410 muss beim Gast ankommen, bevor der Server schließt.
        if (reason == StopReason.EXPIRED) delay(closeGraceMillis)
        val setup = setupOf(current)
        closeServer(current)
        host.releaseLocks()
        deadline = null
        if (setup.persisted) {
            val now = clock.wallMillis()
            when (reason) {
                StopReason.USER -> host.endSpace(setup.spaceId, now)
                StopReason.EXPIRED -> host.endSpace(setup.spaceId, minOf(now, setup.expiresAtMillis ?: now))
                StopReason.DESTROYED -> Unit
            }
        }
        phase = Phase.Idle
        publish(RunState.Idle)
        host.onIdle()
    }

    private suspend fun heartbeat() {
        val current = phase
        if (current is Phase.Idle) return
        val beat = pauses.beat(clock.wallMillis(), clock.elapsedMillis(), clock.uptimeMillis())
        if (isClosed()) {
            stop(StopReason.EXPIRED)
            return
        }
        if (current is Phase.Running) {
            if (beat != null) {
                host.recordPause(current.setup.spaceId, beat)
                host.onEvent(RunEvent.PauseDetected(beat))
            }
            host.onRunningTick(
                runningState(
                    current.setup,
                    current.port,
                    current.address,
                    current.setup.startedAtMillis ?: clock.wallMillis(),
                ),
            )
        }
    }

    private suspend fun network(address: WifiAddress?) {
        when (val current = phase) {
            is Phase.Idle -> {}

            is Phase.Running -> {
                when (val action = NetworkPolicy.whileRunning(current.address, address)) {
                    NetworkAction.Keep, is NetworkAction.Resume -> {}

                    NetworkAction.Lost -> {
                        pauseRunning(current, PauseCause.NETWORK_LOST, null, RunEvent.NetworkLost)
                    }

                    is NetworkAction.Changed -> {
                        pauseRunning(
                            current,
                            PauseCause.NETWORK_CHANGED,
                            action.address,
                            RunEvent.NetworkChanged(action.address),
                        )
                    }
                }
            }

            is Phase.Paused -> {
                when (val action = NetworkPolicy.whilePaused(current.lastAddress, address)) {
                    NetworkAction.Lost, NetworkAction.Keep -> {}

                    is NetworkAction.Resume -> {
                        resume(current, action.address)
                    }

                    is NetworkAction.Changed -> {
                        if (current.pending != action.address || current.cause != PauseCause.NETWORK_CHANGED) {
                            phase = current.copy(cause = PauseCause.NETWORK_CHANGED, pending = action.address)
                            publish(pausedState(setupOf(current), PauseCause.NETWORK_CHANGED, action.address))
                            host.onEvent(RunEvent.NetworkChanged(action.address))
                        }
                    }
                }
            }
        }
    }

    /** Der Gastgeber hat ein neues Netz bestätigt. Der Server bindet an die aktuelle Adresse. */
    private suspend fun confirm() {
        val paused = phase as? Phase.Paused ?: return
        if (paused.pending == null) return
        val current = host.currentWifi() ?: return
        resume(paused, current)
    }

    private suspend fun pauseRunning(
        running: Phase.Running,
        cause: PauseCause,
        pending: WifiAddress?,
        event: RunEvent,
    ) {
        try {
            running.opened.server.stop()
        } catch (failure: Exception) {
            // Der Stopp kann fehlschlagen. Der Zustand gilt trotzdem als pausiert, der Server nimmt nichts mehr an.
        }
        phase = Phase.Paused(running.setup, running.opened, running.address, running.port, cause, pending)
        publish(pausedState(running.setup, cause, pending))
        host.onEvent(event)
    }

    private suspend fun resume(
        paused: Phase.Paused,
        address: WifiAddress,
    ) {
        val setup = paused.setup
        val bound =
            bind(paused.opened.server, address, paused.port, setup) ?: run {
                host.onEvent(RunEvent.ResumeFailed)
                return
            }
        phase = Phase.Running(setup, paused.opened, address, bound)
        publish(runningState(setup, bound, address, setup.startedAtMillis ?: clock.wallMillis()))
    }

    private fun runningState(
        setup: ServerSetup,
        port: Int,
        address: WifiAddress,
        startedAtMillis: Long,
    ): RunState.Running =
        RunState.Running(
            spaceId = setup.spaceId,
            spaceName = setup.spaceName,
            port = port,
            address = address,
            startedAtMillis = startedAtMillis,
            expiresAtMillis = setup.expiresAtMillis,
        )

    /** Bindet an die Adresse. Gelingt der alte Port nicht, nimmt der Server einen freien. Liefert `null` bei Misserfolg. */
    private suspend fun bind(
        server: LocalServer,
        address: WifiAddress,
        preferredPort: Int,
        setup: ServerSetup,
    ): Int? {
        for (port in listOf(preferredPort, 0).distinct()) {
            try {
                return server.start(serverConfig(port, address, setup))
            } catch (failure: Exception) {
                // Nächster Versuch mit einem freien Port.
            }
        }
        return null
    }

    private fun startJobs() {
        val watch =
            scope.launch {
                host.wifiChanges().collect { address -> submit(RunCommand.Network(address)) }
            }
        val beat =
            scope.launch {
                while (true) {
                    delay(heartbeatMillis)
                    submit(RunCommand.Heartbeat)
                }
            }
        jobs = Jobs(watch, beat)
    }

    private fun pausedState(
        setup: ServerSetup,
        cause: PauseCause,
        pending: WifiAddress?,
    ): RunState.Paused =
        RunState.Paused(
            spaceId = setup.spaceId,
            spaceName = setup.spaceName,
            cause = cause,
            expiresAtMillis = setup.expiresAtMillis,
            pendingAddress = pending,
        )

    private fun publish(value: RunState) {
        state.value = value
    }

    private suspend fun closeServer(current: Phase) {
        val opened =
            when (current) {
                is Phase.Idle -> return
                is Phase.Running -> current.opened
                is Phase.Paused -> current.opened
            }
        closeQuietly(opened)
    }

    private suspend fun closeQuietly(opened: OpenedServer) {
        try {
            opened.server.stop()
        } catch (failure: Exception) {
            // Ein Fehler beim Stopp darf die Freigabe der Sperren nicht verhindern.
        }
        opened.release()
    }

    private fun setupOf(current: Phase): ServerSetup =
        when (current) {
            is Phase.Idle -> error("Kein Space aktiv.")
            is Phase.Running -> current.setup
            is Phase.Paused -> current.setup
        }

    private fun serverConfig(
        port: Int,
        address: WifiAddress,
        setup: ServerSetup,
    ): ServerConfig = ServerConfig(port = port, subnet = address.subnet, debugLogging = setup.debugLogging)

    private sealed interface Phase {
        data object Idle : Phase

        class Running(
            val setup: ServerSetup,
            val opened: OpenedServer,
            val address: WifiAddress,
            val port: Int,
        ) : Phase

        class Paused(
            val setup: ServerSetup,
            val opened: OpenedServer,
            val lastAddress: WifiAddress,
            val port: Int,
            val cause: PauseCause,
            val pending: WifiAddress?,
        ) : Phase {
            fun copy(
                cause: PauseCause,
                pending: WifiAddress?,
            ): Paused = Paused(setup, opened, lastAddress, port, cause, pending)
        }
    }

    private class Deadline(
        val expiresAtMillis: Long,
        val startedAtMillis: Long,
        val startedElapsedMillis: Long,
    )

    private class Jobs(
        val watch: Job,
        val heartbeat: Job,
    ) {
        fun cancel() {
            watch.cancel()
            heartbeat.cancel()
        }
    }

    companion object {
        /** Wartezeit vor dem Stopp bei Ablauf, damit die Antwort 410 beim Gast ankommt. */
        const val CLOSE_GRACE_MILLIS = 1_000L
    }
}
