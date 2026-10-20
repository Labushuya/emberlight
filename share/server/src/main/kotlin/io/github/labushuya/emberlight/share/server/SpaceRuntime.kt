package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.content.Context
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.Secrets
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
 * Teilnehmer kommen aus der Datenbank (ADR 0018). [participants] ist nur für den Debug-Einstieg gedacht und wird vor dem
 * Start dort eingetragen. Der Gastgeber bekommt beim Start einen frischen Token, Gäste treten über die Einladung bei.
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
    /** Fester Port des Space (ADR 0017, Paket 3.4). `null` vor dem ersten Start, dann wählt der Server einen freien. */
    val port: Int? = null,
)

/**
 * Laufzeit eines Serverlaufs: die Sitzung mit Vault, Zugriffsprüfung und Einladung. Der Index ist die prozessweit geteilte
 * Datenbank ([SpacesDatabase.shared]). Sie gehört nicht dem Serverlauf und wird hier nicht geschlossen (Paket 3.3).
 */
internal class SpaceRuntime private constructor(
    val access: SpaceAccess,
    /** Statische Gäste-Dateien aus den App-Assets. */
    val guestAssets: GuestAssets,
) {
    val spaceVault: SpaceVault get() = access.vault

    companion object {
        /**
         * Öffnet den Space, registriert die Teilnehmer und räumt Reste eines Absturzes auf. Danach stehen Gastgeber und
         * Einladung fest. Die Einladung bleibt über Pausen im selben Prozess gleich (ADR 0013, Netz).
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
            // Der Token des Gastgebers ist zufällig und nur im Speicher. In der Datenbank steht sein Hash (ADR 0018).
            val hostToken = Secrets.generate()
            vault.ensureHost(Secrets.hashHex(hostToken))
            val invite = vault.ensureInvite(InviteMemory.known(setup.spaceId))
            InviteMemory.remember(setup.spaceId, invite)
            val access =
                SpaceAccess(
                    vault = vault,
                    spaceId = setup.spaceId,
                    spaceName = setup.spaceName,
                    live = LiveAuthorizer(vault.credentials()),
                    uploads = ActiveUploads(),
                    invite = invite,
                    hostToken = hostToken,
                )
            return SpaceRuntime(access, AssetGuestAssets(context.applicationContext.assets))
        }
    }
}
