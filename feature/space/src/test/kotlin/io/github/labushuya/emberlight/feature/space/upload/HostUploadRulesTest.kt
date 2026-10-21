package io.github.labushuya.emberlight.feature.space.upload

import io.github.labushuya.emberlight.feature.space.SpaceFixtures
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.location.NotRemovableReason
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regeln des Gastgeber-Uploads (Paket 3.5c): nur im laufenden Space, Fortschritt, Abbruch der Reihe. */
class HostUploadRulesTest {
    private val id = SpaceId.parse(SpaceFixtures.ID_HOCHZEIT)!!
    private val now = 1_000_000L

    private fun active(expiresAt: Long? = now + HOUR): SpaceRecord =
        SpaceFixtures
            .draft(SpaceFixtures.ID_HOCHZEIT, "Test", status = SpaceStatus.ACTIVE)
            .copy(startedAt = now - HOUR, expiresAt = expiresAt)

    @Test
    fun uploadsAreAllowedInTheRunningSpace() {
        assertTrue(HostUploadRules.canUpload(active(), id, serverRunning = true, nowMillis = now))
    }

    @Test
    fun uploadsWithoutANeverEndingDeadlineAreAllowed() {
        assertTrue(HostUploadRules.canUpload(active(expiresAt = null), id, serverRunning = true, nowMillis = now))
    }

    @Test
    fun noUploadWhileTheServerIsNotRunning() {
        assertFalse(HostUploadRules.canUpload(active(), id, serverRunning = false, nowMillis = now))
    }

    @Test
    fun noUploadWithoutASpaceOrForAnotherSpace() {
        assertFalse(HostUploadRules.canUpload(null, id, serverRunning = true, nowMillis = now))
        val other = SpaceId.parse(SpaceFixtures.ID_GEBURTSTAG)!!
        assertFalse(HostUploadRules.canUpload(active(), other, serverRunning = true, nowMillis = now))
    }

    @Test
    fun noUploadAfterTheEndOrOnADraft() {
        val ended = active().copy(status = SpaceStatus.ENDED, endedAt = now)
        val draft = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Entwurf")
        assertFalse(HostUploadRules.canUpload(ended, id, serverRunning = true, nowMillis = now))
        assertFalse(HostUploadRules.canUpload(draft, id, serverRunning = true, nowMillis = now))
    }

    @Test
    fun noUploadOnceTheDeadlineHasPassed() {
        val expired = active(expiresAt = now)
        assertFalse(HostUploadRules.canUpload(expired, id, serverRunning = true, nowMillis = now))
        assertTrue(HostUploadRules.canUpload(expired, id, serverRunning = true, nowMillis = now - 1))
    }

    @Test
    fun theFractionIsNullWithoutAKnownSize() {
        assertNull(HostUploadRules.fraction(UploadProgress(readBytes = 10, totalBytes = null)))
        assertNull(HostUploadRules.fraction(UploadProgress(readBytes = 10, totalBytes = 0)))
    }

    @Test
    fun theFractionIsClampedBetweenZeroAndOne() {
        assertEquals(0.25f, HostUploadRules.fraction(UploadProgress(25, 100))!!, 0.0001f)
        assertEquals(1f, HostUploadRules.fraction(UploadProgress(250, 100))!!, 0.0001f)
        assertEquals(0f, HostUploadRules.fraction(UploadProgress(0, 100))!!, 0.0001f)
    }

    @Test
    fun onlyAFullStorageStopsTheBatch() {
        assertTrue(HostUploadRules.stopsTheBatch(RejectReason.StorageFull))
        assertTrue(HostUploadRules.stopsTheBatch(RejectReason.DeviceStorageLow))
        assertFalse(HostUploadRules.stopsTheBatch(RejectReason.UnsupportedType))
        assertFalse(HostUploadRules.stopsTheBatch(RejectReason.TooLarge(limitBytes = 10)))
        assertFalse(HostUploadRules.stopsTheBatch(RejectReason.LocationNotRemovable(NotRemovableReason.STRUCTURE)))
    }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
    }
}
