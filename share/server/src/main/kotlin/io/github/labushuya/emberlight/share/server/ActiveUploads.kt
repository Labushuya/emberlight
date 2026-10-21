package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Laufende Uploads je Teilnehmer (ADR 0018). Wird ein Teilnehmer entfernt oder widerrufen, bricht [cancelOf] seine
 * Uploads ab. Die Teildateien räumt die Vault weg.
 */
class ActiveUploads {
    private val jobs = HashMap<ParticipantId, MutableSet<Job>>()

    @Synchronized
    fun add(
        participant: ParticipantId,
        job: Job,
    ) {
        jobs.getOrPut(participant) { HashSet() }.add(job)
    }

    @Synchronized
    fun remove(
        participant: ParticipantId,
        job: Job,
    ) {
        val set = jobs[participant] ?: return
        set.remove(job)
        if (set.isEmpty()) jobs.remove(participant)
    }

    /** Bricht alle laufenden Uploads des Teilnehmers ab. Liefert die Anzahl. */
    fun cancelOf(participant: ParticipantId): Int {
        val running = synchronized(this) { jobs.remove(participant).orEmpty().toList() }
        running.forEach { it.cancel(CancellationException("Teilnehmer entfernt.")) }
        return running.size
    }
}
