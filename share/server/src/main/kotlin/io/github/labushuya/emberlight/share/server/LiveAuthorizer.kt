package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.participants.Credential
import java.util.concurrent.atomic.AtomicReference

/**
 * Zugriffsprüfung mit den aktiven Teilnehmern des Space (ADR 0018). Die Liste liegt als Snapshot in einem
 * [AtomicReference]. Anfragen lesen ihn ohne Sperre. Jede Änderung baut den Snapshot neu. Ein widerrufener Token
 * ist danach sofort ungültig, ohne Warten auf die Datenbank.
 */
class LiveAuthorizer(
    initial: List<Credential>,
) : RequestAuthorizer {
    private val lock = Any()
    private var current: List<Credential> = initial
    private val snapshot = AtomicReference(build(initial))

    override fun authorize(authorizationHeader: String?): Principal? = snapshot.get().tokens.authorize(authorizationHeader)

    /** Aktiver Teilnehmer mit dieser ID, `null` wenn er entfernt oder abgemeldet ist (ADR 0019, signierte URLs). */
    fun principalOf(id: ParticipantId): Principal? = snapshot.get().principals[id]

    /** Ersetzt alle Zugangsschlüssel, etwa nach einer Änderung in der Datenbank. */
    fun replace(credentials: List<Credential>) {
        synchronized(lock) {
            current = credentials
            snapshot.set(build(current))
        }
    }

    /** Entfernt einen Teilnehmer sofort aus der Zugriffsprüfung. */
    fun revoke(id: ParticipantId) {
        synchronized(lock) {
            current = current.filterNot { it.participantId == id }
            snapshot.set(build(current))
        }
    }

    private fun build(credentials: List<Credential>): Snapshot =
        Snapshot(
            tokens =
                TokenAuthorizer(
                    credentials.associate { credential ->
                        hexToBytes(credential.tokenHash) to Principal(credential.participantId, credential.role)
                    },
                ),
            principals = credentials.associate { it.participantId to Principal(it.participantId, it.role) },
        )

    /** Ein Stand der Zugangsschlüssel. Tokenprüfung und Teilnehmer kommen aus demselben Stand. */
    private class Snapshot(
        val tokens: TokenAuthorizer,
        val principals: Map<ParticipantId, Principal>,
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
