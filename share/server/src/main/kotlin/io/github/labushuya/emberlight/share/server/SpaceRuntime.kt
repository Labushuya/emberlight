package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.content.Context
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
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
 * Einrichtung (ADR 0014, ein aktiver Space pro Serverlauf).
 *
 * Ohne Teilnehmer lehnt der Server jede Anfrage ab ([RequestAuthorizer.DenyAll]). So ist der Stand bis Paket 3.4: Der
 * Gastgeber bedient den Space nur über die App, Gäste kommen nicht hinein (ADR 0017).
 *
 * [startedAtMillis], [expiresAtMillis] und [persisted] gehören zum Betrieb (Paket 3.3). Der Debug-Einstieg setzt sie nicht.
 */
data class ServerSetup(
    val spaceId: SpaceId,
    val spaceName: String,
    val participants: List<ParticipantSeed> = emptyList(),
    val limits: SpaceLimits = SpaceLimits(),
    val debugLogging: Boolean = false,
    /** Start in UTC-Millisekunden, `null` ohne festen Lauf. */
    val startedAtMillis: Long? = null,
    /** Geplantes Ende in UTC-Millisekunden, `null` ohne Ende. */
    val expiresAtMillis: Long? = null,
    /** `true`, wenn der Space in `spaces.db` steht und Start, Ende und Pausen dort festgehalten werden. */
    val persisted: Boolean = false,
) {
    internal fun authorizer(): RequestAuthorizer =
        if (participants.isEmpty()) {
            RequestAuthorizer.DenyAll
        } else {
            TokenAuthorizer(
                participants.associate { seed -> hexToBytes(seed.tokenSha256Hex) to seed.principal },
            )
        }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/**
 * Laufzeit eines Serverlaufs: Space-Vault (Speicher und Index) und Zugriffsprüfung. Der Index ist die prozessweit geteilte
 * Datenbank ([SpacesDatabase.shared]). Sie gehört nicht dem Serverlauf und wird hier nicht geschlossen (Paket 3.3).
 */
internal class SpaceRuntime private constructor(
    val spaceVault: SpaceVault,
    val authorizer: RequestAuthorizer,
) {
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
            val spacesDir = SpacesDatabase.spacesDirectory(context)
            val probe = StorageProbe { File(spacesDir, setup.spaceId.value).usableSpace }
            val vault =
                SpaceVault.open(SpacesDatabase.shared(context), spacesDir, setup.spaceId, setup.limits, probe)
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
        }
    }
}
