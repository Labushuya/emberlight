package io.github.labushuya.emberlight.share.space

/** Rolle eines Teilnehmers. Der Gastgeber darf alle Medien löschen, Gäste nur eigene (ADR 0014, Löschen). */
enum class Role {
    HOST,
    GUEST,
}

/** Der authentifizierte Teilnehmer einer Anfrage. Liefert die Zugriffsprüfung (ADR 0013, Authentifizierung). */
data class Principal(
    val participantId: ParticipantId,
    val role: Role,
)
