package io.github.labushuya.emberlight.share.space.participants

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.ParticipantEntity
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase

/** Ergebnis eines Beitritts (ADR 0018). Der Token erscheint nur hier, danach nur noch als Hash. */
sealed interface JoinOutcome {
    data class Joined(
        val participantId: ParticipantId,
        val displayName: String,
        val role: Role,
        val token: String,
    ) : JoinOutcome

    /** Einladung falsch, veraltet oder nicht vorhanden. Zählt als Fehlversuch für die Drosselung. */
    data object InviteInvalid : JoinOutcome

    data class NameInvalid(
        val reason: ParticipantName.Reason,
    ) : JoinOutcome

    /** Name ist vergeben. [suggestion] ist ein freier Vorschlag, z. B. „Anna 2“. */
    data class NameTaken(
        val suggestion: String,
    ) : JoinOutcome

    /** Die Obergrenze aktiver Teilnehmer ist erreicht, der Gastgeber mitgezählt. */
    data object SpaceFull : JoinOutcome
}

/** Ergebnis des Entfernens (ADR 0018). */
sealed interface RemoveOutcome {
    data object Removed : RemoveOutcome

    /** Unbekannt oder schon entfernt. */
    data object NotFound : RemoveOutcome

    /** Der Gastgeber kann sich nicht selbst entfernen. */
    data object HostCannotBeRemoved : RemoveOutcome
}

/** Teilnehmer für die Anzeige. Ohne Token und ohne Hash. */
data class ParticipantInfo(
    val id: ParticipantId,
    val displayName: String,
    val role: Role,
    val joinedAtMillis: Long,
)

/**
 * Teilnehmer und Einladung eines Space (ADR 0018). Die Datenbank ist der Maßstab. Schreibvorgänge laufen über den
 * Commit-Lock der Vault, daher ist die Reihenfolge der Änderungen festgelegt. Ohne Dateizugriff: Medien entfernt die Vault.
 */
