package io.github.labushuya.emberlight.feature.space.run

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.share.server.ActiveSpaces
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.AndroidKeyStoreInviteSecretCipher
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
 * über dessen Sitzung, damit Zugriff und Uploads sofort folgen. Ohne laufenden Server (Pause) öffnet die Aktion die Vault
 * selbst. Beides arbeitet auf derselben prozessweiten Datenbank.
 */
@Singleton
class SpaceHostActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: SpacesDatabase,
        private val inviteCipher: InviteSecretCipher,
    ) {
        private val changes = MutableStateFlow(0)

        /** Zählt jede Änderung an Teilnehmern oder Einladung. Die Anzeige lädt dann neu. */
        fun changes(): StateFlow<Int> = changes.asStateFlow()

        /** Einladungsgeheimnis des laufenden Space, `null` wenn für diesen Space kein Server läuft. */
        fun currentInvite(spaceId: SpaceId): String? = runningFor(spaceId)?.inviteSecret

        suspend fun participants(spaceId: SpaceId): List<ParticipantInfo> = vaultFor(spaceId).participants()

        suspend fun removeGuest(
            spaceId: SpaceId,
            participant: ParticipantId,
            deleteUploads: Boolean,
        ): RemoveOutcome {
            val running = runningFor(spaceId)
            val outcome =
                running?.removeGuest(participant, deleteUploads)
                    ?: vaultFor(spaceId).removeParticipant(participant, deleteUploads)
            // Nur eine tatsächliche Entfernung ändert den Bestand. Abgelehnte Versuche laden die Anzeige nicht neu.
            if (outcome == RemoveOutcome.Removed) changes.value += 1
            return outcome
        }

        /** Erneuert die Einladung. Alte QR-Codes sind danach ungültig, Beigetretene bleiben. */
        suspend fun rotateInvite(spaceId: SpaceId): String {
            val fresh = runningFor(spaceId)?.rotateInvite() ?: vaultFor(spaceId).rotateInvite()
            changes.value += 1
            return fresh
        }

        private fun runningFor(spaceId: SpaceId) = ActiveSpaces.current()?.takeIf { it.spaceId == spaceId }

        /**
         * Vault ohne laufenden Server. Die Grenzen und der Speicher spielen für Teilnehmer und Einladung keine Rolle, deshalb
         * stehen hier neutrale Werte.
         */
        private fun vaultFor(spaceId: SpaceId): SpaceVault =
            runningFor(spaceId)?.vault
                ?: SpaceVault.open(
                    database,
                    SpacesDatabase.spacesDirectory(context),
                    spaceId,
                    SpaceLimits(),
                    StorageProbe { 0L },
                    inviteCipher,
                )
    }
