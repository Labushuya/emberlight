package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Sitzung eines laufenden Space: Vault, Zugriffsprüfung, laufende Uploads und die Einladung im Speicher (ADR 0018).
 * Routen und die Oberfläche des Gastgebers arbeiten über diese Klasse, damit Änderungen an Teilnehmern sofort wirken.
 */
class SpaceAccess internal constructor(
    val vault: SpaceVault,
    val spaceId: SpaceId,
    val spaceName: String,
    val live: LiveAuthorizer,
    val uploads: ActiveUploads,
    invite: String,
    /** Token des Gastgebers, nur im Speicher. Die Datenbank hat den Hash (ADR 0018). */
    val hostToken: String?,
) {
    /** Einladungsgeheimnis für den QR-Code. Nur im Speicher, in der Datenbank steht der Hash. */
    @Volatile
    var inviteSecret: String = invite
        private set

    /**
     * Entfernt einen Gast. Der Token ist sofort ungültig, laufende Uploads brechen ab. Danach spiegelt die Prüfung die
     * Datenbank. Der Gastgeber ist nicht entfernbar.
     */
    suspend fun removeGuest(
        id: ParticipantId,
        deleteUploads: Boolean,
    ): RemoveOutcome {
        val target = vault.participant(id) ?: return RemoveOutcome.NotFound
        if (target.role == Role.HOST) return RemoveOutcome.HostCannotBeRemoved
        live.revoke(id)
        uploads.cancelOf(id)
        val outcome = vault.removeParticipant(id, deleteUploads)
        live.replace(vault.credentials())
        return outcome
    }

    /**
     * Beitritt über die Einladung. Nach einem Erfolg kennt die Zugriffsprüfung den neuen Token sofort (ADR 0018).
     */
    suspend fun join(
        inviteSecret: String,
        rawName: String,
    ): JoinOutcome {
        val outcome = vault.join(inviteSecret, rawName)
        if (outcome is JoinOutcome.Joined) live.replace(vault.credentials())
        return outcome
    }

    /** Erneuert die Einladung. Der alte QR-Code ist sofort ungültig, Beigetretene bleiben. */
    suspend fun rotateInvite(): String {
        val fresh = vault.rotateInvite()
        inviteSecret = fresh
        InviteMemory.remember(spaceId, fresh)
        return fresh
    }

    /** Gleicht die Zugriffsprüfung mit der Datenbank ab, etwa nach einem Neustart der Sitzung. */
    suspend fun refreshAccess() {
        live.replace(vault.credentials())
    }
}

/**
 * Einladungen je Space im Prozess. Eine Pause oder Wiederaufnahme im selben Prozess behält den QR-Code (ADR 0013,
 * Netz). Nach einem Prozess-Neustart fehlt das Geheimnis, dann entsteht eine neue Einladung.
 */
internal object InviteMemory {
    private val secrets = ConcurrentHashMap<String, String>()

    fun known(spaceId: SpaceId): String? = secrets[spaceId.value]

    fun remember(
        spaceId: SpaceId,
        secret: String,
    ) {
        secrets[spaceId.value] = secret
    }
}

/**
 * Der laufende Space dieses Prozesses. Die Oberfläche des Gastgebers spricht über ihn mit dem Server (ADR 0018). Der
 * Server setzt ihn beim Öffnen und löscht ihn beim Schließen.
 */
object ActiveSpaces {
    private val current = AtomicReference<SpaceAccess?>(null)

    fun current(): SpaceAccess? = current.get()

    internal fun publish(access: SpaceAccess) {
        current.set(access)
    }

    internal fun clear(spaceId: SpaceId) {
        current.updateAndGet { if (it?.spaceId == spaceId) null else it }
    }
}
