package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.run.ActiveSpan
import io.github.labushuya.emberlight.share.space.run.MemoryRunBeatStore
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Manuelles Ende über den echten Socket (ADR 0017, Nachtrag 21.10.2026). Der Gastgeber beendet den Space, der Verarbeiter
 * läuft mit einem echten Server. Danach antwortet der Server `410` mit der Stoppzeit, Medien gibt es nicht mehr, und nach
 * dem Nachlauf ist er geschlossen. Die Nachlaufzeit ist für den Test auf 1,5 Sekunden verkürzt.
 */
class SpaceEndGraceSocketTest {
    private val spaceId = SpaceId.generate()
    private val localSubnet = WifiAddress("127.0.0.1", 8)
    private val allowAll = RequestAuthorizer { Principal(ParticipantId.generate(), Role.GUEST) }

    @Test
    fun aManualEndAnswersGoneWithTheEndTimeAndNoMediaThenTheServerCloses() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val state = MutableStateFlow<RunState>(RunState.Idle)
            val host = SocketHost(spaceId, localSubnet, allowAll)
            val runner =
                SpaceRunner(
                    scope = scope,
                    clock = JvmRunClock,
                    host = host,
                    state = state,
                    beats = MemoryRunBeatStore(),
                    closeGraceMillis = GRACE_MILLIS,
                )
            runner.run()
            try {
                runner.submit(RunCommand.Start(spaceId, port = 0))
                val port = awaitRunning(state).port
                assertEquals(200, RawHttp.get(port, MEDIA, *auth(port)).status)

                runner.submit(RunCommand.Stop(StopReason.USER))
                withTimeout(TIMEOUT_MILLIS) { state.first { it is RunState.Idle } }

                val gone = RawHttp.get(port, MEDIA, *auth(port))
                val endedAt = checkNotNull(host.endedAt)
                assertEquals(410, gone.status)
                assertTrue(gone.body, gone.body.contains("\"endedAt\":\"${Instant.ofEpochMilli(endedAt)}\""))

                // Der Nachlauf läuft noch: Eine zweite Anfrage bekommt wieder 410, nicht 200.
                assertEquals(410, RawHttp.get(port, MEDIA, *auth(port)).status)

                delay(GRACE_MILLIS + AFTER_GRACE_MILLIS)
                val closed = runCatching { RawHttp.get(port, MEDIA, *auth(port)) }
                assertTrue("nach dem Nachlauf ist der Server geschlossen", closed.isFailure)
            } finally {
                runner.shutdown()
                scope.cancel()
            }
        }

    private fun auth(port: Int): Array<Pair<String, String>> =
        arrayOf("Host" to "127.0.0.1:$port", "Authorization" to "Bearer abc")

    private suspend fun awaitRunning(state: MutableStateFlow<RunState>): RunState.Running =
        withTimeout(TIMEOUT_MILLIS) { state.first { it is RunState.Running } } as RunState.Running

    /** Umgebung des Verarbeiters mit einem echten Server. Der Server liefert eine Medienroute, die der Test abfragt. */
    private class SocketHost(
        private val spaceId: SpaceId,
        private val wifi: WifiAddress,
        private val authorizer: RequestAuthorizer,
    ) : RunHost {
        @Volatile
        var endedAt: Long? = null

        private val setup =
            ServerSetup(
                spaceId = spaceId,
                spaceName = "Test",
                startedAtMillis = System.currentTimeMillis(),
                expiresAtMillis = System.currentTimeMillis() + HOUR,
                persisted = true,
            )

        override suspend fun setupFor(
            spaceId: SpaceId,
            resuming: Boolean,
        ): ServerSetup? = setup.takeIf { it.spaceId == spaceId }

        override suspend fun activeSpan(): ActiveSpan? = null

        override fun batteryExempt(): Boolean = true

        override suspend fun openServer(
            setup: ServerSetup,
            closure: SpaceClosure,
        ): OpenedServer =
            OpenedServer(
                KtorLocalServer(
                    routes = { get(MEDIA) { call.respondText("bild") } },
                    authorizer = authorizer,
                    closure = closure,
                ),
                release = {},
            )

        override fun currentWifi(): WifiAddress? = wifi

        override fun wifiChanges(): Flow<WifiAddress?> = flowOf(wifi)

        override fun acquireLocks() = Unit

        override fun releaseLocks() = Unit

        override fun scheduleExpiry(atMillis: Long) = Unit

        override fun cancelExpiry() = Unit

        override suspend fun endSpace(
            spaceId: SpaceId,
            endedAtMillis: Long,
        ) {
            endedAt = endedAtMillis
        }

        override suspend fun abortStart(spaceId: SpaceId) = Unit

        override suspend fun recordPause(
            spaceId: SpaceId,
            event: PauseEvent,
        ) = Unit

        override fun onEvent(event: RunEvent) = Unit

        override fun onRunningTick(state: RunState.Running) = Unit

        override fun onClosing() = Unit

        override fun onIdle() = Unit
    }

    private companion object {
        const val MEDIA = "/medium"
        const val GRACE_MILLIS = 1_500L
        const val AFTER_GRACE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 5_000L
        const val HOUR = 60 * 60 * 1_000L
    }
}

/** Uhr für den reinen JVM-Test. [io.github.labushuya.emberlight.share.space.run.SystemRunClock] braucht Android. */
private object JvmRunClock : RunClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

    override fun uptimeMillis(): Long = elapsedMillis()

    private const val NANOS_PER_MILLI = 1_000_000L
}
