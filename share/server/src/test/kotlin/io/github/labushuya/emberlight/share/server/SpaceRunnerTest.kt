package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.run.ActiveSpan
import io.github.labushuya.emberlight.share.space.run.MemoryRunBeatStore
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verarbeiter des Betriebs (ADR 0017). Die Zeit ist virtuell, es gibt keine echten Pausen. Der Server, die Sperren, das
 * Netz und die Datenbank sind Fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpaceRunnerTest {
    private val clock = FakeClock()
    private val wifi = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
    private val otherWifi = WifiAddress("10.0.0.7", 24, gateway = "10.0.0.1")
    private val spaceId = SpaceId.generate()
    private val expires = START + HOUR

    @Test
    fun theFixedPortOfTheSpaceIsUsedAndNotSavedAgain() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup().copy(port = FIXED_PORT)

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            val running = env.state.value as RunState.Running
            assertEquals(FIXED_PORT, running.port)
            assertFalse(running.addressChanged)
            assertTrue(env.host.savedPorts.isEmpty())
        }

    @Test
    fun aBusyFixedPortMovesTheSpaceToANewAddressAndSavesIt() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup().copy(port = FIXED_PORT)
            env.busyPort = FIXED_PORT

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            val running = env.state.value as RunState.Running
            assertEquals(FAKE_PORT, running.port)
            assertTrue("Die Adressänderung muss sichtbar sein.", running.addressChanged)
            assertEquals(listOf(FAKE_PORT), env.host.savedPorts)
        }

    @Test
    fun aRenewedInviteIsShownAsAStatusOfTheRunningSpace() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.inviteRenewed = true

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertTrue((env.state.value as RunState.Running).inviteRenewed)
        }

    @Test
    fun aSettledRenewedInviteHidesTheHintAndTheHeartbeatDoesNotBringItBack() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.inviteRenewed = true
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.InviteSettled)
            runCurrent()
            assertFalse((env.state.value as RunState.Running).inviteRenewed)

            advanceTimeBy(SpaceRunPolicy.HEARTBEAT_INTERVAL_MS)
            runCurrent()

            val running = env.state.value as RunState.Running
            assertFalse(running.inviteRenewed)
            assertEquals(1, env.servers.single().starts)
        }

    @Test
    fun settlingWithoutARenewedInviteChangesNothing() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            val before = env.state.value

            env.submit(RunCommand.InviteSettled)
            runCurrent()

            assertEquals(before, env.state.value)
        }

    @Test
    fun aSettleDuringTheGraceKeepsTheServiceAlive() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            env.submit(RunCommand.InviteSettled)
            runCurrent()

            assertEquals(0, env.host.idles)
            assertEquals(0, env.servers.single().stops)
        }

    @Test
    fun aSettleWithoutAnySpaceEndsTheService() =
        runTest {
            val env = Env(this)
            env.submit(RunCommand.InviteSettled)
            runCurrent()

            assertEquals(1, env.host.idles)
        }

    @Test
    fun aFirstStartWithoutAFixedPortSavesTheBoundPort() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertEquals(listOf(FAKE_PORT), env.host.savedPorts)
            assertFalse((env.state.value as RunState.Running).addressChanged)
        }

    @Test
    fun aStartBindsTheServerTakesTheLocksAndSchedulesTheEnd() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            val running = env.state.value as RunState.Running
            assertEquals(spaceId, running.spaceId)
            assertEquals(FAKE_PORT, running.port)
            assertEquals(1, env.host.locksAcquired)
            assertEquals(listOf(expires), env.host.expiries)
            assertEquals(1, env.servers.single().starts)
        }

    @Test
    fun aStartWithoutWifiRevertsTheSpaceToDraftAndEnds() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.wifiNow = null

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(RunEvent.NoWifi), env.host.events)
            assertEquals(listOf(spaceId), env.host.aborted)
            assertEquals(1, env.host.idles)
            assertEquals(0, env.host.locksAcquired)
        }

    @Test
    fun aServerThatFailsToStartLeavesNoLocksAndRevertsTheSpace() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.failStart = true

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(RunEvent.StartFailed), env.host.events)
            assertEquals(listOf(spaceId), env.host.aborted)
            assertEquals(0, env.host.locksAcquired)
        }

    @Test
    fun aSpaceThatHasEndedBeforeTheStartIsEndedNotReverted() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup(expiresAt = START - 1)

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(RunEvent.NotStartable), env.host.events)
            assertEquals(listOf(spaceId to START - 1), env.host.ended)
            assertTrue(env.host.aborted.isEmpty())
        }

    @Test
    fun aUserStopEndsTheSpaceNowAndKeepsTheServerForTheGrace() =
        runTest {
            val env = runningEnv()
            clock.advance(5 * MINUTE)

            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            assertFalse(env.host.expiryCancelled.isEmpty())
            assertGraceAfter(env, endedAt = START + 5 * MINUTE)
        }

    @Test
    fun anExpiryByHeartbeatKeepsTheServerForTheGrace() =
        runTest {
            val env = runningEnv()
            clock.advance(HOUR + 5 * MINUTE)
            advanceTimeBy(SpaceRunPolicy.HEARTBEAT_INTERVAL_MS)
            runCurrent()

            assertGraceAfter(env, endedAt = expires)
        }

    @Test
    fun anExpiryByTheAlarmKeepsTheServerForTheGrace() =
        runTest {
            val env = runningEnv()
            clock.advance(HOUR + 1)

            env.submit(RunCommand.Expire)
            runCurrent()

            assertGraceAfter(env, endedAt = expires)
        }

    @Test
    fun anExpiryByARequestKeepsTheServerForTheGrace() =
        runTest {
            val env = runningEnv()
            clock.advance(HOUR + 1)

            env.runner.onClosedRequest()
            runCurrent()

            assertGraceAfter(env, endedAt = expires)
        }

    @Test
    fun aUserEndWhilePausedNeedsNoGraceBecauseTheServerIsOff() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Network(null))
            runCurrent()

            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            // Die Pause hat den Server schon gestoppt. Hier geht es um den Betrieb: kein Nachlauf, sofort Leerlauf.
            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.host.locksReleased)
            assertEquals(1, env.host.idles)
            assertEquals(0, env.host.closings)
            assertNull(env.runner.endedAtMillis())
        }

    @Test
    fun aDestroyedServiceEndsTheGraceAtOnce() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()

            assertEquals(1, env.servers.single().stops)
            assertEquals(1, env.host.locksReleased)
            assertEquals(1, env.host.idles)
            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS + 1)
            runCurrent()
            assertEquals(1, env.servers.size)
            assertEquals(RunState.Idle, env.state.value)
        }

    @Test
    fun repeatedEndsDuringTheGraceChangeNothing() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            env.runner.onClosedRequest()
            env.submit(RunCommand.Stop(StopReason.USER))
            env.submit(RunCommand.Expire)
            runCurrent()

            assertEquals(1, env.host.ended.size)
            assertEquals(0, env.servers.single().stops)
            assertEquals(1, env.host.closings)
        }

    @Test
    fun theGraceIsNotCutShortByAnOldAlarmOrAHeartbeat() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS / 2)
            env.submit(RunCommand.Heartbeat)
            env.submit(RunCommand.Expire)
            runCurrent()

            assertEquals(0, env.servers.single().stops)
            assertEquals(0, env.host.idles)
        }

    @Test
    fun theHeartbeatEndsAnExpiredSpaceAtItsPlannedEnd() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            clock.advance(HOUR + 5 * MINUTE)
            advanceTimeBy(SpaceRunPolicy.HEARTBEAT_INTERVAL_MS)
            runCurrent()
            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS + 1)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(spaceId to expires), env.host.ended)
            assertEquals(1, env.servers.single().stops)
        }

    @Test
    fun theClosingGraceIsSixtySecondsAfterTheEnd() {
        assertEquals(60_000L, SpaceRunner.CLOSE_GRACE_MILLIS)
    }

    @Test
    fun aRequestToAClosedSpaceAnswersAndThenTheRunStops() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            clock.advance(HOUR + 1)

            // Die Anfrage erkennt den Ablauf sofort, bevor der Betrieb reagiert.
            assertTrue(env.runner.isClosed())
            env.runner.onClosedRequest()
            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS - 1)
            runCurrent()
            assertEquals(0, env.servers.single().stops)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(spaceId to expires), env.host.ended)
        }

    @Test
    fun anExpireCommandWithoutARunOnlyLetsTheServiceEnd() =
        runTest {
            val env = Env(this)
            env.submit(RunCommand.Expire)
            runCurrent()

            assertEquals(1, env.host.idles)
            assertTrue(env.host.ended.isEmpty())
        }

    @Test
    fun aLostWifiPausesTheServerAndTheSameNetworkResumesIt() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.wifiNow = null
            env.wifiFlow.emit(null)
            runCurrent()
            assertEquals(PauseCause.NETWORK_LOST, (env.state.value as RunState.Paused).cause)
            assertEquals(1, env.servers.single().stops)
            assertEquals(RunEvent.NetworkLost, env.host.events.last())

            env.wifiNow = wifi
            env.wifiFlow.emit(wifi)
            runCurrent()

            assertTrue(env.state.value is RunState.Running)
            assertEquals(2, env.servers.single().starts)
            assertEquals(0, env.host.ended.size)
        }

    @Test
    fun aDifferentNetworkPausesAndOnlyTheConfirmationResumesOnTheNewAddress() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.wifiNow = otherWifi
            env.wifiFlow.emit(otherWifi)
            runCurrent()
            val paused = env.state.value as RunState.Paused
            assertEquals(PauseCause.NETWORK_CHANGED, paused.cause)
            assertEquals(otherWifi, paused.pendingAddress)
            assertEquals(RunEvent.NetworkChanged(otherWifi), env.host.events.last())

            // Ohne Bestätigung bleibt der Server aus, auch wenn das neue Netz steht.
            runCurrent()
            assertTrue(env.state.value is RunState.Paused)

            env.submit(RunCommand.ConfirmNetwork)
            runCurrent()

            val running = env.state.value as RunState.Running
            assertEquals(otherWifi, running.address)
            assertEquals(2, env.servers.single().starts)
        }

    @Test
    fun confirmingWithoutAPendingNetworkChangesNothing() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.ConfirmNetwork)
            runCurrent()

            assertTrue(env.state.value is RunState.Running)
            assertEquals(1, env.servers.single().starts)
        }

    @Test
    fun aResumeThatCannotBindKeepsTheSpacePausedAndReportsIt() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.wifiNow = null
            env.wifiFlow.emit(null)
            runCurrent()
            env.servers.single().refuseStarts = true

            env.wifiNow = wifi
            env.wifiFlow.emit(wifi)
            runCurrent()

            assertTrue(env.state.value is RunState.Paused)
            assertEquals(RunEvent.ResumeFailed, env.host.events.last())
        }

    @Test
    fun aPauseOfTheSystemIsRecordedWithItsStartAndDuration() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Heartbeat)
            runCurrent()

            // Der Dienst bekommt zehn Minuten lang keinen Herzschlag. Die Uhr läuft in dieser Zeit weiter.
            clock.skip(10 * MINUTE)
            env.submit(RunCommand.Heartbeat)
            runCurrent()

            val (pausedSpace, event) = env.host.pauses.single()
            assertEquals(spaceId, pausedSpace)
            assertEquals(10 * MINUTE, event.durationMillis)
            assertEquals(0L, event.deepSleepMillis)
            assertEquals(START, event.startedAtMillis)
            assertEquals(RunEvent.PauseDetected(event), env.host.events.last())
        }

    @Test
    fun quickSequencesDuringTheGraceQueueTheLastStartAndLeaveNoLockBehind() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.USER))
            env.submit(RunCommand.Start(spaceId, port = 0))
            env.submit(RunCommand.Stop(StopReason.USER))
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            // Während des Nachlaufs läuft kein zweiter Start. Die Sperren bleiben bis zum Ende des Nachlaufs.
            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.host.locksHeldNow)
            assertEquals(1, env.servers.size)

            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS + 1)
            runCurrent()

            assertTrue("zuletzt gestartet", env.state.value is RunState.Running)
            assertEquals(2, env.host.locksAcquired)
            assertEquals(1, env.host.locksReleased)
            assertEquals(1, env.host.locksHeldNow)
            assertEquals(2, env.servers.size)
            assertEquals(1, env.servers.last().starts)
            assertEquals(1, env.host.ended.size)
        }

    @Test
    fun aStartDuringTheGraceIsQueuedWithAMessageAndRunsAfterIt() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertTrue(RunEvent.StartQueued in env.host.events)
            assertEquals(1, env.servers.size)

            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS + 1)
            runCurrent()

            assertTrue(env.state.value is RunState.Running)
            assertEquals(2, env.servers.size)
            assertEquals(0, env.host.idles)
            // Die Warteschlange ist abgearbeitet, nachdem der Start entschieden ist. Die Meldung „wartet“ endet dann.
            assertEquals(RunEvent.StartQueueEnded, env.host.events.last())
        }

    @Test
    fun aQueuedStartDroppedWithTheServiceEndsItsQueueToo() =
        runTest {
            val env = runningEnv()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()

            assertTrue(RunEvent.StartQueueEnded in env.host.events)
            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.servers.size)
        }

    @Test
    fun aStartAfterTheGraceStartsAtOnce() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.USER))
            advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS + 1)
            runCurrent()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            assertTrue(env.state.value is RunState.Running)
            assertEquals(2, env.servers.size)
            assertEquals(1, env.host.locksHeldNow)
        }

    @Test
    fun shutdownReleasesLocksAndStopsTheServerButKeepsTheSpaceActive() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.runner.shutdown()
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.servers.single().stops)
            assertEquals(1, env.host.locksReleased)
            assertTrue(
                "Der Space bleibt aktiv und wird nicht beendet, auch nicht bei Prozess-Tod.",
                env.host.ended.isEmpty(),
            )
            assertEquals(START, env.beats.load()?.startedAtMillis)
        }

    @Test
    fun aRestartAfterAProcessDeathResumesTheSpaceOnTheSamePortAndRecordsTheInterruption() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            clock.advance(MINUTE)
            env.submit(RunCommand.Heartbeat)
            runCurrent()

            // Der Prozess stirbt. Zehn Minuten ohne Herzschlag. Der Neustart findet den Lauf im Speicher.
            env.runner.shutdown()
            runCurrent()
            clock.skip(10 * MINUTE)
            env.restartRunner(this).submit(RunCommand.Restart)
            runCurrent()

            val running = env.state.value as RunState.Running
            assertEquals(spaceId, running.spaceId)
            assertEquals(FAKE_PORT, running.port)
            assertEquals(2, env.servers.size)
            assertEquals(1, env.servers.last().starts)
            val (pausedSpace, event) = env.host.pauses.single()
            assertEquals(spaceId, pausedSpace)
            assertEquals(START + MINUTE, event.startedAtMillis)
            assertEquals(10 * MINUTE, event.durationMillis)
            assertTrue(env.host.ended.isEmpty())
        }

    @Test
    fun aRestartWithoutTheBatteryExemptionLeavesTheSpaceActiveAndIdle() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()
            env.host.exempt = false
            clock.skip(10 * MINUTE)

            env.submit(RunCommand.Restart)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.servers.size)
            assertTrue(env.host.ended.isEmpty())
            assertTrue(env.host.pauses.isEmpty())
            assertNotNull(env.beats.load())
        }

    @Test
    fun aRestartOfAnExpiredSpaceEndsItAtItsPlannedEnd() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()
            clock.skip(HOUR + 5 * MINUTE)

            env.submit(RunCommand.Restart)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(spaceId to expires), env.host.ended)
            assertNull(env.beats.load())
            assertEquals(1, env.servers.size)
        }

    @Test
    fun aRestartWithoutAnActiveSpaceOnlyStopsTheService() =
        runTest {
            val env = Env(this)
            env.submit(RunCommand.Restart)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(1, env.host.idles)
            assertTrue(env.servers.isEmpty())
        }

    @Test
    fun aResumeWithoutWifiDoesNotRevertTheSpaceToDraft() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()
            env.wifiNow = null

            env.submit(RunCommand.Restart)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(RunEvent.NoWifi), env.host.events)
            assertTrue("Der Space bleibt aktiv, er ist nur unterbrochen.", env.host.aborted.isEmpty())
            assertNotNull(env.beats.load())
        }

    @Test
    fun aWakeUpWhileIdleNotifiesAboutAnOpenInterruptedSpaceOnly() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()

            env.submit(RunCommand.Expire)
            runCurrent()

            assertEquals(RunState.Idle, env.state.value)
            assertEquals(listOf(RunEvent.Interrupted), env.host.events)
            assertTrue(env.host.ended.isEmpty())
        }

    @Test
    fun aWakeUpWhileIdleEndsAnExpiredSpace() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()
            clock.skip(HOUR + 1)

            env.submit(RunCommand.Expire)
            runCurrent()

            assertEquals(listOf(spaceId to expires), env.host.ended)
            assertEquals(emptyList<RunEvent>(), env.host.events)
            assertNull(env.beats.load())
        }

    @Test
    fun anEarlyWakeUpWhileRunningDoesNotEndTheSpace() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.Expire)
            runCurrent()

            assertTrue(env.state.value is RunState.Running)
            assertEquals(0, env.servers.single().stops)
            assertTrue(env.host.ended.isEmpty())
        }

    @Test
    fun aUserStopClearsTheSavedRunSoNothingIsResumedLater() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()

            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            assertNull(env.beats.load())
        }

    @Test
    fun aResumeFromTheScreenKeepsTheSpaceActiveIfTheServerCannotStart() =
        runTest {
            val env = Env(this)
            env.setup = persistedSetup()
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.DESTROYED))
            runCurrent()
            env.failStart = true

            env.submit(RunCommand.Start(spaceId, port = 0, resume = true))
            runCurrent()

            assertEquals(listOf(RunEvent.StartFailed), env.host.events)
            assertTrue(env.host.aborted.isEmpty())
        }

    @Test
    fun aSetupWithoutPersistenceNeverWritesToTheDatabase() =
        runTest {
            val env = Env(this)
            env.setup = ServerSetup(spaceId = spaceId, spaceName = "Debug", expiresAtMillis = null, persisted = false)
            env.submit(RunCommand.Start(spaceId, port = 0))
            runCurrent()
            env.submit(RunCommand.Stop(StopReason.USER))
            runCurrent()

            assertTrue(env.host.ended.isEmpty())
            assertTrue(env.host.aborted.isEmpty())
            assertTrue(env.host.pauses.isEmpty())
        }

    /** Ein laufender, gespeicherter Space. Die Zeit steht am Start. */
    private fun TestScope.runningEnv(): Env {
        val env = Env(this)
        env.setup = persistedSetup()
        env.submit(RunCommand.Start(spaceId, port = 0))
        runCurrent()
        return env
    }

    /**
     * Prüft den Nachlauf nach einem Ende (ADR 0017, Nachtrag 21.10.2026): Der Space ist sofort beendet, die Stoppzeit gilt
     * für jede Anfrage, der Server läuft bis zum Ablauf der Frist und stoppt dann, die Sperren fallen erst danach.
     */
    private fun TestScope.assertGraceAfter(
        env: Env,
        endedAt: Long,
    ) {
        assertEquals(listOf(spaceId to endedAt), env.host.ended)
        assertEquals(RunState.Idle, env.state.value)
        assertEquals(1, env.host.closings)
        assertTrue(env.runner.isClosed())
        assertEquals(endedAt, env.runner.endedAtMillis())
        assertEquals(0, env.servers.single().stops)
        assertEquals(0, env.host.locksReleased)

        advanceTimeBy(SpaceRunner.CLOSE_GRACE_MILLIS - 1)
        runCurrent()
        assertEquals(0, env.servers.single().stops)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, env.servers.single().stops)
        assertEquals(1, env.host.locksReleased)
        assertEquals(1, env.host.idles)
    }

    private fun persistedSetup(expiresAt: Long = expires) =
        ServerSetup(
            spaceId = spaceId,
            spaceName = "Test",
            startedAtMillis = START,
            expiresAtMillis = expiresAt,
            persisted = true,
        )

    /** Umgebung eines Tests: Verarbeiter, Fakes und ein kleiner Zustandsspiegel. */
    private inner class Env(
        testScope: TestScope,
    ) {
        val host = FakeHost()
        val servers = mutableListOf<FakeServer>()
        val wifiFlow = FakeWifi()
        var wifiNow: WifiAddress? = WIFI
        var setup: ServerSetup? = null
        var failStart = false

        /** Ein Port, der belegt ist: Bindung schlägt fehl. */
        var busyPort: Int? = null
        var inviteRenewed: Boolean = false
        private val stateFlow = MutableStateFlow<RunState>(RunState.Idle)
        val state: StateFlow<RunState> get() = stateFlow.asStateFlow()
        val beats = MemoryRunBeatStore()
        var runner: SpaceRunner

        init {
            host.env = this
            runner = newRunner(testScope)
        }

        /** Ein neuer Verarbeiter auf denselben Fakes und demselben Speicher, wie nach einem Neustart des Dienstes. */
        fun restartRunner(testScope: TestScope): SpaceRunner {
            runner = newRunner(testScope)
            return runner
        }

        private fun newRunner(testScope: TestScope): SpaceRunner =
            SpaceRunner(
                scope = testScope.backgroundScope,
                clock = clock,
                host = host,
                state = stateFlow,
                beats = beats,
                heartbeatMillis = SpaceRunPolicy.HEARTBEAT_INTERVAL_MS,
                closeGraceMillis = SpaceRunner.CLOSE_GRACE_MILLIS,
            ).also { it.run() }

        fun submit(command: RunCommand) = runner.submit(command)

        fun newServer(): FakeServer = FakeServer(this).also { servers += it }
    }

    /** Wifi-Fake: der Verlauf der Netzsignale. */
    private class FakeWifi {
        private val flow = MutableSharedFlow<WifiAddress?>(replay = 0, extraBufferCapacity = 16)

        fun flow(): Flow<WifiAddress?> = flow.asSharedFlow()

        suspend fun emit(address: WifiAddress?) {
            flow.emit(address)
        }
    }

    private inner class FakeHost : RunHost {
        lateinit var env: Env
        val savedPorts = mutableListOf<Int>()

        override suspend fun savePort(
            spaceId: SpaceId,
            port: Int,
        ) {
            savedPorts += port
        }

        var locksAcquired = 0
        var locksReleased = 0
        var locksHeldNow = 0
        var idles = 0
        val expiries = mutableListOf<Long>()
        val expiryCancelled = mutableListOf<Unit>()
        val ended = mutableListOf<Pair<SpaceId, Long>>()
        val aborted = mutableListOf<SpaceId>()
        val pauses = mutableListOf<Pair<SpaceId, PauseEvent>>()
        val events = mutableListOf<RunEvent>()

        var exempt = true

        override suspend fun setupFor(
            spaceId: SpaceId,
            resuming: Boolean,
        ): ServerSetup? = env.setup?.takeIf { it.spaceId == spaceId }

        /** Der aktive Space der Datenbank: der Einrichtungsstand, solange er nicht beendet oder abgebrochen wurde. */
        override suspend fun activeSpan(): ActiveSpan? {
            val setup = env.setup?.takeIf { it.persisted } ?: return null
            if (ended.any { it.first == setup.spaceId } || setup.spaceId in aborted) return null
            return ActiveSpan(setup.spaceId, setup.startedAtMillis, setup.expiresAtMillis)
        }

        override fun batteryExempt(): Boolean = exempt

        override suspend fun openServer(
            setup: ServerSetup,
            closure: SpaceClosure,
        ): OpenedServer = OpenedServer(env.newServer(), release = {}, inviteRenewed = env.inviteRenewed)

        override fun currentWifi(): WifiAddress? = env.wifiNow

        override fun wifiChanges(): Flow<WifiAddress?> = env.wifiFlow.flow()

        override fun acquireLocks() {
            locksAcquired++
            locksHeldNow++
        }

        override fun releaseLocks() {
            locksReleased++
            locksHeldNow = 0
        }

        override fun scheduleExpiry(atMillis: Long) {
            expiries += atMillis
        }

        override fun cancelExpiry() {
            expiryCancelled += Unit
        }

        override suspend fun endSpace(
            spaceId: SpaceId,
            endedAtMillis: Long,
        ) {
            ended += spaceId to endedAtMillis
        }

        override suspend fun abortStart(spaceId: SpaceId) {
            aborted += spaceId
        }

        override suspend fun recordPause(
            spaceId: SpaceId,
            event: PauseEvent,
        ) {
            pauses += spaceId to event
        }

        override fun onEvent(event: RunEvent) {
            events += event
        }

        override fun onRunningTick(state: RunState.Running) = Unit

        var closings = 0

        override fun onClosing() {
            closings++
        }

        override fun onIdle() {
            idles++
        }
    }

    /** Server-Fake mit Zählern. [refuseStarts] lässt jeden weiteren Start scheitern. */
    private inner class FakeServer(
        private val env: Env,
    ) : LocalServer {
        private val running = MutableStateFlow<ServerState>(ServerState.Stopped)
        private val startCount = AtomicInteger()
        private val stopCount = AtomicInteger()
        var refuseStarts = false
        val starts: Int get() = startCount.get()
        val stops: Int get() = stopCount.get()

        override val state: StateFlow<ServerState> = running.asStateFlow()

        override suspend fun start(config: ServerConfig): Int {
            startCount.incrementAndGet()
            if (env.failStart || refuseStarts) throw IllegalStateException("Bindung verweigert")
            if (config.port != 0 && config.port == env.busyPort) throw IllegalStateException("Port belegt")
            val port = if (config.port != 0) config.port else FAKE_PORT
            running.value = ServerState.Running(port)
            return port
        }

        override suspend fun stop() {
            stopCount.incrementAndGet()
            running.value = ServerState.Stopped
        }
    }

    /** Uhr des Tests. [skip] lässt die Zeit springen, ohne dass der Verarbeiter Herzschläge sieht. */
    private inner class FakeClock : RunClock {
        private var wall = START
        private var elapsed = 1_000_000L
        private var uptime = 1_000_000L

        override fun wallMillis(): Long = wall

        override fun elapsedMillis(): Long = elapsed

        override fun uptimeMillis(): Long = uptime

        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
            uptime += millis
        }

        /** Der Dienst pausiert: Die Wand-Uhr und die Laufzeit gehen weiter, der Herzschlag fehlt. */
        fun skip(millis: Long) = advance(millis)
    }

    private companion object {
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val FAKE_PORT = 8787
        const val FIXED_PORT = 5_000
        val WIFI = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
    }
}
