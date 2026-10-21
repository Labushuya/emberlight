package io.github.labushuya.emberlight.feature.space.upload

import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus

/** Regeln des Gastgeber-Uploads ohne Android (Paket 3.5c). Rein, damit sie ohne Gerät geprüft werden. */
object HostUploadRules {
    /**
     * Hochladen ist nur im laufenden Space erlaubt: Der Space ist aktiv, nicht abgelaufen und der Server läuft. Nach dem Ende
     * und in einer Pause gibt es keine Uploads (Paket 3.5c, Paket 3.6 für das Nachhalten).
     */
    fun canUpload(
        record: SpaceRecord?,
        spaceId: SpaceId,
        serverRunning: Boolean,
        nowMillis: Long,
    ): Boolean {
        if (!serverRunning || record == null || record.id != spaceId) return false
        if (record.status != SpaceStatus.ACTIVE) return false
        val expires = record.expiresAt ?: return true
        return nowMillis < expires
    }

    /** Anteil des Fortschritts von 0 bis 1. `null`, wenn die Größe unbekannt ist: dann ist die Anzeige unbestimmt. */
    fun fraction(progress: UploadProgress): Float? {
        val total = progress.totalBytes?.takeIf { it > 0L } ?: return null
        return (progress.readBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Speicher voll oder Gerät knapp: Dann bricht die Reihe ab, weitere Dateien würden ebenso scheitern. */
    fun stopsTheBatch(reason: RejectReason): Boolean =
        reason == RejectReason.StorageFull || reason == RejectReason.DeviceStorageLow
}
