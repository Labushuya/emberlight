package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laufende Uploads je Teilnehmer: Abbruch betrifft nur den entfernten Teilnehmer (ADR 0018). */
class ActiveUploadsTest {
    @Test
    fun cancelStopsOnlyTheUploadsOfThatParticipant() {
        val uploads = ActiveUploads()
        val removed = ParticipantId.generate()
        val kept = ParticipantId.generate()
        val removedJob = CompletableDeferred<Unit>()
        val keptJob = CompletableDeferred<Unit>()
        uploads.add(removed, removedJob)
        uploads.add(kept, keptJob)

        assertEquals(1, uploads.cancelOf(removed))

        assertTrue(removedJob.isCancelled)
        assertTrue(!keptJob.isCancelled)
        assertEquals(0, uploads.cancelOf(removed))
    }

    @Test
    fun finishedUploadsAreForgotten() {
        val uploads = ActiveUploads()
        val participant = ParticipantId.generate()
        val job = CompletableDeferred<Unit>()
        uploads.add(participant, job)
        uploads.remove(participant, job)

        assertEquals(0, uploads.cancelOf(participant))
        assertTrue(!job.isCancelled)
    }
}
