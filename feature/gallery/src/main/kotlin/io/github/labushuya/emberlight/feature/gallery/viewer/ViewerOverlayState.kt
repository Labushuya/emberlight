package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.labushuya.emberlight.core.design.motion.EmberlightMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState

/**
 * Zustand des Vollbild-Overlays (ADR 0010). Besitzt die Phase, den Fortschritt der Hero-Animation, den Zieh-Versatz, den Kurzen
 * Blick, den Infobereich und das gewählte Element. Alles, was die Zeichnung liest, sind Zustandswerte, die in der Zeichenphase
 * gelesen werden: Ein Frame setzt die Oberfläche nicht neu zusammen.
 *
 * [shown] trägt die ID des gezeigten Elements, `null` heißt geschlossen. Die Galerie hält es über `rememberSaveable`, damit es
 * Drehen und Falten übersteht. [cellRect] liefert die Grenzen der sichtbaren Kachel in Root-Koordinaten (ohne Beobachtung).
 * [beforeClose] bringt die Kachel vor dem Öffnen, Schließen und Blicken in den sichtbaren Bereich und liefert ihre aktuellen
 * Grenzen (`null`, wenn sie nicht sichtbar wird). Der Aufrufer setzt es bei jeder Komposition neu.
 */
@Stable
internal class ViewerOverlayState(
    private val scope: CoroutineScope,
    private val shown: MutableState<Long?>,
    private val cellRect: (Long) -> Rect?,
) {
    /** Sicherung vor Öffnen, Schließen und Blick: Kachel sichtbar machen, Grenzen nach dem Layout liefern. */
    var beforeClose: suspend (Long) -> Rect? = { id -> cellRect(id) }

    /** Wird beim Beginn des Kurzen Blicks gerufen (Haptik). Die Oberfläche setzt es bei jeder Komposition neu. */
    var onPeekStarted: () -> Unit = {}

    /** Die gezeigte Medien-ID, `null` wenn geschlossen. */
    val shownId: Long? get() = shown.value

    var phase: OverlayPhase by mutableStateOf(if (shown.value != null) OverlayPhase.OPEN else OverlayPhase.CLOSED)
        private set

    /** Fortschritt der Hero-Animation: 0 = Kachel, 1 = Vollbild. Bei der Rückgeste steuert er den Schrumpf zur Kachel. */
    var progress: Float by mutableFloatStateOf(if (shown.value != null) 1f else 0f)
        private set

    /** Zieh-Versatz nach unten in Pixeln, nie negativ. */
    var dragY: Float by mutableFloatStateOf(0f)
        private set

    /** Anteil des Kurzen Blicks, 0 ohne Blick, 1 auf der Blick-Stufe (ADR 0010, Kurzer Blick). Steuert die Deckkraft des Grundes. */
    var peek: Float by mutableFloatStateOf(0f)
        private set

    /** Hervorhebung der Kachel während des Blicks und der Rückgeste, 0 bis 1. */
    var highlight: Float by mutableFloatStateOf(0f)
        private set

    /** Infobereich offen? Die Oberfläche zeigt ihn, sobald der Wert gesetzt ist. */
    var infoOpen: Boolean by mutableStateOf(false)
        private set

    /** Öffnungsstand des Infobereichs, 0 zu bis 1 offen. Folgt dem Finger und rastet per Feder ein. */
    var infoFraction: Float by mutableFloatStateOf(0f)
        private set

    /** Stand des Infobereichs beim Beginn des laufenden Ziehens. */
    private var infoDragStartOpen = false

    /** Weg des Fingers seit Beginn des Ziehens am Infobereich, nach unten positiv. Ungeklemmt, für die Schwelle. */
    private var infoTravelPx = 0f

    /**
     * Der Infobereich ist offen oder fährt noch ein bzw. aus. Solange er sichtbar ist, gehört das Ziehen nach unten ihm und nicht
     * dem Wegwischen. Erst im Ruhezustand zu darf ein neues Ziehen wegwischen (ADR 0010, Infobereich).
     */
    val infoVisible: Boolean get() = infoOpen || infoFraction > 0f

    /** Höhe des Overlays in Pixeln und Ursprung in Root-Koordinaten. Die Oberfläche setzt sie beim Layout. */
    var heightPx: Float by mutableFloatStateOf(1f)

    /** Rechteck des Fit-Bildes in Overlay-Koordinaten. Die Oberfläche setzt es, sobald das Seitenverhältnis bekannt ist. */
    var fit: Rect by mutableStateOf(Rect.Zero)

    /** Ursprung des Overlays in Root-Koordinaten. */
    var originInRoot: Offset = Offset.Zero

    /** Ziel der Hero-Animation in Overlay-Koordinaten. `null`: ohne Kachel, die Animation blendet zur Mitte aus. */
    var heroTile: Rect? by mutableStateOf(null)
        private set

    /**
     * Deckkraft des Vorschaubildes über dem Pager. Es bleibt sichtbar, bis die Seite das Bild nachweislich zeigt (ADR 0010,
     * Feinschliff). Dann blendet es kurz aus. `0` heißt: nicht vorhanden.
     */
    var heroAlpha: Float by mutableFloatStateOf(if (shown.value != null) 0f else 1f)
        private set

    /**
     * Startrechteck des Rückwegs aus dem Zoom: das auf dem Bildschirm sichtbare Inhalts-Rechteck. `null` ohne Zoom. Solange es
     * gesetzt ist, zeichnet das Vorschaubild die Animation, der Pager bleibt unsichtbar.
     */
    var zoomFrom: Rect? by mutableStateOf(null)
        private set

    /** Die Zoomzustände der Bildseiten, damit der Rückweg aus dem Zoom das Inhalts-Rechteck liest. */
    private val zoomStates = HashMap<Long, ZoomableState>()

    /** Element, dessen Vorschaubild die Animation gezeichnet hat. Ein anderes Element verwirft das Vorschaubild. */
    private var openedId: Long? = null

    /** ID der Seite, die ihr Bild nachweislich zeigt. */
    private var pageReadyId: Long? = null

    /** Zeitpunkt des Beginns der Rückgeste (Nanosekunden, monoton). `null`, wenn keine läuft. */
    private var backStartedNanos: Long? = null

    /** Ist seit dem Beginn der Rückgeste ein Fortschritt eingetroffen? Dann folgt der Schrumpf dem Fortschritt, nicht dem Blick. */
    private var progressSeen = false

    private var job: Job? = null
    private var peekJob: Job? = null
    private var highlightJob: Job? = null
    private var infoJob: Job? = null
    private var fallbackJob: Job? = null
    private var fadeJob: Job? = null
    private var fading = false

    /** Registriert den Zoomzustand einer Bildseite. */
    fun registerZoom(
        id: Long,
        zoom: ZoomableState,
    ) {
        zoomStates[id] = zoom
    }

    fun unregisterZoom(id: Long) {
        zoomStates.remove(id)
    }

    /**
     * Öffnet das Element [id]. Die Hero-Animation startet an [tile], der Kachel des Elements. Ohne Kachel blendet sie nur ein.
     */
    fun open(
        id: Long,
        tile: Rect? = cellRect(id),
    ) {
        if (phase != OverlayPhase.CLOSED) return
        shown.value = id
        openedId = id
        pageReadyId = null
        fading = false
        heroAlpha = 1f
        heroTile = tile
        peek = 0f
        highlight = 0f
        infoOpen = false
        infoFraction = 0f
        phase = nextPhase(phase, OverlayEvent.Open)
        ViewerTrace.mark("Hero-Animation Start", id)
        job?.cancel()
        job =
            scope.launch {
                progress = 0f
                dragY = 0f
                animate(0f, 1f, animationSpec = heroSpec()) { value, _ -> progress = value }
                phase = nextPhase(phase, OverlayEvent.OpenFinished)
                ViewerTrace.mark("Hero-Animation Ende", id)
                if (pageReadyId == id) fadeHeroOut(id) else armHeroFallback(id)
            }
    }

    /**
     * Die Seite [id] zeigt ihr Bild im Fit-Rechteck, also das Vorschaubild oder das Vollbild. Erst dann darf das Vorschaubild
     * der Animation verschwinden. Ohne diese Meldung bleibt es bis zur Rückfallzeit.
     */
    fun pageShown(id: Long) {
        if (id != shown.value || pageReadyId == id) return
        pageReadyId = id
        ViewerTrace.mark("Seite Bild angezeigt", id)
        retireHeroIfReady()
    }

    /** Der Nutzer beginnt zu ziehen. Eine laufende Feder endet, das Bild bleibt an seiner Stelle. */
    fun dragStarted() {
        if (phase != OverlayPhase.OPEN && phase != OverlayPhase.SETTLING) return
        // Der Infobereich bekommt das Ziehen, solange er offen ist oder einfährt. Sonst würde der Viewer schließen (ADR 0010).
        if (infoVisible) return
        job?.cancel()
        phase = nextPhase(phase, OverlayEvent.DragStarted)
        retireHeroIfReady()
    }

    /** Folgt dem Finger um [delta] Pixel. Nach oben geht es nicht über die Ausgangslage hinaus. */
    fun dragBy(delta: Float) {
        if (phase != OverlayPhase.DRAGGING) return
        dragY = (dragY + delta).coerceAtLeast(0f)
    }

    /** Loslassen: schließen über der Schwelle oder über die Geschwindigkeit, sonst federt das Bild zurück. */
    fun dragEnded(
        velocityYPerSecond: Float,
        thresholds: GesturePx,
    ) {
        if (phase != OverlayPhase.DRAGGING) return
        val dismiss = shouldDismiss(dragY, velocityYPerSecond, thresholds)
        phase = nextPhase(phase, OverlayEvent.DragEnded(dismiss = dismiss))
        if (dismiss) {
            closeAnimated()
        } else {
            settleAnimated()
        }
    }

    /** Der Infobereich wird gezogen. Nur im Ruhezustand des Bildes, sonst ohne Wirkung. Merkt den Stand für die Entscheidung. */
    fun infoDragStarted() {
        if (phase != OverlayPhase.OPEN) return
        infoJob?.cancel()
        infoDragStartOpen = infoOpen
        infoTravelPx = 0f
    }

    /** Folgt dem Finger um [deltaY] Pixel (nach unten positiv). Der Stand ist 0 bis 1. */
    fun infoDragBy(deltaY: Float) {
        if (phase != OverlayPhase.OPEN) return
        infoTravelPx += deltaY
        infoFraction = (infoFraction - deltaY / panelHeightPx()).coerceIn(0f, 1f)
    }

    /**
     * Loslassen des Infobereichs: rastet offen oder zu, je nach Weg des Fingers seit Beginn und Geschwindigkeit (ADR 0010,
     * Infobereich, Gesten-Schwellen in dp).
     */
    fun infoDragEnded(
        velocityYPerSecond: Float,
        thresholds: GesturePx,
    ) {
        if (phase != OverlayPhase.OPEN) return
        setInfo(infoSheetOpens(infoDragStartOpen, -infoTravelPx, velocityYPerSecond, thresholds))
    }

    /** Öffnet den Infobereich, etwa über die Bedienungshilfen. Nur im Ruhezustand des Bildes. */
    fun openInfo() {
        if (phase == OverlayPhase.OPEN && !infoOpen) setInfo(true)
    }

    /** Schließt den Infobereich, falls er offen ist. */
    fun closeInfo() {
        if (infoOpen) setInfo(false)
    }

    /** Öffnet oder schließt den Infobereich. */
    fun toggleInfo() {
        if (infoOpen) closeInfo() else openInfo()
    }

    /** Rastet den Infobereich per Feder ein und merkt den Zustand. */
    private fun setInfo(open: Boolean) {
        infoOpen = open
        infoJob?.cancel()
        infoJob =
            scope.launch {
                val target = if (open) 1f else 0f
                animate(infoFraction, target, animationSpec = SETTLE_SPRING) { value, _ -> infoFraction = value }
                // Der Ruhezustand ist garantiert exakt, auch wenn die Feder knapp daneben endet.
                infoFraction = target
            }
    }

    /** Höhe des Infobereichs in Pixeln, wenn er ganz offen ist. */
    fun panelHeightPx(): Float = heightPx * INFO_PANEL_FRACTION

    /**
     * Verschiebung des Bildes nach oben bei offenem Infobereich, in Pixeln. Die Unterkante des Bildes rückt auf die Oberkante
     * des freien Bereichs oberhalb des Infobereichs. Dort bleibt ein Teil des Bildes sichtbar.
     */
    fun infoShiftPx(): Float = (fit.bottom - heightPx * (1f - INFO_PANEL_FRACTION)).coerceAtLeast(0f)

    /**
     * Die System-Rückgeste hat begonnen. Ohne Fortschritt (Tastenhalten) tritt nach [PEEK_GRACE_MS] der Kurze Blick ein. Erst
     * danach ist klar, dass kein Fortschritt kommt. Ist der Infobereich offen, schließt die Rückgeste zuerst ihn. Gezoomt gibt
     * es keinen Blick: die Rückgeste verhält sich wie bisher.
     */
    fun backStarted() {
        if (infoVisible) return
        if (phase != OverlayPhase.OPEN) return
        val id = shown.value ?: return
        if (isZoomed(id)) return
        phase = nextPhase(phase, OverlayEvent.BackStarted)
        backStartedNanos = System.nanoTime()
        progressSeen = false
        job?.cancel()
        peekJob?.cancel()
        peekJob =
            scope.launch {
                // Die Kachel vor dem Blick in den sichtbaren Bereich bringen, wie beim Öffnen (ADR 0010, Feinschliff).
                heroTile = beforeClose(id)
                delay(PEEK_GRACE_MS)
                if (progressSeen || phase != OverlayPhase.PEEKING) return@launch
                ViewerTrace.mark("Blick Start (ohne Fortschritt)", id)
                onPeekStarted()
                fadeHighlight(1f)
                // Bild auf die Blick-Stufe, der Grund wird dabei dunkler. Beides läuft im Blick-Job, damit ein Fortschritt es stoppt.
                coroutineScope {
                    launch {
                        animate(progress, PEEK_STAGE, animationSpec = tween(PEEK_MS)) { value, _ ->
                            progress =
                                value
                        }
                    }
                    launch { animate(peek, 1f, animationSpec = tween(PEEK_MS)) { value, _ -> peek = value } }
                }
                if (phase == OverlayPhase.PEEKING) ViewerTrace.mark("Blick Stufe erreicht", id)
            }
    }

    /**
     * Die Rückgeste meldet [fraction] (0 bis 1). Das Bild schrumpft in Richtung Kachel. Ist das Bild gezoomt, startet der
     * Schrumpf aus dem sichtbaren Ausschnitt. Ein Fortschritt schaltet den Kurzen Blick ab, der Schrumpf folgt dem Fortschritt.
     */
    fun backProgressed(fraction: Float) {
        if (infoVisible) return
        val next = nextPhase(phase, OverlayEvent.BackProgressed)
        if (next == phase && next != OverlayPhase.BACK) return
        val id = shown.value
        if (backStartedNanos != null && !progressSeen && id != null) {
            ViewerTrace.mark("Blick Start (mit Fortschritt)", id)
        }
        progressSeen = true
        if (phase == OverlayPhase.OPEN) captureZoomStart()
        job?.cancel()
        peekJob?.cancel()
        phase = next
        progress = 1f - fraction.coerceIn(0f, 1f)
        peek = 0f
        dragY = 0f
        fadeHighlight(1f)
        retireHeroIfReady()
    }

    /**
     * Die Rückgeste wurde abgebrochen. Der Viewer federt zurück ins Vollbild und bleibt offen. Das gilt für den Blick wie für
     * den Fortschritt. Die Meldung trägt die Zeit seit dem Beginn (ADR 0010).
     */
    fun backCancelled() {
        if (phase != OverlayPhase.BACK && phase != OverlayPhase.PEEKING) return
        val id = shown.value
        val started = backStartedNanos
        if (id != null && started != null) {
            ViewerTrace.mark("Blick abgebrochen nach ${elapsedMs(started)} ms", id)
        }
        backStartedNanos = null
        peekJob?.cancel()
        job?.cancel()
        phase = nextPhase(phase, OverlayEvent.BackCancelled)
        fadeHighlight(0f)
        job =
            scope.launch {
                coroutineScope {
                    launch { animate(progress, 1f, animationSpec = SETTLE_SPRING) { value, _ -> progress = value } }
                    launch { animate(peek, 0f, animationSpec = tween(PEEK_MS)) { value, _ -> peek = value } }
                }
                // Ruhezustand garantiert, auch wenn die Feder knapp daneben endet.
                progress = 1f
                peek = 0f
                phase = nextPhase(phase, OverlayEvent.SettleFinished)
                // Die Seite ist wieder sichtbar, derselbe Zoom liegt darunter. Der Rückweg aus dem Zoom endet hier.
                zoomFrom = null
                retireHeroIfReady()
            }
    }

    /**
     * Die Rückgeste wurde vollendet: der Viewer schließt zur Kachel. Ist der Infobereich offen, schließt die Geste zuerst ihn.
     * Ohne Fortschritt (das System meldet nur das Vollenden, etwa bei der Zurück-Taste) schließt der Viewer trotzdem.
     */
    fun backCommitted() {
        if (infoOpen) {
            backStartedNanos = null
            closeInfo()
            return
        }
        if (phase == OverlayPhase.BACK || phase == OverlayPhase.PEEKING) {
            val id = shown.value
            if (id != null && backStartedNanos != null) ViewerTrace.mark("Blick vollendet", id)
            backStartedNanos = null
            peekJob?.cancel()
            phase = nextPhase(phase, OverlayEvent.BackCommitted)
            closeAnimated()
        } else {
            close()
        }
    }

    /** Wählt das gezeigte Element, etwa nach einem Seitenwechsel. Ohne Wirkung, wenn geschlossen. Ein offener Infobereich bleibt. */
    fun select(id: Long) {
        if (shown.value == null) return
        shown.value = id
        // Ein anderes Element bekommt nicht das Vorschaubild des geöffneten.
        if (id != openedId) dropHero()
    }

    /**
     * Ruhezustand des Kurzen Blicks ohne Animation, für Tests und Goldens: Blick-Stufe, Hintergrund abgedunkelt, Kachel [tile]
     * hervorgehoben. Die Zustandsmaschine bleibt unberührt, es läuft kein Job.
     */
    internal fun snapPeek(tile: Rect) {
        cancelAnimations()
        phase = OverlayPhase.PEEKING
        progress = PEEK_STAGE
        peek = 1f
        highlight = 1f
        heroTile = tile
        heroAlpha = 0f
    }

    /** Ruhezustand des Infobereichs ohne Animation, für Tests und Goldens. */
    internal fun snapInfo(open: Boolean) {
        infoJob?.cancel()
        infoOpen = open
        infoFraction = if (open) 1f else 0f
    }

    private fun cancelAnimations() {
        job?.cancel()
        peekJob?.cancel()
        highlightJob?.cancel()
    }

    /** Schließt ohne Animation, etwa wenn es nichts mehr zu zeigen gibt. */
    fun forceClose() {
        cancelHeroJobs()
        job?.cancel()
        peekJob?.cancel()
        infoJob?.cancel()
        highlightJob?.cancel()
        shown.value = null
        progress = 0f
        dragY = 0f
        peek = 0f
        highlight = 0f
        infoOpen = false
        infoFraction = 0f
        zoomFrom = null
        heroAlpha = 0f
        backStartedNanos = null
        phase = OverlayPhase.CLOSED
    }

    /** Schließen über Zurück-Taste oder Schließen-Knopf. Ein offener Infobereich schließt zuerst. */
    fun close() {
        if (infoOpen) {
            closeInfo()
            return
        }
        val next = nextPhase(phase, OverlayEvent.CloseRequested)
        if (next == phase) return
        if (phase == OverlayPhase.OPEN) captureZoomStart()
        peekJob?.cancel()
        phase = next
        closeAnimated()
    }

    /**
     * Federt das Bild in die Ruhelage. Fortschritt und Kurzer Blick federn mit: Hat ein Ziehen den Rückfederlauf einer
     * abgebrochenen Rückgeste unterbrochen, bliebe das Bild sonst auf der Blick-Stufe stehen (ADR 0010).
     */
    private fun settleAnimated() {
        job?.cancel()
        job =
            scope.launch {
                coroutineScope {
                    launch { animate(dragY, 0f, animationSpec = SETTLE_SPRING) { value, _ -> dragY = value } }
                    launch { animate(progress, 1f, animationSpec = SETTLE_SPRING) { value, _ -> progress = value } }
                    launch { animate(peek, 0f, animationSpec = tween(PEEK_MS)) { value, _ -> peek = value } }
                }
                // Ruhezustand garantiert, auch wenn die Feder knapp daneben endet.
                dragY = 0f
                progress = 1f
                peek = 0f
                phase = nextPhase(phase, OverlayEvent.SettleFinished)
                retireHeroIfReady()
            }
    }

    /**
     * Schließt: Die Kachel wird vorher sichtbar gemacht ([beforeClose]), dann läuft die Hero-Animation vom aktuellen Stand
     * zurück zur Kachel. Aus dem Zoom startet sie am sichtbaren Inhalts-Rechteck, ohne vorheriges Zurücksetzen des Zooms. Die
     * Hervorhebung bleibt bis zum Ende stehen und blendet dann mit dem Viewer aus.
     */
    private fun closeAnimated() {
        job?.cancel()
        cancelHeroJobs()
        // Während des Schließens deckt das Vorschaubild die Seite ab, damit der Übergang nicht von der Seite unterbrochen wird.
        heroAlpha = 1f
        job =
            scope.launch {
                val id = shown.value
                // Ohne gültiges Inhalts-Rechteck gibt es keinen Startpunkt im Zoom. Dann fällt das Bild wie bisher zurück.
                if (zoomFrom == null && id != null && isZoomed(id)) zoomStates[id]?.resetZoom(snap())
                val tile = if (id != null) beforeClose(id) else null
                heroTile = tile
                if (id != null) ViewerTrace.mark(if (tile != null) "Ziel-Grenzen aktuell" else "Ziel ohne Kachel", id)
                coroutineScope {
                    launch { animate(progress, 0f, animationSpec = heroSpec()) { value, _ -> progress = value } }
                    launch { animate(dragY, 0f, animationSpec = heroSpec()) { value, _ -> dragY = value } }
                    launch { animate(peek, 0f, animationSpec = tween(PEEK_MS)) { value, _ -> peek = value } }
                    launch {
                        animate(
                            infoFraction,
                            0f,
                            animationSpec = heroSpec(),
                        ) { value, _ -> infoFraction = value }
                    }
                }
                // Geschlossen heißt Ruhezustand: alle Zwischenwerte garantiert zurück, sonst bliebe ein Rest im nächsten Öffnen.
                progress = 0f
                dragY = 0f
                peek = 0f
                infoFraction = 0f
                shown.value = null
                zoomFrom = null
                infoOpen = false
                highlightJob?.cancel()
                highlight = 0f
                heroAlpha = 0f
                phase = nextPhase(phase, OverlayEvent.CloseFinished)
            }
    }

    /**
     * Merkt sich, wo der sichtbare Zoom beginnt. Ohne Zoom bleibt es beim Ablauf ab dem Vollbild.
     *
     * `transformedContentBounds` ist in Telephoto 0.19.0 als veraltet markiert, liefert aber das Inhalts-Rechteck in
     * Komponenten-Koordinaten, also Overlay-Koordinaten (die Seite füllt das Overlay). Die Ersatz-API arbeitet mit
     * Koordinatenräumen, die hier nicht gebraucht werden. Beim Aktualisieren von Telephoto prüfen (ADR 0010).
     */
    @Suppress("DEPRECATION")
    private fun captureZoomStart() {
        val id = shown.value ?: return
        zoomFrom = if (isZoomed(id)) zoomStates[id]?.transformedContentBounds?.takeIf { it.isUsable() } else null
        if (zoomFrom != null) {
            // Das Vorschaubild übernimmt die Animation, die Seite ist ab jetzt nicht mehr sichtbar.
            cancelHeroJobs()
            heroAlpha = 1f
        }
    }

    private fun isZoomed(id: Long): Boolean = (zoomStates[id]?.zoomFraction ?: 0f) > ZOOM_EPSILON

    /** Blendet die Hervorhebung der Kachel auf [target], kurz und sanft. */
    private fun fadeHighlight(target: Float) {
        highlightJob?.cancel()
        highlightJob =
            scope.launch {
                animate(highlight, target, animationSpec = tween(HIGHLIGHT_MS)) { value, _ -> highlight = value }
            }
    }

    /** Blendet das Vorschaubild aus, sobald die Seite es zeigt und nichts anderes es braucht (Animation, Zoom-Rückweg). */
    private fun retireHeroIfReady() {
        val id = shown.value ?: return
        if (pageReadyId != id || zoomFrom != null) return
        if (phase != OverlayPhase.OPEN && phase != OverlayPhase.PEEKING && phase != OverlayPhase.DRAGGING &&
            phase != OverlayPhase.BACK && phase != OverlayPhase.SETTLING
        ) {
            return
        }
        fadeHeroOut(id)
    }

    /** Rückfall: Die Seite meldet nie ein Bild (Fehler, fehlendes Vorschaubild). Nach [HERO_FALLBACK_MS] verschwindet das Vorschaubild. */
    private fun armHeroFallback(id: Long) {
        fallbackJob?.cancel()
        fallbackJob =
            scope.launch {
                delay(HERO_FALLBACK_MS)
                if (pageReadyId != id) {
                    ViewerTrace.mark("Hero-Rückfall ohne Bild", id)
                    fadeHeroOut(id)
                }
            }
    }

    private fun fadeHeroOut(id: Long) {
        if (fading || heroAlpha <= 0f || zoomFrom != null) return
        fading = true
        fallbackJob?.cancel()
        fadeJob?.cancel()
        fadeJob =
            scope.launch {
                animate(heroAlpha, 0f, animationSpec = tween(HERO_FADE_MS)) { value, _ -> heroAlpha = value }
                ViewerTrace.mark("Hero entfernt", id)
            }
    }

    private fun dropHero() {
        cancelHeroJobs()
        heroAlpha = 0f
    }

    private fun cancelHeroJobs() {
        fallbackJob?.cancel()
        fadeJob?.cancel()
        fading = false
    }

    private fun heroSpec(): FiniteAnimationSpec<Float> = EmberlightMotion.heroSpec()

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    private companion object {
        val SETTLE_SPRING: FiniteAnimationSpec<Float> =
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/** Ausblenden des Vorschaubildes nach der Bereitschaft der Seite (ms). Kurz, damit es nicht wie ein Wechsel wirkt. */
internal const val HERO_FADE_MS = 100

/** Wie lange das Vorschaubild höchstens stehen bleibt, wenn die Seite kein Bild meldet (ms). */
internal const val HERO_FALLBACK_MS = 1_000L

/**
 * Gnadenfrist des Kurzen Blicks (ms). Kommt innerhalb dieser Zeit ein Fortschritt, ist es eine Gesten-Navigation und der Schrumpf
 * folgt dem Finger. Kommt keiner, hält die Rückgeste ohne Fortschritt (Tastenhalten) und der Blick beginnt.
 */
internal const val PEEK_GRACE_MS = 80L

/** Dauer, in der das Bild auf die Blick-Stufe fährt (ms). */
internal const val PEEK_MS = 200

/** Blick-Stufe des Bildes als Fortschritt: ca. 80 bis 85 % Größe, je nach Kachelgröße (ADR 0010, Kurzer Blick). */
internal const val PEEK_STAGE = 0.8f

/** Dauer des Ein- und Ausblendens der Kachel-Hervorhebung (ms). */
internal const val HIGHLIGHT_MS = 150

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Overlay-Zustand der Galerie oder des Albums. Die gewählte ID bleibt über `rememberSaveable` erhalten. Nach einem Prozess-Tod
 * ist der Viewer offen, aber ohne Animation (ADR 0010).
 */
@Composable
internal fun rememberViewerOverlayState(cellRect: (Long) -> Rect?): ViewerOverlayState {
    val scope = rememberCoroutineScope()
    val shown = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember(scope, cellRect) { ViewerOverlayState(scope, shown, cellRect) }
}
