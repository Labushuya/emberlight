package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.run.ActiveSpan
import io.github.labushuya.emberlight.share.space.run.PauseDetector
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RestartDecision
import io.github.labushuya.emberlight.share.space.run.RestartPolicy
import io.github.labushuya.emberlight.share.space.run.RunBeat
import io.github.labushuya.emberlight.share.space.run.RunBeatStore
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunPolicy
import io.github.labushuya.emberlight.share.space.run.interruptionMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Befehle an den Betrieb. Sie laufen über einen Kanal und werden nacheinander verarbeitet (ADR 0017, Ablauf). */
internal sealed interface RunCommand {
    /**
     * Start eines Space. Mit [resume] setzt die Oberfläche oder der Neustart einen bereits aktiven, unterbrochenen Space
     * fort. Dann bleibt der Space bei einem Fehler aktiv und wird nicht auf Entwurf zurückgesetzt.
     */
    data class Start(
        val spaceId: SpaceId,
        val port: Int,
        val resume: Boolean = false,
    ) : RunCommand

    data class Stop(
        val reason: StopReason,
    ) : RunCommand

    /** Regelmäßiger Herzschlag: Ablauf prüfen, Pausen erkennen, Benachrichtigung aktualisieren. */
    data object Heartbeat : RunCommand

    /** Die Frist ist erreicht: Wecker oder eine Anfrage an einen abgelaufenen Space. */
    data object Expire : RunCommand

    /** Neustart des Dienstes ohne Auftrag (START_STICKY, Prozess-Tod). Entscheidet über den aktiven Space (ADR 0017). */
    data object Restart : RunCommand

    /** Der Netzstand hat sich geändert. `null` heißt: kein WLAN mit Adresse. */
    data class Network(
        val address: WifiAddress?,
    ) : RunCommand

    /** Der Gastgeber bestätigt den Wechsel in ein neues Netz. */
    data object ConfirmNetwork : RunCommand

    /**
     * Der Gastgeber hat den Hinweis zur automatisch erneuerten Einladung bestätigt oder die Einladung selbst erneuert. Der
     * Hinweis kehrt bis zum nächsten Start nicht zurück (Paket 3.4b).
     */
    data object InviteSettled : RunCommand
}

internal enum class StopReason {
    /** Der Gastgeber beendet den Space in der App. Der Space wird `ENDED`, das Ende ist jetzt. */
    USER,

    /** Die Frist ist um. Der Space wird `ENDED`, das Ende ist der Zeitpunkt der Frist. */
    EXPIRED,

    /**
     * Der Dienst wird beendet (System, Prozess-Tod). Der Space bleibt `ACTIVE` und wird nicht beendet. Der letzte Herzschlag
     * bleibt gespeichert, damit der Neustart oder der Gastgeber den Lauf fortsetzt.
     */
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

    /** Der Space ist aktiv, aber nicht gestartet (Unterbrechung ohne Server). Die Benachrichtigung öffnet die App. */
    data object Interrupted : RunEvent
}

/** Schnittstelle zur Umgebung des Betriebs: Speicher, Netz, Sperren, Wecker und Hinweise. Der Dienst setzt sie um. */
internal interface RunHost {
    /**
     * Einrichtung eines aktiven Space, `null` wenn er nicht gestartet werden kann. Bei [resuming] bleibt ein Space bei
     * fehlender Berechtigung aktiv, statt auf Entwurf zurückzufallen.
     */
    suspend fun setupFor(
        spaceId: SpaceId,
        resuming: Boolean,
    ): ServerSetup?

    /** Der aktive Space aus der Datenbank, `null` wenn keiner aktiv ist. */
    suspend fun activeSpan(): ActiveSpan?

    /** `true`, wenn die App von der Akku-Optimierung ausgenommen ist. Ohne Ausnahme startet der Dienst nicht von selbst. */
    fun batteryExempt(): Boolean

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

