package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.content.Context
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.StorageProbe
import java.io.File

/** Ein Teilnehmer des Space mit dem Hash seines Zugangsschlüssels (ADR 0013). Der Schlüssel selbst bleibt außen. */
data class ParticipantSeed(
    val principal: Principal,
    val displayName: String,
    /** SHA-256 des Tokens als 64 Hex-Zeichen in Kleinbuchstaben. */
    val tokenSha256Hex: String,
) {
    init {
        require(HEX_SHA256.matches(tokenSha256Hex)) { "Token-Hash muss SHA-256 in Hex sein." }
    }

    private companion object {
        val HEX_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Einrichtung eines Serverlaufs: welcher Space, welche Grenzen, welche Teilnehmer. Der Dienst startet nur mit einer
 * Einrichtung (ADR 0014, ein aktiver Space pro Serverlauf). Die Oberfläche kommt in Paket 3.2 bzw. 3.3.
 */
data class ServerSetup(
    val spaceId: SpaceId,
    val spaceName: String,
    val participants: List<ParticipantSeed>,
    val limits: SpaceLimits = SpaceLimits(),
    val debugLogging: Boolean = false,
) {
    init {
        require(participants.isNotEmpty()) { "Ein Space braucht mindestens einen Teilnehmer." }
    }

    internal fun authorizer(): RequestAuthorizer =
        TokenAuthorizer(
            participants.associate { seed -> hexToBytes(seed.tokenSha256Hex) to seed.principal },
        )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/**
 * Offene Laufzeit eines Serverlaufs: Space-Vault (Index und Speicher) und Zugriffsprüfung. Wird beim Start geöffnet,
 * beim Stopp geschlossen. Der Index liegt unter `noBackupFilesDir/spaces/` (ADR 0014).
 */
internal class SpaceRuntime private constructor(
    val spaceVault: SpaceVault,
    val authorizer: RequestAuthorizer,
) : AutoCloseable {
    override fun close() {
        spaceVault.close()
    }

    companion object {
        /**
         * Öffnet den Space, registriert die Teilnehmer und räumt Reste eines Absturzes auf.
         *
         * Der Mindest-Restspeicher nutzt [File.getUsableSpace]. Das zählt nur freien Speicher ohne löschbare Caches. Das
         * ist der konservative Wert: Er unterschätzt den Platz eher, als ihn zu überschätzen.
         */
        @SuppressLint("UsableSpace")
        suspend fun open(
            context: Context,
            setup: ServerSetup,
        ): SpaceRuntime {
            val spacesDir = File(context.noBackupFilesDir, SPACES_DIRECTORY)
            val probe = StorageProbe { File(spacesDir, setup.spaceId.value).usableSpace }
            val vault = SpaceVault.open(context, spacesDir, setup.spaceId, setup.limits, probe)
            try {
                setup.participants.forEach { seed ->
                    vault.registerParticipant(
                        seed.principal.participantId,
                        seed.displayName,
                        seed.principal.role,
                        seed.tokenSha256Hex,
                    )
                }
                vault.recover()
                return SpaceRuntime(vault, setup.authorizer())
            } catch (failure: Throwable) {
                vault.close()
                throw failure
            }
        }

        private const val SPACES_DIRECTORY = "spaces"
    }
}
