package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import java.util.concurrent.Semaphore

/**
 * Parallelitätsgrenzen (ADR 0013, Startwerte): Anfragen insgesamt, Uploads insgesamt und Uploads je Teilnehmer.
 * Ein Überschreiten wird sofort abgelehnt, nicht gewartet: Anfragen mit `503`, Uploads mit `429`.
 */
class ServerGates(
    private val requestLimit: Int = REQUEST_LIMIT,
    private val uploadLimit: Int = UPLOAD_LIMIT,
    private val uploadsPerParticipant: Int = UPLOADS_PER_PARTICIPANT,
) {
    init {
        require(requestLimit > 0 && uploadLimit > 0 && uploadsPerParticipant > 0)
    }

    private val requests = Semaphore(requestLimit)
    private val uploadsByParticipant = HashMap<ParticipantId, Int>()
    private var uploadsRunning = 0

    /** Belegt einen Anfrage-Platz. `false`, wenn alle belegt sind. Jeder Erfolg braucht ein [leaveRequest]. */
    fun tryEnterRequest(): Boolean = requests.tryAcquire()

    fun leaveRequest() {
        requests.release()
    }

    /** Belegt einen Upload-Platz für [participant]. `false`, wenn die Gesamt- oder die eigene Grenze erreicht ist. */
    @Synchronized
    fun tryEnterUpload(participant: ParticipantId): Boolean {
        val own = uploadsByParticipant[participant] ?: 0
        if (uploadsRunning >= uploadLimit || own >= uploadsPerParticipant) return false
        uploadsRunning++
        uploadsByParticipant[participant] = own + 1
        return true
    }

    @Synchronized
    fun leaveUpload(participant: ParticipantId) {
        val own = uploadsByParticipant[participant] ?: return
        uploadsRunning--
        if (own <= 1) uploadsByParticipant.remove(participant) else uploadsByParticipant[participant] = own - 1
    }

    companion object {
        const val REQUEST_LIMIT = 16
        const val UPLOAD_LIMIT = 4
        const val UPLOADS_PER_PARTICIPANT = 2
    }
}