    /** Merkt den gebundenen Port am Space, damit er beim nächsten Start gilt (Paket 3.4). */
    suspend fun savePort(
        spaceId: SpaceId,
        port: Int,
    ) {}

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
    /** `true`, wenn die Einladung nach einem Neustart neu entstand (Paket 3.4, ADR 0018). */
    val inviteRenewed: Boolean = false,
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
    private val beats: RunBeatStore,
    private val heartbeatMillis: Long = SpaceRunPolicy.HEARTBEAT_INTERVAL_MS,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
) : SpaceClosure {
    private val commands = Channel<RunCommand>(Channel.UNLIMITED)
    private val pauses = PauseDetector()
    private var phase: Phase = Phase.Idle
    private var jobs: Jobs? = null

    /** Der feste Port war belegt, der Space läuft auf einer neuen Adresse (Paket 3.4). */
    private var addressChanged = false

    /** Die Einladung entstand neu, weil das gespeicherte Geheimnis nicht lesbar war (ADR 0018). */
    private var inviteRenewed = false

    /** Der letzte Stand des laufenden, dauerhaften Space. Er wird bei jedem Herzschlag in [beats] festgehalten. */
    private var runBeat: RunBeat? = null

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
            RunCommand.Expire -> expire()
            RunCommand.Restart -> restart()
            is RunCommand.Network -> network(command.address)
            RunCommand.ConfirmNetwork -> confirm()
            RunCommand.InviteSettled -> settleInvite()
        }
    }

    private suspend fun start(command: RunCommand.Start) {
        if (phase !is Phase.Idle) return
        val setup = host.setupFor(command.spaceId, resuming = command.resume)
        if (setup == null) {
            host.onEvent(RunEvent.NotStartable)
            host.onIdle()
            return
        }
        val now = clock.wallMillis()
        val expires = setup.expiresAtMillis
        // Ein fortgesetzter Space bleibt bei einem Fehler aktiv. Nur ein frischer Start fällt auf Entwurf zurück.
        val revert = !command.resume
        if (expires != null && expires <= now) {
            // Die Frist lief schon, bevor der Dienst den Space aufnehmen konnte: Der Space ist beendet, nicht Entwurf.
            if (setup.persisted) endExpired(setup.spaceId, expires)
            fail(setup, RunEvent.NotStartable, revert = false)
            return
        }
        val wifi = host.currentWifi()
        if (wifi == null) {
            fail(setup, RunEvent.NoWifi, revert = revert)
            return
        }
        val opened =
            try {
                host.openServer(setup, this)
            } catch (failure: Exception) {
                fail(setup, RunEvent.StartFailed, revert = revert)
                return
            }
        inviteRenewed = opened.inviteRenewed
        val nowElapsed = clock.elapsedMillis()
        val prior = if (command.resume) beats.load()?.takeIf { it.spaceId == setup.spaceId } else null
        val port =
            try {
                // Der feste Port des Space gilt bei jedem Start (ADR 0017, Paket 3.4). Eine Wiederaufnahme nimmt den Port von
                // vorher, damit die Adresse für die Gäste gleich bleibt. Ist der Port belegt, nimmt der Server einen freien.
                val preferred = prior?.port ?: setup.port ?: command.port
                val bound =
                    bind(opened.server, wifi, preferred, setup) ?: throw IllegalStateException("Bindung verweigert.")
                addressChanged = setup.port != null && bound != setup.port
                if (setup.persisted && bound != setup.port) host.savePort(setup.spaceId, bound)
                host.acquireLocks()
                bound
            } catch (failure: Exception) {
                closeQuietly(opened)
                fail(setup, RunEvent.StartFailed, revert = revert)
                return
            }

        val startedAt = setup.startedAtMillis ?: now
        // Die Messung der Laufzeit läuft weiter, solange dasselbe Gerät läuft. Sonst beginnt sie jetzt (ohne Messung: Wand-Uhr).
        val startedElapsed =
            when {
                prior != null && nowElapsed >= prior.startedElapsedMillis -> prior.startedElapsedMillis
                command.resume -> nowElapsed - (now - startedAt).coerceAtLeast(0L)
                else -> nowElapsed
            }
        if (prior != null) {
            // Die Unterbrechung reicht vom letzten Herzschlag bis zur Wiederaufnahme (ADR 0017, Pausen).
            host.recordPause(
                setup.spaceId,
                PauseEvent(
                    startedAtMillis = prior.lastWallMillis,
                    durationMillis = interruptionMillis(prior, now, nowElapsed),
                    deepSleepMillis = 0L,
                ),
            )
        }
        deadline = expires?.let { Deadline(it, startedAt, startedElapsed) }
        phase = Phase.Running(setup, opened, wifi, port)
        pauses.reset()
        runBeat =
            if (setup.persisted) {
                RunBeat(setup.spaceId, startedAt, startedElapsed, now, nowElapsed, port).also(beats::save)
            } else {
                null
            }
        startJobs()
        expires?.let { host.scheduleExpiry(it) }
        publish(runningState(setup, port, wifi, startedAt))
    }

    /**
     * Wecker oder Anfrage an einen abgelaufenen Space. Läuft der Server, endet er nur, wenn die Frist wirklich um ist (ein
     * früher Wecker bleibt folgenlos). Ohne Server entscheidet der Stand nach dem Neustart, ob der Space endet.
     */
    private suspend fun expire() {
        if (phase !is Phase.Idle) {
            if (isClosed()) stop(StopReason.EXPIRED)
            return
        }
        when (val decision = decideIdle()) {
            is RestartDecision.Expired -> {
                endExpired(decision.spaceId, decision.endedAtMillis)
            }

            is RestartDecision.Resume, is RestartDecision.Interrupted -> {
                // Der Wecker startet den Server nicht selbst. Der Gastgeber soll den Space fortsetzen.
                host.onEvent(RunEvent.Interrupted)
            }

            RestartDecision.NothingToDo -> {}
        }
        host.onIdle()
    }

    /**
     * Neustart des Dienstes ohne Auftrag (Prozess-Tod, START_STICKY). Ein aktiver, nicht abgelaufener Space geht wieder an
     * den Server, wenn die Akku-Ausnahme besteht. Sonst bleibt er `ACTIVE` und unterbrochen, der Dienst endet.
     */
    private suspend fun restart() {
        if (phase !is Phase.Idle) return
        when (val decision = decideIdle()) {
            RestartDecision.NothingToDo, is RestartDecision.Interrupted -> {
                host.onIdle()
            }

            is RestartDecision.Expired -> {
                endExpired(decision.spaceId, decision.endedAtMillis)
                host.onIdle()
            }

            is RestartDecision.Resume -> {
                val port = beats.load()?.takeIf { it.spaceId == decision.spaceId }?.port ?: 0
                start(RunCommand.Start(decision.spaceId, port = port, resume = true))
            }
        }
    }

    /** Entscheidung über den aktiven Space, wenn kein Server läuft. */
    private suspend fun decideIdle(): RestartDecision =
        RestartPolicy.decide(
            active = host.activeSpan(),
            beat = beats.load(),
            nowWallMillis = clock.wallMillis(),
            nowElapsedMillis = clock.elapsedMillis(),
            batteryExempt = host.batteryExempt(),
        )

    /**
     * Beendet einen Space, dessen Frist um ist, während kein Server läuft. Eine Unterbrechung bis zum Ende wird als Pause
     * festgehalten, danach ist der gespeicherte Lauf erledigt.
     */
    private suspend fun endExpired(
        spaceId: SpaceId,
        endedAtMillis: Long,
    ) {
        beats.load()?.takeIf { it.spaceId == spaceId }?.let { beat ->
            host.recordPause(
                spaceId,
                PauseEvent(
                    startedAtMillis = beat.lastWallMillis,
                    durationMillis = (endedAtMillis - beat.lastWallMillis).coerceAtLeast(0L),
                    deepSleepMillis = 0L,
                ),
            )
        }
        host.endSpace(spaceId, endedAtMillis)
        beats.clear()
        runBeat = null
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
        // Ein beendeter Space hat keinen Lauf mehr zum Fortsetzen. Bei DESTROYED bleibt der letzte Herzschlag erhalten.
        if (reason != StopReason.DESTROYED) {
            beats.clear()
            runBeat = null
        }
        phase = Phase.Idle
        publish(RunState.Idle)
        host.onIdle()
    }

    private suspend fun heartbeat() {
        val current = phase
        if (current is Phase.Idle) return
        val wall = clock.wallMillis()
        val elapsed = clock.elapsedMillis()
        val beat = pauses.beat(wall, elapsed, clock.uptimeMillis())
        if (isClosed()) {
            stop(StopReason.EXPIRED)
            return
        }
        // Der letzte Herzschlag gilt auch in einer Pause: Ab hier beginnt eine Unterbrechung, falls der Prozess endet.
        runBeat = runBeat?.copy(lastWallMillis = wall, lastElapsedMillis = elapsed)?.also(beats::save)
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

    /** Der Gastgeber hat den Hinweis zur erneuerten Einladung beantwortet. Der Lauf selbst bleibt unverändert. */
    private fun settleInvite() {
        if (!inviteRenewed) return
        inviteRenewed = false
        val running = phase as? Phase.Running ?: return
        publish(
            runningState(
                running.setup,
                running.port,
                running.address,
                running.setup.startedAtMillis ?: clock.wallMillis(),
            ),
        )
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
            addressChanged = addressChanged,
            inviteRenewed = inviteRenewed,
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
        /**
         * Nachlauf nach dem Ablauf (ADR 0017, Nachtrag 3.5a): Authentifizierte Anfragen bekommen 410, Medien und Vorschauen
         * gibt es nicht mehr. Danach stoppt der Server. 60 s, damit die Gäste-Seite die Ende-Ansicht sicher erreicht.
         */
        const val CLOSE_GRACE_MILLIS = 60_000L
    }
}
