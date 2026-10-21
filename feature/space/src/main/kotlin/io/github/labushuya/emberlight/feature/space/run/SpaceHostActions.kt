package io.github.labushuya.emberlight.feature.space.run

import io.github.labushuya.emberlight.share.server.ActiveSpaces
import io.github.labushuya.emberlight.share.space.DeleteOutcome
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.ParticipantInfo
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gastgeber-Aktionen auf die Teilnehmer und die Einladung (ADR 0018). Läuft ein Server für diesen Space, geht jede Aktion
 * über dessen Sitzung, damit Zugriff und Uploads sofort folgen. Ohne laufenden Server (Pause) nimmt die Aktion die Vault
 * aus [SpaceVaults]. Das ist dieselbe Instanz wie die des Servers (Paket 3.5c), daher gibt es keinen zweiten Commit-Lock.
 */
@Singleton
class SpaceHostActions
    @Inject
    constructor(
        private val vaults: SpaceVaults,
        private val inviteCipher: InviteSecretCipher,
    ) {
        private val changes = MutableStateFlow(0)

        /** Zählt jede Änderung an Teilnehmern oder Einladung. Die Anzeige lädt dann neu. */
        fun changes(): StateFlow<Int> = changes.asStateFlow()

        /** Einladungsgeheimnis des laufenden Space, `null` wenn für diesen Space kein Server läuft. */
        fun currentInvite(spaceId: SpaceId): String? = runningFor(spaceId)?.inviteSecret

        suspend fun participants(spaceId: SpaceId): List<ParticipantInfo> = vaultFor(spaceId)?.participants().orEmpty()

        suspend fun removeGuest(
            spaceId: SpaceId,
            participant: ParticipantId,
            deleteUploads: Boolean,
        ): RemoveOutcome {
            val running = runningFor(spaceId)
            val outcome =
                running?.removeGuest(participant, deleteUploads)
                    ?: vaultFor(spaceId)?.removeParticipant(participant, deleteUploads)
                    ?: RemoveOutcome.NotFound
            // Nur eine tatsächliche Entfernung ändert den Bestand. Abgelehnte Versuche laden die Anzeige nicht neu.
            if (outcome == RemoveOutcome.Removed) changes.value += 1
            return outcome
        }

        /** Erneuert die Einladung. Alte QR-Codes sind danach ungültig, Beigetretene bleiben. */
        suspend fun rotateInvite(spaceId: SpaceId): String {
            val fresh =
                runningFor(spaceId)?.rotateInvite()
                    ?: checkNotNull(vaultFor(spaceId)) { "Der Space $spaceId ist nicht gespeichert." }.rotateInvite()
            changes.value += 1
            return fresh
        }

        /**
         * Entfernt ein Medium als Gastgeber. Der Gastgeber darf alles (ADR 0014, Löschen). Läuft ein Server, geht der Weg über
         * die Sitzung, damit die Gäste-Seite die Änderung sofort sieht (Paket 3.5c).
         */
        suspend fun deleteMedia(
            spaceId: SpaceId,
            media: MediaId,
        ): DeleteOutcome {
            val vault = vaultFor(spaceId) ?: return DeleteOutcome.NotFound
            val host = vault.participants().firstOrNull { it.role == Role.HOST } ?: return DeleteOutcome.NotFound
            val principal = Principal(host.id, Role.HOST)
            return runningFor(spaceId)?.deleteMedia(principal, media) ?: vault.delete(principal, media)
        }

        private fun runningFor(spaceId: SpaceId) = ActiveSpaces.current()?.takeIf { it.spaceId == spaceId }

        /** Vault des Space, auch ohne laufenden Server. `null`, wenn der Space nicht gespeichert ist. */
        private suspend fun vaultFor(spaceId: SpaceId): SpaceVault? =
            runningFor(spaceId)?.vault ?: vaults.vaultOf(spaceId, inviteCipher)
    }
