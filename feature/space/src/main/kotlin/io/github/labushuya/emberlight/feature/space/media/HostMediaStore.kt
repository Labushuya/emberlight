package io.github.labushuya.emberlight.feature.space.media

import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.preview.PreviewLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dateien für die Medienansicht des Gastgebers (Paket 3.5c): Vorschau, HEIC-Ansicht und Original aus dem Space-Speicher. Nie
 * aus dem MediaStore, die Galerie bleibt getrennt (ADR 0014, KONZEPT Spaces). Die Dateien liefert die Vault, so wie sie die
 * Gäste-Seite über die Medienrouten bekommen.
 */
@Singleton
class HostMediaStore
    @Inject
    constructor(
        private val vaults: SpaceVaults,
        private val cipher: InviteSecretCipher,
    ) {
        /**
         * Original für den Rückfall, wenn die Vorschau fehlgeschlagen ist (Hotfix 2). Die Oberfläche verkleinert es beim
         * Laden. Der Pfad liegt im Space-Speicher, nie im MediaStore.
         */
        suspend fun originalFile(
            spaceId: SpaceId,
            id: MediaId,
        ): File? =
            withContext(Dispatchers.IO) {
                val vault = vaults.vaultOf(spaceId, cipher) ?: return@withContext null
                vault.contentOf(id)?.path?.toFile()
            }

        /** Vorschau (320 px) für die Kachel. `null`, solange sie nicht bereit ist. */
        suspend fun previewFile(
            spaceId: SpaceId,
            id: MediaId,
        ): File? =
            withContext(Dispatchers.IO) {
                val vault = vaults.vaultOf(spaceId, cipher) ?: return@withContext null
                (vault.previewOf(id) as? PreviewLookup.Ready)?.path?.toFile()
            }

        /** Datei für die Detailansicht. HEIC/HEIF zeigt die 1600-px-Ansicht, andere Medien das Original. */
        suspend fun detailFile(
            spaceId: SpaceId,
            id: MediaId,
            heic: Boolean,
        ): File? =
            withContext(Dispatchers.IO) {
                val vault = vaults.vaultOf(spaceId, cipher) ?: return@withContext null
                if (heic) {
                    (vault.viewOf(id) as? PreviewLookup.Ready)?.path?.toFile()
                } else {
                    vault.contentOf(id)?.path?.toFile()
                }
            }
    }
