package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.management.SpaceAccentPalette
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.RemoveOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    /** `true`, wenn die Einladung nach einem Neustart neu entstand, weil das Geheimnis nicht lesbar war (ADR 0018). */
    val inviteRenewed: Boolean = false,
    /** Akzentfarbe des Space als Hex-Wert aus der festen Palette (Paket 3.4, ADR 0018). */
    val accentColor: String = SpaceAccentPalette.hexAt(0),
    /** Token des Gastgebers, nur im Speicher. Die Datenbank hat den Hash (ADR 0018). */
    val hostToken: String?,
    /** Schlüssel für signierte Medien-URLs, nur im Speicher je Serverlauf (ADR 0019). */
    val mediaSigner: MediaUrlSigner = MediaUrlSigner.random(),
    /** Geplantes Ende in UTC-Millisekunden, `null` ohne Ende (ADR 0017). Für `/space` (ADR 0019). */
    val endsAtMillis: Long? = null,
    /** Grenzen für Dateigrößen und Speicher. Die Gäste-Seite prüft vorab (ADR 0019). */
    val limits: SpaceLimits = SpaceLimits(),
    /** Uhr in Millisekunden seit der Epoche. Tests ersetzen sie. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Prüfung signierter Medien-URLs. Entfernte Teilnehmer sind sofort ungültig (ADR 0019). */
    val mediaAccess: SignedMediaAuthorizer = SignedMediaAuthorizer(mediaSigner, live::principalOf)

    private val revisionCounter = AtomicLong(0)

    /**
     * Zähler für Änderungen an Medien, Teilnehmern und Space (ADR 0019). Die Gäste-Seite lädt die Liste nur neu, wenn er
     * sich ändert. Er steigt bei Uploads, Löschungen, Teilnehmer- und Einladungsänderungen.
     */
    val revision: Long
        get() = revisionCounter.get()

    /** Erhöht den Zähler der Revision. */
    fun bumpRevision() {
        revisionCounter.incrementAndGet()
    }

    /** Restzeit in Sekunden bis zum Ende, ab 0. `null` ohne festes Ende. */
    fun remainingSeconds(): Long? = endsAtMillis?.let { ((it - clock()) / 1_000).coerceAtLeast(0) }

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
        bumpRevision()
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
        if (outcome is JoinOutcome.Joined) {
            live.replace(vault.credentials())
            bumpRevision()
        }
        return outcome
    }

    /** Prüft ein Einladungsgeheimnis für die Vorschau. Ändert nichts. */
    suspend fun invitePreview(inviteSecret: String): Boolean = vault.inviteMatches(inviteSecret)

    /** Erneuert die Einladung. Der alte QR-Code ist sofort ungültig, Beigetretene bleiben. */
    suspend fun rotateInvite(): String {
        val fresh = vault.rotateInvite()
        inviteSecret = fresh
        bumpRevision()
        return fresh
    }

    /** Gleicht die Zugriffsprüfung mit der Datenbank ab, etwa nach einem Neustart der Sitzung. */
    suspend fun refreshAccess() {
        live.replace(vault.credentials())
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
