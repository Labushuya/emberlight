package io.github.labushuya.emberlight.share.server

import android.content.Context
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.management.SpaceAccentPalette
import io.github.labushuya.emberlight.share.space.participants.AndroidKeyStoreInviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.Secrets

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
    /** Farbe des Space als Index in die feste Palette (Paket 3.4, ADR 0018). */
    val colorIndex: Int = 0,
)

/**
 * Laufzeit eines Serverlaufs: die Sitzung mit Vault, Zugriffsprüfung und Einladung. Die Vault ist die prozessweit geteilte
 * ([SpaceVaults.shared]), ebenso der Index ([SpacesDatabase.shared]). Beide gehören nicht dem Serverlauf und werden hier nicht
 * geschlossen (Paket 3.3, Paket 3.5c).
 */
internal class SpaceRuntime private constructor(
    val access: SpaceAccess,
    /** Statische Gäste-Dateien aus den App-Assets. */
    val guestAssets: GuestAssets,
) {
    val spaceVault: SpaceVault get() = access.vault

    companion object {
        /**
         * Öffnet den Space, registriert die Teilnehmer und stellt Gastgeber und Einladung bereit. Die Vault kommt aus
         * [SpaceVaults.shared]: Wiederaufnahme und Vorschau-Worker laufen dort einmal pro Prozess, nicht bei jedem Start
         * (Paket 3.5c). Die Einladung bleibt über Pausen im selben Prozess gleich (ADR 0013, Netz).
         */
        suspend fun open(
            context: Context,
            setup: ServerSetup,
            inviteCipher: InviteSecretCipher = AndroidKeyStoreInviteSecretCipher(),
        ): SpaceRuntime {
            val vault = SpaceVaults.shared(context).vaultFor(setup.spaceId, setup.limits, inviteCipher)
            setup.participants.forEach { seed ->
                vault.registerParticipant(
                    seed.principal.participantId,
                    seed.displayName,
                    seed.principal.role,
                    seed.tokenSha256Hex,
                )
            }
            // Der Token des Gastgebers ist zufällig und nur im Speicher. In der Datenbank steht sein Hash (ADR 0018). Mit einem
            // Gastgeber aus dem Debug-Einstieg gilt dessen Hash. Ein neuer Token würde den Test-Token des PCs ungültig machen.
            val hostToken =
                if (setup.participants.any { it.principal.role == Role.HOST }) {
                    null
                } else {
                    Secrets.generate().also { vault.ensureHost(Secrets.hashHex(it)) }
                }
            val invite = vault.ensureInvite()
            val access =
                SpaceAccess(
                    vault = vault,
                    spaceId = setup.spaceId,
                    spaceName = setup.spaceName,
                    live = LiveAuthorizer(vault.credentials()),
                    uploads = ActiveUploads(),
                    invite = invite.secret,
                    inviteRenewed = invite.renewed,
                    accentColor = SpaceAccentPalette.hexAt(setup.colorIndex),
                    hostToken = hostToken,
                    endsAtMillis = setup.expiresAtMillis,
                    limits = setup.limits,
                )
            return SpaceRuntime(access, AssetGuestAssets(context.applicationContext.assets))
        }
    }
}
