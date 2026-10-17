package io.github.labushuya.emberlight.feature.gallery.viewer

/**
 * Phase des Vollbild-Overlays über dem Raster (ADR 0010). Reine Zustandsmaschine, ohne Animation und ohne Android. Die
 * Animationen laufen im Overlay und melden ihr Ende als Ereignis.
 *
 * - [CLOSED]: Nichts zu sehen, das Raster ist frei.
 * - [OPENING]: Die Hero-Animation läuft von der Kachel zum Vollbild.
 * - [OPEN]: Der Pager ist sichtbar, das Raster darunter nicht gezeichnet.
 * - [PEEKING]: Kurzer Blick. Die Rückgeste hält ohne Fortschritt (Tastenhalten). Das Bild steht auf der Blick-Stufe, die
 *   Kachel ist hervorgehoben (ADR 0010, Kurzer Blick).
 * - [DRAGGING]: Der Nutzer zieht nach unten (Wegwischen).
 * - [BACK]: Die System-Rückgeste zeigt ihren Fortschritt.
 * - [SETTLING]: Das Bild federt in die Ausgangslage zurück (abgebrochenes Ziehen oder abgebrochene Rückgeste).
 * - [CLOSING]: Die Hero-Animation läuft von der aktuellen Position zurück zur Kachel.
 */
internal enum class OverlayPhase {
    CLOSED,
    OPENING,
    OPEN,
    PEEKING,
    DRAGGING,
    BACK,
    SETTLING,
    CLOSING,
    ;

    /** Ist der Viewer zu sehen (also nicht [CLOSED])? */
    val visible: Boolean get() = this != CLOSED

    /** Zeigt der Pager das Bild (und nicht die Hero-Animation)? */
    val pagerShown: Boolean
        get() = this == OPEN || this == PEEKING || this == DRAGGING || this == BACK || this == SETTLING

    /** Läuft die Hero-Animation und zeichnet das Bild selbst? */
    val heroShown: Boolean get() = this == OPENING || this == CLOSING
}

/** Ereignisse, die die Phase ändern. Die Oberfläche und die Animationen melden sie. */
internal sealed interface OverlayEvent {
    /** Das Öffnen wurde ausgelöst (Antippen einer Kachel oder Wiederherstellen nach Prozess-Tod). */
    data object Open : OverlayEvent

    /** Die Hero-Animation des Öffnens ist fertig. */
    data object OpenFinished : OverlayEvent

    /** Die System-Rückgeste hat begonnen. Ohne Fortschritt tritt der Kurze Blick ein (siehe [OverlayPhase.PEEKING]). */
    data object BackStarted : OverlayEvent

    /** Der Nutzer beginnt zu ziehen. */
    data object DragStarted : OverlayEvent

    /** Der Nutzer lässt los. [dismiss] `true`: über der Schwelle oder schnell genug, also schließen. */
    data class DragEnded(
        val dismiss: Boolean,
    ) : OverlayEvent

    /** Die System-Rückgeste meldet einen Fortschritt (auch während des Ziehens und des Federns). */
    data object BackProgressed : OverlayEvent

    /** Die System-Rückgeste wurde abgebrochen. */
    data object BackCancelled : OverlayEvent

    /** Die System-Rückgeste wurde vollendet. */
    data object BackCommitted : OverlayEvent

    /** Schließen ausgelöst (Zurück-Taste, Schließen-Knopf, Ziehen über der Schwelle). */
    data object CloseRequested : OverlayEvent

    /** Das Federn in die Ausgangslage ist fertig. */
    data object SettleFinished : OverlayEvent

    /** Die Hero-Animation des Schließens ist fertig. */
    data object CloseFinished : OverlayEvent
}

/**
 * Nächste Phase nach [event]. Ereignisse, die in der aktuellen Phase keinen Sinn haben, lassen die Phase unverändert. So
 * bleibt die Maschine auch bei verspäteten Meldungen einer Animation konsistent.
 */
internal fun nextPhase(
    phase: OverlayPhase,
    event: OverlayEvent,
): OverlayPhase =
    when (event) {
        OverlayEvent.Open -> {
            if (phase == OverlayPhase.CLOSED) OverlayPhase.OPENING else phase
        }

        OverlayEvent.OpenFinished -> {
            if (phase == OverlayPhase.OPENING) OverlayPhase.OPEN else phase
        }

        OverlayEvent.BackStarted -> {
            if (phase == OverlayPhase.OPEN) OverlayPhase.PEEKING else phase
        }

        OverlayEvent.DragStarted -> {
            if (phase == OverlayPhase.OPEN ||
                phase == OverlayPhase.SETTLING
            ) {
                OverlayPhase.DRAGGING
            } else {
                phase
            }
        }

        is OverlayEvent.DragEnded -> {
            when {
                phase != OverlayPhase.DRAGGING -> phase
                event.dismiss -> OverlayPhase.CLOSING
                else -> OverlayPhase.SETTLING
            }
        }

        OverlayEvent.BackProgressed -> {
            when (phase) {
                OverlayPhase.OPEN, OverlayPhase.PEEKING, OverlayPhase.BACK -> OverlayPhase.BACK
                else -> phase
            }
        }

        OverlayEvent.BackCancelled -> {
            when (phase) {
                OverlayPhase.BACK, OverlayPhase.PEEKING -> OverlayPhase.SETTLING
                else -> phase
            }
        }

        OverlayEvent.BackCommitted -> {
            when (phase) {
                OverlayPhase.BACK, OverlayPhase.PEEKING -> OverlayPhase.CLOSING
                else -> phase
            }
        }

        OverlayEvent.CloseRequested -> {
            when (phase) {
                OverlayPhase.CLOSED, OverlayPhase.CLOSING -> phase
                else -> OverlayPhase.CLOSING
            }
        }

        OverlayEvent.SettleFinished -> {
            if (phase == OverlayPhase.SETTLING) OverlayPhase.OPEN else phase
        }

        OverlayEvent.CloseFinished -> {
            if (phase == OverlayPhase.CLOSING) OverlayPhase.CLOSED else phase
        }
    }
