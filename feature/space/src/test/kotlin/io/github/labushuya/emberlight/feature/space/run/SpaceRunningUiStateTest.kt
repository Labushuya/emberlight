package io.github.labushuya.emberlight.feature.space.run

import io.github.labushuya.emberlight.share.server.PauseCause
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ableitungen des Zustands der Gastgeber-Ansicht: Unterbrechung, Speicherstand und Warnung (Paket 3.3). */
class SpaceRunningUiStateTest {
    private val id = SpaceId.generate()

    @Test
    fun anActiveSpaceWithoutARunningServerIsInterrupted() {
        assertTrue(state(status = SpaceStatus.ACTIVE, expiresAt = NOW + HOUR).interrupted)
    }

    @Test
    fun anActiveSpaceWithoutAnEndIsInterruptedToo() {
        assertTrue(state(status = SpaceStatus.ACTIVE, expiresAt = null).interrupted)
    }

    @Test
    fun aRunningServerIsNeverInterrupted() {
        val running = state(status = SpaceStatus.ACTIVE, expiresAt = NOW + HOUR, runState = running())

        assertFalse(running.interrupted)
    }

    @Test
    fun aPausedServerIsNotInterruptedEither() {
        val paused =
            state(
                status = SpaceStatus.ACTIVE,
                expiresAt = NOW + HOUR,
                runState = RunState.Paused(id, "Test", PauseCause.NETWORK_LOST, NOW + HOUR, null),
            )

        assertFalse(paused.interrupted)
    }

    @Test
    fun anExpiredSpaceEndsInsteadOfBeingInterrupted() {
        assertFalse(state(status = SpaceStatus.ACTIVE, expiresAt = NOW - 1).interrupted)
    }

    @Test
    fun aDraftOrAnEndedSpaceIsNotInterrupted() {
        assertFalse(state(status = SpaceStatus.DRAFT, expiresAt = null).interrupted)
        assertFalse(state(status = SpaceStatus.ENDED, expiresAt = NOW + HOUR).interrupted)
    }

    @Test
    fun withoutARecordNothingIsInterrupted() {
        assertFalse(SpaceRunningUiState(loaded = true, nowMillis = NOW).interrupted)
    }

    @Test
    fun theStorageShareIsCappedAtOneHundredPercent() {
        val over = state(status = SpaceStatus.ACTIVE, expiresAt = null, usedBytes = 3 * GIB, limit = GIB)

        assertEquals(100, over.usedPercent)
        assertTrue(over.storageWarning)
    }

    @Test
    fun theStorageWarningStartsAtEightyPercent() {
        // Ein Limit von 100 Byte macht die Prozentwerte exakt, ohne Rundung.
        val below = state(status = SpaceStatus.ACTIVE, expiresAt = null, usedBytes = 79L, limit = 100L)
        val atLimit = state(status = SpaceStatus.ACTIVE, expiresAt = null, usedBytes = 80L, limit = 100L)
        assertFalse(below.storageWarning)
        assertTrue(atLimit.storageWarning)
    }

    @Test
    fun withoutALimitTheShareIsZero() {
        assertEquals(0, state(status = SpaceStatus.ACTIVE, expiresAt = null, usedBytes = GIB, limit = 0L).usedPercent)
    }

    @Test
    fun aRenewedInviteIsOnlyFlaggedWhileTheServerRuns() {
        val renewed = state(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR, runState = running().copy(inviteRenewed = true))
        val kept = state(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR, runState = running())

        assertTrue(renewed.inviteRenewed)
        assertFalse(kept.inviteRenewed)
        assertFalse(state(SpaceStatus.ACTIVE, expiresAt = NOW + HOUR).inviteRenewed)
    }

    private fun running() =
        RunState.Running(
            spaceId = id,
            spaceName = "Test",
            port = 8080,
            address = WifiAddress("192.168.1.20", 24),
            startedAtMillis = NOW - MINUTE,
            expiresAtMillis = NOW + HOUR,
        )

    private fun state(
        status: SpaceStatus,
        expiresAt: Long?,
        runState: RunState = RunState.Idle,
        usedBytes: Long = 0L,
        limit: Long = GIB,
    ): SpaceRunningUiState =
        SpaceRunningUiState(
            record =
                SpaceRecord(
                    id = id,
                    settings =
                        SpaceSettings(
                            name = "Test",
                            colorIndex = 0,
                            lifetime = Lifetime.Until(expiresAt ?: (NOW + HOUR)),
                            retentionDays = 7,
                            storageLimitBytes = limit,
                        ),
                    coverSha256 = null,
                    status = status,
                    createdAt = NOW - DAY,
                    updatedAt = NOW - DAY,
                    startedAt = NOW - MINUTE,
                    expiresAt = expiresAt,
                ),
            loaded = true,
            usedBytes = usedBytes,
            runState = runState,
            nowMillis = NOW,
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val GIB = 1024L * 1024L * 1024L
    }
}
