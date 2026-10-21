package io.github.labushuya.emberlight.feature.space.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.share.server.ActiveSpaces
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.ChunkSource
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Upload aus der App über den Inhaltsresolver (Paket 3.5c). Der Stream wird hier geöffnet, die Vault bekommt nur die Bytes.
 * Uploader ist der Gastgeber. Die Vault ist dieselbe wie die des Servers (ADR 0019), damit Dedupe und Sperre gelten.
 */
@Singleton
class ContentHostUploadPort
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val vaults: SpaceVaults,
        private val cipher: InviteSecretCipher,
        private val runs: SpaceRunRepository,
        private val clock: RunClock,
    ) : HostUploadPort {
        override suspend fun canUpload(spaceId: SpaceId): Boolean =
            HostUploadRules.canUpload(
                record = runs.findActive(),
                spaceId = spaceId,
                serverRunning = SpaceServerService.runState.value is RunState.Running,
                nowMillis = clock.wallMillis(),
            )

        override suspend fun describe(uri: String): PickedFile =
            withContext(Dispatchers.IO) {
                val fallback = PickedFile(name = FALLBACK_NAME, sizeBytes = null)
                val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext fallback
                runCatching {
                    context.contentResolver
                        .query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use fallback
                            val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
                            val size = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0L }
                            PickedFile(name, size)
                        }
                }.getOrNull() ?: fallback
            }

        override suspend fun upload(
            spaceId: SpaceId,
            uri: String,
            keepLocation: Boolean,
            onProgress: (UploadProgress) -> Unit,
        ): PickedUploadOutcome =
            withContext(Dispatchers.IO) {
                val parsed =
                    runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext PickedUploadOutcome.Unreadable
                val file = describe(uri)
                val input = openOrNull(parsed) ?: return@withContext PickedUploadOutcome.Unreadable
                input.use { stream ->
                    val vault = vaults.vaultOf(spaceId, cipher) ?: return@withContext PickedUploadOutcome.Unreadable
                    val host =
                        vault.participants().firstOrNull { it.role == Role.HOST }
                            ?: return@withContext PickedUploadOutcome.Unreadable
                    val source =
                        InputStreamChunkSource(stream) { read ->
                            onProgress(UploadProgress(read, file.sizeBytes))
                        }
                    // Ein Lesefehler mitten im Strom (etwa entzogener Zugriff) ist kein Absturz, sondern eine nicht lesbare Datei.
                    val result =
                        try {
                            vault.ingest(
                                Principal(host.id, Role.HOST),
                                file.name,
                                file.sizeBytes,
                                source,
                                keepLocation,
                            )
                        } catch (unreadable: IOException) {
                            return@withContext PickedUploadOutcome.Unreadable
                        }
                    // Der HTTP-Upload erhöht die Revision, damit die Gäste-Seite neu lädt. Der Upload aus der App ebenso.
                    if (result is IngestResult.Created || result is IngestResult.Duplicate) {
                        ActiveSpaces.current()?.takeIf { it.spaceId == spaceId }?.bumpRevision()
                    }
                    PickedUploadOutcome.Ingested(result)
                }
            }

        /** Öffnet den Stream. Ein fehlendes Recht oder eine entfernte Datei ergibt `null`, nie eine Ausnahme nach außen. */
        private fun openOrNull(uri: Uri): InputStream? =
            try {
                context.contentResolver.openInputStream(uri)
            } catch (unreadable: IOException) {
                null
            } catch (denied: SecurityException) {
                null
            }

        private companion object {
            const val FALLBACK_NAME = "medium"
        }
    }

/**
 * Quelle über einen [InputStream]. Meldet nach jedem Lesen die Gesamtzahl der Bytes. Läuft auf einem Faden für I/O, denn das
 * Lesen blockiert.
 */
internal class InputStreamChunkSource(
    private val input: InputStream,
    private val onRead: (Long) -> Unit,
) : ChunkSource {
    private var total = 0L

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = input.read(buffer, offset, length)
        if (count > 0) {
            total += count
            onRead(total)
        }
        return count
    }
}
