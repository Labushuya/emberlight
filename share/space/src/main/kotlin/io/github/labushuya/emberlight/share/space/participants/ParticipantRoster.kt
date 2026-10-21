package io.github.labushuya.emberlight.share.space.participants

import androidx.room.withTransaction
import io.github.labushuya.emberlight.share.space.ParticipantEntity
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
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
 * Einladung für den QR-Code. [renewed] ist `true`, wenn eine bestehende Einladung nicht mehr wiederherstellbar war und
 * deshalb eine neue entstanden ist. Der Gastgeber muss dann den neuen QR-Code teilen (ADR 0018).
 */
data class InviteState(
    val secret: String,
    val renewed: Boolean,
)

/**
 * Teilnehmer und Einladung eines Space (ADR 0018). Die Datenbank ist der Maßstab. Schreibvorgänge laufen über den
 * Commit-Lock der Vault, daher ist die Reihenfolge der Änderungen festgelegt. Ohne Dateizugriff: Medien entfernt die Vault.
 */
class ParticipantRoster internal constructor(
    private val database: SpacesDatabase,
    private val spaceId: SpaceId,
    private val inviteCipher: InviteSecretCipher,
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
     * Einladung bereitstellen (ADR 0018). Ist ein Geheimnis gespeichert, das zum Hash passt, bleibt die Einladung und
     * derselbe QR-Code gilt auch nach einem Prozess-Neustart. Fehlt der Schlüssel oder ist das Geheimnis verändert, entsteht
     * eine neue Einladung und [InviteState.renewed] ist `true`. Ohne gespeicherte Einladung entsteht sie ohne Hinweis.
     */
    suspend fun ensureInvite(): InviteState {
        val space = database.spaces().find(spaceId.value)
        val storedHash = space?.inviteHash
        if (space == null || storedHash == null) {
            return InviteState(issueInvite(createdAt = nowMillis(), rotatedAt = null), renewed = false)
        }
        recoverSecret(space, storedHash)?.let { return InviteState(it, renewed = false) }
        val now = nowMillis()
        return InviteState(issueInvite(createdAt = now, rotatedAt = now), renewed = true)
    }

    /** Erneuert die Einladung. Alte QR-Codes sind danach ungültig, Beigetretene bleiben. */
    suspend fun rotateInvite(): String {
        val now = nowMillis()
        val createdAt = database.spaces().find(spaceId.value)?.inviteCreatedAt ?: now
        return issueInvite(createdAt = createdAt, rotatedAt = now)
    }

    /** Erzeugt ein neues Geheimnis, speichert Hash und verschlüsseltes Geheimnis und gibt den Klartext zurück. */
    private suspend fun issueInvite(
        createdAt: Long,
        rotatedAt: Long?,
    ): String {
        val fresh = Secrets.generate()
        val sealed = inviteCipher.seal(fresh)
        database.spaces().setInvite(
            id = spaceId.value,
            hash = Secrets.hashHex(fresh),
            createdAt = createdAt,
            rotatedAt = rotatedAt,
            ciphertext = sealed.ciphertext,
            iv = sealed.iv,
        )
        return fresh
    }

    /**
     * Stellt das gespeicherte Geheimnis wieder her. `null` bei fehlendem Ciphertext, unlesbarem Schlüssel oder einem
     * Geheimnis, das nicht zum gespeicherten Hash passt.
     */
    private fun recoverSecret(
        space: SpaceEntity,
        storedHash: String,
    ): String? {
        val ciphertext = space.inviteCiphertext ?: return null
        val iv = space.inviteIv ?: return null
        val secret =
            try {
                inviteCipher.open(SealedInvite(ciphertext, iv))
            } catch (_: InviteSecretUnreadableException) {
                return null
            }
        return secret.takeIf { Secrets.isWellFormed(it) && Secrets.hashesEqual(Secrets.hashHex(it), storedHash) }
    }

    /** Prüft ein Einladungsgeheimnis, ohne etwas zu ändern. Grundlage für Beitritt und Vorschau (ADR 0018). */
    suspend fun inviteMatches(inviteSecret: String): Boolean {
        val stored = database.spaces().find(spaceId.value)?.inviteHash ?: return false
        return Secrets.isWellFormed(inviteSecret) && Secrets.hashesEqual(Secrets.hashHex(inviteSecret), stored)
    }

    suspend fun join(
        inviteSecret: String,
        rawName: String,
    ): JoinOutcome {
        if (!inviteMatches(inviteSecret)) return JoinOutcome.InviteInvalid
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