internal class ParticipantRoster(
    private val database: SpacesDatabase,
    private val spaceId: SpaceId,
    private val nowMillis: () -> Long,
) {
    private val dao = database.participants()

    /** Der Gastgeber des Space, falls schon angelegt. */
    suspend fun host(): ParticipantEntity? = dao.findHost(spaceId.value)

    /**
     * Legt den Gastgeber an oder aktualisiert ihn. Der Token wird bei jedem Start neu gewählt (ADR 0018), nur sein Hash
     * landet in der Datenbank. Die Kennung bleibt gleich.
     */
    suspend fun ensureHost(tokenHash: String): ParticipantId =
        database.withTransaction {
            val existing = dao.findHost(spaceId.value)
            if (existing == null) {
                val id = ParticipantId.generate()
                dao.insert(
                    ParticipantEntity(
                        id = id.value,
                        spaceId = spaceId.value,
                        displayName = HOST_DISPLAY_NAME,
                        nameKey = HOST_NAME_KEY,
                        role = Role.HOST.name,
                        tokenHash = tokenHash,
                        createdAt = nowMillis(),
                    ),
                )
                id
            } else {
                dao.upsert(existing.copy(tokenHash = tokenHash))
                ParticipantId(existing.id)
            }
        }

    /**
     * Einladung bereitstellen. Gehört [known] zum gespeicherten Hash, bleibt sie. Sonst wird eine neue erzeugt und der
     * Hash ersetzt die alte Einladung (alte QR-Codes sind dann ungültig).
     */
    suspend fun ensureInvite(known: String?): String {
        val stored = database.spaces().find(spaceId.value)?.inviteHash
        if (known != null && stored != null && Secrets.hashesEqual(Secrets.hashHex(known), stored)) return known
        val fresh = Secrets.generate()
        val now = nowMillis()
        database.spaces().setInvite(
            id = spaceId.value,
            hash = Secrets.hashHex(fresh),
            createdAt = now,
            rotatedAt = if (stored == null) null else now,
        )
        return fresh
    }

    /** Erneuert die Einladung. Beigetretene Teilnehmer bleiben. */
    suspend fun rotateInvite(): String {
        val fresh = Secrets.generate()
        val now = nowMillis()
        val current = database.spaces().find(spaceId.value)
        database.spaces().setInvite(
            id = spaceId.value,
            hash = Secrets.hashHex(fresh),
            createdAt = current?.inviteCreatedAt ?: now,
            rotatedAt = now,
        )
        return fresh
    }

    suspend fun join(
        inviteSecret: String,
        rawName: String,
    ): JoinOutcome {
        val space = database.spaces().find(spaceId.value)
        val stored = space?.inviteHash
        val inviteOk =
            Secrets.isWellFormed(inviteSecret) &&
                stored != null &&
                Secrets.hashesEqual(Secrets.hashHex(inviteSecret), stored)
        if (!inviteOk) return JoinOutcome.InviteInvalid
        val name =
            when (val result = ParticipantName.normalize(rawName)) {
                is ParticipantName.Result.Invalid -> return JoinOutcome.NameInvalid(result.reason)
                is ParticipantName.Result.Valid -> result
            }
        return database.withTransaction {
            if (dao.countActive(spaceId.value) >= MAX_PARTICIPANTS) {
                JoinOutcome.SpaceFull
            } else if (dao.findByNameKey(spaceId.value, name.key) != null) {
                val taken = dao.activeOf(spaceId.value).map { it.nameKey }.toSet()
                JoinOutcome.NameTaken(
                    ParticipantName.suggestAlternative(name.display) { key -> key in taken },
                )
            } else {
                val id = ParticipantId.generate()
                val token = Secrets.generate()
                dao.insert(
                    ParticipantEntity(
                        id = id.value,
                        spaceId = spaceId.value,
                        displayName = name.display,
                        nameKey = name.key,
                        role = Role.GUEST.name,
                        tokenHash = Secrets.hashHex(token),
                        createdAt = nowMillis(),
                    ),
                )
                JoinOutcome.Joined(id, name.display, Role.GUEST, token)
            }
        }
    }

    /** Zieht einen Teilnehmer zurück. Der Name wird frei, der Eintrag bleibt für die Anzeige seiner Uploads. */
    suspend fun revoke(id: ParticipantId): RemoveOutcome {
        val row =
            dao.findById(id.value)?.takeIf { it.spaceId == spaceId.value && it.revokedAt == null }
                ?: return RemoveOutcome.NotFound
        if (row.role == Role.HOST.name) return RemoveOutcome.HostCannotBeRemoved
        dao.revoke(id.value, nowMillis(), REVOKED_PREFIX + id.value)
        return RemoveOutcome.Removed
    }

    /** Aktive Zugangsschlüssel als Hash und Principal, für die Zugriffsprüfung. Entfernte fehlen. */
    suspend fun activeCredentials(): List<Credential> =
        dao.activeOf(spaceId.value).map { row ->
            Credential(row.tokenHash, ParticipantId(row.id), Role.valueOf(row.role))
        }

    suspend fun active(): List<ParticipantInfo> =
        dao.activeOf(spaceId.value).map {
            ParticipantInfo(ParticipantId(it.id), it.displayName, Role.valueOf(it.role), it.createdAt)
        }

    suspend fun find(id: ParticipantId): ParticipantInfo? =
        dao.findById(id.value)?.takeIf { it.spaceId == spaceId.value && it.revokedAt == null }?.let {
            ParticipantInfo(ParticipantId(it.id), it.displayName, Role.valueOf(it.role), it.createdAt)
        }

    companion object {
        /** Obergrenze aktiver Teilnehmer inklusive Gastgeber (ADR 0018, Grenzen). */
        const val MAX_PARTICIPANTS = 50

        /** Anzeige des Gastgebers in der Datenbank. Oberflächen übersetzen über die Rolle. */
        const val HOST_DISPLAY_NAME = "Gastgeber"
        private const val HOST_NAME_KEY = "#host"
        private const val REVOKED_PREFIX = ParticipantName.REVOKED_KEY_PREFIX
    }
}

/** Zugangsschlüssel eines aktiven Teilnehmers: Hash des Tokens, Kennung und Rolle. Ohne Klartext. */
data class Credential(
    val tokenHash: String,
    val participantId: ParticipantId,
    val role: Role,
)
