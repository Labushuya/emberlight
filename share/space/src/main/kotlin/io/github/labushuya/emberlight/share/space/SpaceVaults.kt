package io.github.labushuya.emberlight.share.space

import android.annotation.SuppressLint
import android.content.Context
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.preview.AndroidPreviewRenderer
import io.github.labushuya.emberlight.share.space.preview.PreviewRenderer
import io.github.labushuya.emberlight.share.space.preview.PreviewWorkers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Die eine Vault je Space im Prozess (Paket 3.5c, ADR 0019, Nachtrag 3.5c). App und Server holen ihre Vault hier, damit
 * Commit-Lock, Dedupe und Vorschau-Worker nur einmal existieren. Zwei Vaults auf demselben Space hätten zwei Sperren und
 * damit einen Wettlauf beim Dedupe.
 *
 * Die Vault entsteht beim ersten Zugriff. Ihre Grenzen gelten für die ganze Laufzeit des Prozesses. Das ist sicher, weil
 * Grenzen nur im Entwurf geändert werden (ADR 0016) und der Space vor dem Start feststeht. [SpaceVault.recover] läuft
 * genau einmal pro Vault, vor der ersten Annahme. Dadurch räumt es nie die Teildatei eines laufenden Uploads weg. Der
 * Vorschau-Worker läuft prozessweit, auch ohne laufenden Server, damit ein Gastgeber-Upload seine Vorschau bekommt.
 */
class SpaceVaults internal constructor(
    private val database: SpacesDatabase,
    private val spacesDir: File,
    private val renderer: PreviewRenderer,
    private val workers: PreviewWorkers,
) {
    private val lock = Mutex()
    private val vaults = HashMap<SpaceId, SpaceVault>()

    /**
     * Vault des Space mit den Grenzen [limits]. Ist sie schon offen, bleibt sie unverändert und [limits] wird ignoriert.
     * Der Server nutzt diesen Weg beim Start.
     */
    suspend fun vaultFor(
        spaceId: SpaceId,
        limits: SpaceLimits,
        inviteCipher: InviteSecretCipher,
    ): SpaceVault =
        lock.withLock {
            vaults[spaceId] ?: open(spaceId, limits, inviteCipher).also { vaults[spaceId] = it }
        }

    /**
     * Vault des gespeicherten Space, `null` wenn es ihn nicht gibt. Die Grenzen kommen aus der Tabelle. Die Oberfläche der
     * App nutzt diesen Weg, auch ohne laufenden Server.
     */
    suspend fun vaultOf(
        spaceId: SpaceId,
        inviteCipher: InviteSecretCipher,
    ): SpaceVault? {
        lock.withLock { vaults[spaceId] }?.let { return it }
        val row = database.spaces().find(spaceId.value) ?: return null
        return vaultFor(spaceId, SpaceLimits(storageLimitBytes = row.storageLimitBytes), inviteCipher)
    }

    /** Beendet den Vorschau-Worker. Nur für Tests gedacht, im Prozess lebt er bis zum Ende. */
    fun close() {
        workers.close()
    }

    private suspend fun open(
        spaceId: SpaceId,
        limits: SpaceLimits,
        inviteCipher: InviteSecretCipher,
    ): SpaceVault {
        val vault =
            SpaceVault.open(
                database,
                spacesDir,
                spaceId,
                limits,
                probeFor(spaceId),
                inviteCipher,
                renderer,
            )
        vault.recover()
        vault.startPreviews(workers.scope)
        return vault
    }

    @SuppressLint("UsableSpace")
    private fun probeFor(spaceId: SpaceId): StorageProbe = StorageProbe { File(spacesDir, spaceId.value).usableSpace }

    companion object {
        @Volatile
        private var shared: SpaceVaults? = null

        /**
         * Die eine Instanz für den ganzen Prozess. Die Hilt-Bindung in `feature:space` und der Server liefern dieselbe.
         * Sie wird nie geschlossen, wie [SpacesDatabase.shared].
         */
        fun shared(context: Context): SpaceVaults =
            shared ?: synchronized(this) {
                shared
                    ?: context.applicationContext
                        .let { app ->
                            SpaceVaults(
                                SpacesDatabase.shared(app),
                                SpacesDatabase.spacesDirectory(app),
                                AndroidPreviewRenderer(),
                                PreviewWorkers(),
                            )
                        }.also { shared = it }
            }

        /**
         * Eigene Instanz auf einer gegebenen Datenbank, für Tests. Die Anwendung nutzt [shared]. Zwei Instanzen je Prozess
         * sind nicht vorgesehen, weil jede eigene Sperren hätte.
         */
        fun create(
            database: SpacesDatabase,
            spacesDir: File,
            renderer: PreviewRenderer = AndroidPreviewRenderer(),
        ): SpaceVaults = SpaceVaults(database, spacesDir, renderer, PreviewWorkers())
    }
}
