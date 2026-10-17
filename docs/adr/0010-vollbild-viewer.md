# ADR 0010: Vollbild-Viewer

Status: angenommen, 16.10.2026

## Kontext

Paket 5a aus Meilenstein 2 bringt den Vollbild-Viewer für Fotos und Videos: Wischen zwischen Medien, Zoom bis zu sehr großen Bildern, Video-Wiedergabe, ausblendbare Leisten und einen immersiven Modus. Der Übergang aus dem Raster (Shared Element), das Wegwischen nach unten und die Vorschau der System-Rückgeste folgen in Paket 5b und sind im Abschnitt „Übergänge (5b)“ beschrieben.

Zu klären waren:

- wo der Viewer im Modul liegt und wie er an die Galerie angebunden wird,
- welche Bibliothek Zoom und große Bilder übernimmt, ohne Kopien privater Medien anzulegen,
- wie Video wiedergegeben wird, ohne Autoplay und mit genau einem Player,
- wie der Zustand über Drehen, Falten und Hintergrund erhalten bleibt,
- was der Viewer für Spaces (M3) vorbereiten muss.

## Entscheidung

### Ort: Paket `viewer` in `feature:gallery`

- Der Viewer liegt im Paket `io.github.labushuya.emberlight.feature.gallery.viewer` im Modul `feature:gallery`. Er braucht dieselbe Logik wie das Raster: `ScopedItems`, `MediaScope`, den Index und die Zoomstufe. Ein eigenes Modul würde diese Logik herausziehen oder duplizieren.
- Ein eigenes Modul lohnt erst, wenn Spaces den Viewer braucht. Dann wird der quellenunabhängige Kern in ein Modul verschoben, siehe unten.
- Die Abhängigkeitsrichtung bleibt: `feature:gallery` kennt `core:media`, nicht umgekehrt.

### Quellenunabhängiges Modell `ViewerItem`

- Die UI-Bausteine (Bildseite, Videoseite, Leisten, Pager) kennen nur `ViewerItem(id, uri, type, timestamp, width, height, durationMs, previewKey)`. `type` ist eine eigene Enum `ViewerMediaType`, nicht das MediaStore-Modell.
- `previewKey` ist der Schlüssel eines bereits geladenen Vorschaubilds im Speicher-Cache von Coil. Die Galerie setzt ihn aus `MediaThumbnail.memoryCacheKey`, ein Space kann ihn leer lassen.
- Die Abbildung `MediaItem.toViewerItem()` ist die einzige Stelle, die den MediaStore kennt.
- Ausnahme für das Standbild einer Videoseite: Es nutzt `MediaThumbnail` aus `core:media`. Ein Space ergänzt dafür in 3 eine eigene Standbild-Quelle. Das ist bewusst offen gelassen.

### Route, Navigation, Reihenfolge

- `@Serializable data class ViewerRoute(mediaId, bucketId, scopeAll)`. `scopeAll = true` meint die Zeitleiste, sonst das Album `bucketId` (`null` für „Sonstige“).
- Öffentlich: `NavGraphBuilder.viewerScreen(onBack)`, `NavController.navigateToViewer(mediaId, bucketId, scopeAll)`, `NavController.popViewer(lastViewedMediaId)`.
- Der Viewer zeigt genau die Reihenfolge des Rasters: Er nutzt dieselbe `ScopedItems`-Logik mit demselben Geltungsbereich. Ein Test prüft, dass die Reihenfolge der Gruppierung des Rasters entspricht.
- Zurück gibt die zuletzt angesehene Medien-ID an den Eintrag darunter (`SavedStateHandle`). Das Raster scrollt dorthin, wenn das Element außerhalb des sichtbaren Bereichs liegt, und löscht die Meldung danach. Die Scrollposition und der Reiter bleiben über den Back-Stack erhalten. Die Zoomstufe liegt im gemeinsamen Singleton (ADR 0009).
- Im Viewer verschwindet die Navigationsleiste (`NavigationSuiteType.None`). Die Galerie bleibt in der Leiste markiert, weil `ViewerRoute` zu ihrem Top-Level-Ziel gehört.

### ViewModel

- `ViewerViewModel` (Hilt, `SavedStateHandle.toRoute`) liefert die Liste aus `MediaRepository`. Die gewählte Seite steht im `SavedStateHandle` und überlebt Konfigurationswechsel.
- Verschwindet das gewählte Element, springt die Auswahl an dieselbe Stelle: auf den Nachbarn, der jetzt dort steht, sonst auf das letzte Element. Die Regel ist eine reine Funktion (`resolveViewerPosition`).
- Ist die Liste leer oder die Berechtigung entzogen, meldet der Zustand `Closed`. Der Viewer geht zurück ins Raster, das den Grund zeigt.

### Bilder: Telephoto ohne Coil-Modul

- Verwendet wird `me.saket.telephoto:zoomable-image` 0.19.0 mit `me.saket.telephoto:sub-sampling-image` 0.19.0. Das ist der Kern ohne Coil.
- Abweichung vom Auftrag: Der Auftrag nannte `zoomable-image-coil3`. Dessen Coil-Brücke erzwingt bei jedem Bild die Disk-Cache-Richtlinie „schreiben“. Der Kommentar im Quelltext sagt: „assume it'll be needed and force Coil to write this image to disk“. Das würde das Original jedes privaten Fotos in den Disk-Cache schreiben. ADR 0008 schließt genau das aus.
- Stattdessen implementiert `ContentUriImageSource` die Schnittstelle `ZoomableImageSource` selbst. Große Bilder liest Telephoto über `SubSamplingImageSource.contentUriOrNull` kachelweise direkt vom ContentResolver. Eine Kopie entsteht nicht.
- Vor dem Öffnen prüft die Quelle die Datei: Sie liest nur den Kopf und verlangt Maße und einen erkannten MIME-Typ. Fehlt die Datei, ist die Berechtigung weg oder ist es kein Bild, zeigt die Seite die ruhige Fehlermeldung (Symbol und Text).
- Platzhalter: Das Vorschaubild aus dem Speicher-Cache. Das Raster setzt dafür `memoryCacheKey` explizit (`media-thumbnail:<uri>`). Der Viewer sucht denselben Schlüssel im `ImageLoader` und zeigt das Bild als Platzhalter und als Vorschau für die Kacheln. Damit blitzt nichts auf.
- Gesten: Pinch, Doppeltipp (Telephoto `DoubleClickToZoomListener.cycle`) und Verschieben. Telephoto verbraucht eine Verschiebung nur, solange sie im Bild liegt (`canConsumePanChange`). Ist das Bild am Rand, bekommt der Pager die Geste und wischt weiter. Das prüft ein Gerätetest, siehe unten.
- Kompatibilität: Telephoto 0.19.0 kommt mit Kotlin 2.4.21, Compose BOM 2026.09.00 und Coil 3.6.3 zusammen (Kompilierung und Tests grün). Die transitiven Versionen (Compose-Runtime 1.8.0, `kotlin-stdlib` 2.1.21) hebt Gradle an.

### Video: Media3 mit genau einem Player, ohne Autoplay

- Artefakte: `media3-exoplayer` 1.11.1 und `media3-ui-compose` 1.11.1 (`PlayerSurface`). Nicht `media3-ui-compose-material3`: Der Viewer hat eine eigene Leiste im Stil der App. Die fertige Material-3-Steuerung würde nicht passen, und die Doku sagt, nur eine der beiden Varianten zu nutzen. Kein Cast-Modul.
- Der Player entsteht über die Fabrik `VideoPlayerFactory` (Hilt-Bindung auf `ExoVideoPlayerFactory`). Er entsteht erst beim ersten Abspielen und gehört dem ViewModel, das ihn in `onCleared` freigibt.
- Audio-Fokus über `setAudioAttributes(..., handleAudioFocus = true)`, Pause beim Abziehen des Kopfhörers über `setHandleAudioBecomingNoisy(true)`.
- Zustandsmaschine `VideoPlayback` (rein, ohne Android): Ein Video startet nur auf Antippen des Abspiel-Knopfes. Seitenwechsel löst den Player (Befehl `Release`). ON_STOP und Ende pausieren. Ein Fehler des Players fällt auf das Standbild zurück. Der Player wird pro Viewer nie zweimal angelegt.
- Das Standbild der Videoseite ist ein Vorschaubild des Systems mit demselben Cache-Schlüssel. Ist das Standbild nicht ladbar, zeigt die Seite die Fehlermeldung.
- Die Steuerung ist in der Leiste: Abspielen oder Pause, Zeitleiste zum Ziehen, Zeit und Dauer, Stumm-Schalter. Alle Knöpfe sind beschriftet. Der Stumm-Schalter gehört dem ViewModel und gilt auch für einen Player, der erst später entsteht.

### Hintergrund, Leisten, immersiver Modus

- Der Viewer liegt auf Schwarz (`#000000`), unabhängig vom Theme. Die Medien sollen neutral erscheinen.
- Ein einfaches Antippen blendet Leisten und Steuerung ein oder aus. Ausgeblendet heißt immersiv: Die Systemleisten sind über `WindowInsetsControllerCompat` aus, ein Wisch vom Rand zeigt sie vorübergehend (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`).
- Beim Verlassen stellt `ImmersiveSystemBars` immer den Normalzustand wieder: Systemleisten sichtbar und das vorige Verhalten. Das gilt für Zurück, Navigation und Konfigurationswechsel. Ein Prozessneustart beginnt mit der Standardanzeige der Activity.
- Die Leisten sind transparent über einem dezenten Verlauf. Oben: Zurück, Datum und Uhrzeit (lokalisiert über `DateFormat.getBestDateTimePattern`), bei Videos die Dauer. Funktionen wie Teilen oder Löschen gehören nicht in diesen Scope.
- Barrierefreiheit: Seiten tragen die Beschreibung wie im Raster. Die Leisten sind beschriftet. Die Aktion „Leisten ein- oder ausblenden“ steht als Bedienungshilfen-Aktion bereit.

### Pager

- `HorizontalPager` mit `key` = Medien-ID, `beyondViewportPageCount = 1` (Nachbarn vorladen) und `pageSpacing = 16 dp`. Die Seite folgt dem Zustand, etwa nach dem Löschen eines Elements.

## Übergänge (5b)

Ersetzt durch „Overlay-Architektur und Hero-Animation“ weiter unten. Der Abschnitt beschreibt den verworfenen Ansatz mit eigenem Navigationsziel und geteiltem Übergangs-Scope; der Stand ist nur noch Historie.

Paket 5b bringt den Übergang zwischen Raster und Vollbild, das Wegwischen nach unten und die Vorschau der System-Rückgeste. Der Viewer bleibt in `feature:gallery`. Der Übergangs-Scope liegt in `core:design`, damit die Galerie keine Abhängigkeit zu `app` braucht.

### Scopes über CompositionLocals

- `SharedTransitionLayout` umschließt den NavHost in `EmberlightApp`. Den Scope stellt `LocalSharedTransitionScope` (core:design) bereit.
- navigation-compose 2.10 stellt keinen Local für den `AnimatedVisibilityScope` bereit (dieser Local gehört zu Navigation 3). Darum reicht jedes `composable` für Galerie, Album und Viewer seinen Receiver (`this@composable`) über `LocalNavAnimatedVisibilityScope` weiter.
- Ohne Scope (Tests, Vorschauen, Goldens) liefert `Modifier.sharedMediaBounds` das Modifier unverändert. Die Ruhezustände der Goldens bleiben gleich.
- Die ktlint-Regel für CompositionLocals verlangt eine Freigabe. Die Freigabe per `.editorconfig` (`compose_allowed_composition_locals`) griff in der eingesetzten Version (ktlint-compose-rules 0.6.8) nicht. Deshalb steht genau dort `@file:Suppress("ktlint:compose:compositionlocal-allowlist")`, nur für die zwei Scopes.

### Schlüssel

- `viewerSharedKey(id)` ist `media-<id>`. Raster-Zelle (`MediaCell`) und Viewer-Seite teilen ihn. Je Medium gibt es genau einen Schlüssel, Albumkarten haben keinen.

### Crop ↔ Fit

- Verfahren: `sharedBounds` mit `ResizeMode.scaleToBounds(contentScale)`, `renderInOverlayDuringTransition = false` und einer Grenzanimation von 350 ms mit der M3-„emphasized“-Kurve (0,2 / 0 / 0 / 1). Das Element bleibt im Baum und folgt damit der Verschiebung der Oberfläche beim Wegwischen. Eine Feder wurde verworfen: Sie schwingt beim Wandel von Quadrat zu Rechteck über das Ziel hinaus.
- Die Zelle ist ein Quadrat mit `Crop`. Die Viewer-Vorschau liegt im Fit-Rechteck des Bildes und ist ebenfalls `Crop`. Das Seitenverhältnis kommt aus dem Vorschaubild im Speicher-Cache, sonst aus den Maßen des MediaStore (die bei gedrehten Fotos vertauscht sein können). Ist beides unbekannt, fehlt das gemeinsame Element, der Übergang blendet nur ein.
- Am Ende des Übergangs entspricht die Grenze dem Fit-Rechteck. Dort ist `Crop` identisch mit `Fit`, deshalb gibt es keinen Sprung und keine Verzerrung.
- Die interaktive Telephoto-Ansicht (Zoom, Verschieben) liegt auf der ganzen Seite. Sie ist bis zum Ende des Übergangs unsichtbar (`alpha = 0`) und wird danach sichtbar. Die Vorschau trägt den Übergang, nicht die Zoom-Ansicht.
- Verworfen: Das gemeinsame Element auf der ganzen Seite mit `Fit`. Das Bild würde beim Öffnen als kleines, eingepasstes Rechteck in der Kachel erscheinen.

### Platzhalter und hochauflösendes Bild

- Während des Übergangs zeigt die Seite das Vorschaubild aus dem Speicher-Cache. `rememberPageSettled()` meldet „ruht“, sobald der Übergang beendet ist und der Viewer nicht im Rückweg steht.
- `ContentUriImageSource` gibt das Subsampling-Delegate erst frei, wenn die Seite ruht. Bis dahin bleibt der Platzhalter.
- Video: Das Standbild ist das gemeinsame Element. Die `PlayerSurface` erscheint erst nach dem Übergang, und nur wenn die Seite angehängt ist und ruht.

### Rückweg zum zuletzt angesehenen Element

- Beim Zurück meldet der Viewer die Medien-ID (`popViewer`). Das Raster setzt die Scrollposition in einem `SideEffect`. Der läuft nach der Komposition und vor dem Layout desselben Frames. Ein `LaunchedEffect` wäre einen Frame zu spät: Die Kachel stünde im ersten Frame des Rückwegs noch nicht im Raster.
- Liegt das Element außerhalb des Fensters, das die gespeicherte erste Position zusammen mit Spalten und Zeilen aus der Bildschirmhöhe zeigt, springt das Raster an die Position. Liegt es innerhalb, bleibt die Position. Das Fenster ist eine Schätzung, weil Überschriften Positionen zählen. Sie entscheidet nur, ob vorab gescrollt wird.
- Ist das Element nicht mehr im Raster (etwa gelöscht), entfällt das gemeinsame Element. Der Rückweg blendet dann nur ein.

### Wegwischen nach unten

- Es startet nur auf einer ungezoomten Seite (`zoomFraction` höchstens 0,001) und auf Video-Seiten. Gezoomt gehört die Geste dem Verschieben.
- Achsen: Erst wenn die Bewegung den Schwellwert der Bedienung überschreitet, entscheidet die Achse. Vertikal nach unten (`|dy| > 1,5 · |dx|`) startet das Wegwischen. Alles andere geht an den Pager bzw. die Zoom-Geste. Verbraucht ein Kind die Bewegung, endet das Wegwischen. So bleiben Pager, Telephoto und Doppeltipp unberührt.
- Während des Ziehens folgt das Bild dem Finger, es wird um bis zu 10 % kleiner, der schwarze Grund wird proportional transparent und das Raster scheint durch. Die Leisten blenden aus.
- Loslassen: Schließen ab 15 % der Höhe oder bei einem Schnellschluss (mindestens 600 dp/s und mindestens 5 % der Höhe). Sonst federt das Bild zurück (Feder, `LowBouncy`, `MediumLow`).
- Übergabe an den Rückweg: Der Viewer schließt sofort über `popViewer`. Verschiebung, Verkleinerung und Grund laufen in derselben Dauer wie der Shared-Element-Übergang zurück auf Ausgangslage bzw. transparent. Das Bild startet an der gezogenen Stelle und landet an der Kachel, es springt nicht in die Mitte. Die Annahme, dass die Grenzen des Shared Elements die Verschiebung der Oberfläche nicht als Startposition übernehmen, ist nicht gerätegeprüft (siehe Gerätetest). Zeigt sich dort ein Sprung, wird auf einen kurzen Ausblend-Schluss ohne gemeinsames Element umgestellt.
- Barrierefreiheit: Zurück-Knopf, Antippen und Bedienungshilfen-Aktionen bleiben unverändert. Das Wegwischen ist eine zusätzliche Geste, nie die einzige.

### Predictive Back

- Der Viewer übernimmt die Rückgeste mit `PredictiveBackHandler` in `ViewerContent`. Der Fortschritt steuert dieselbe Vorschau wie beim Ziehen. Abbrechen federt zurück, Vollenden schließt über den Rückweg.
- Ein gewöhnlicher `BackHandler` ohne Fortschritt hätte die Vorschau des Systems unterdrückt. Er bleibt nur für Zustände ohne Inhalt (Laden, Schließen).
- Der NavHost zeigt für den Viewer keine eigene Vorschau, weil der Viewer die Geste zuerst bekommt (Callbacks in umgekehrter Registrierungsreihenfolge). Das Schließen ohne Geste (Zurück-Knopf) läuft über denselben Rückweg.
- Manifest: keine Änderung. Bei `targetSdk` 37 gilt `enableOnBackInvokedCallback` als Standard. Die Systemanimation prüft der Gerätetest.

### Bewegung

- Übergang und Rückweg 350 ms, Einblenden des Vollbilds 200 ms, Kurve M3 „emphasized“. Material 3 empfiehlt für große Bewegungen 300 bis 400 ms. Das Rückfedern ist eine Feder, weil sie sich aus dem Finger heraus natürlich anfühlt.

### Navigationsleiste

- Machbarkeit geprüft: Die Leiste verschwindet beim Öffnen des Viewers sofort, weil `NavigationSuiteType.None` das Layout ohne Übergang wechselt. Ein animiertes Ein- und Ausblenden setzt voraus, die Leiste selbst zu zeichnen und `NavigationSuiteScaffold` zu ersetzen. Das ist für 5b nicht gemacht. Die Leiste bleibt ein harter Wechsel, offen für Meilenstein 3.

### Grenzen

- Gezoomtes Raster: Die Kachel liegt in der skalierten Ebene des Pinch-Zooms, der Übergang rechnet mit den Layout-Grenzen. Der Start kann dann versetzt wirken (Gerätetest).
- Der Rückweg zu einem Element, das nicht mehr im Raster ist, blendet nur ein.
- Kein Golden des Übergangs: Die Zwischenstände hängen von Frame-Zeit und Zeichenreihenfolge ab. Ein Golden würde einen Zufall des Test-Laufs festschreiben.
- Die System-Vorschau ist im Test nur über die Dispatcher-API belegt (Start, Fortschritt, Abbruch, Vollenden), nicht als Systemanimation.

## Overlay-Architektur und Hero-Animation (Korrektur 2)

Status: umgesetzt im Branch `fix/m2-viewer-overlay`. Die Gerätebefunde sind unten festgehalten. Die Geräteprüfung ist gelaufen, Ergebnisse siehe „Geräteprüfung“.

### Begründung: Gerätebefunde

Der Vollbild-Viewer war ein eigenes Navigationsziel mit geteiltem Übergangs-Scope (Abschnitt „Übergänge (5b)“, ersetzt). Auf dem Gerät zeigten sich:

- Beim Wegwischen war der Hintergrund schwarz. Das Raster-Ziel war nicht komponiert, solange der Viewer ein eigenes Ziel war.
- Der Rückweg traf die falsche oder keine Kachel. Die Kachel des zuletzt angesehenen Elements lag außerhalb des Bildes, und die Rücksprung-Meldung wirkte erst im nächsten Frame.
- Übergänge sprangen, mit Versatz und kurzzeitig schwarzem Rahmen um das Bild.
- Die Navigationsleiste verschwand hart, weil `NavigationSuiteType.None` das Layout ohne Übergang wechselt.

Die Abhängigkeit an den Navigations-Übergang war die Ursache. Deshalb ist der Viewer jetzt ein Overlay über dem Raster, ohne eigenes Ziel.

### Overlay statt Ziel

- `MediaBrowser` (Paket `timeline`) legt Raster und Overlay übereinander. Das Raster bleibt komponiert und lebendig. Solange der Viewer ruhig und vollständig deckend steht, zeichnet das Raster nicht (Layer-Alpha 0).
- Galerie und Album haben je einen `MediaBrowser`. Das Overlay liegt über dem gesamten Ziel, also auch über der Kopfzeile.
- Entfernt: `ViewerRoute`, `navigateToViewer`, `popViewer`, die Rücksprung-Meldung über `SavedStateHandle`, das Scroll-Fenster `inVisibleWindow`, `SharedElementTransition` und die Locals `LocalSharedTransitionScope` und `LocalNavAnimatedVisibilityScope` in `core:design` (samt ktlint-Freigabe).

### Datenquelle und Zustand

- Die Liste ist die des Rasters: `MediaGridState.Content` des Hosts, geflacht in Rasterreihenfolge. Live-Updates kommen so automatisch mit. Ein zweiter Datenfluss entfällt.
- Der Zustand des Overlays (`ViewerOverlayState`) trägt Phase, Fortschritt und Zieh-Versatz. Die gezeigte Medien-ID liegt in `rememberSaveable` und übersteht Drehen und Falten. Nach Prozess-Tod ist der Viewer offen, ohne Animation.
- Der Wiedergabe-Halter `ViewerPlaybackViewModel` gehört dem Ziel des Rasters (Hilt, gescopter Eintrag). Er überlebt Konfigurationswechsel und gibt den einen Player beim Verlassen des Ziels frei.
- Ein Video, das sich nicht abspielen lässt (Demo-Medium mit Schema `emberlight-demo://`), meldet „Im Demo-Modus nicht abspielbar“ statt still nichts zu tun.

### Raster folgt dem Viewer

- Vor dem Öffnen scrollt das Raster ohne Animation, bis die Kachel des Elements vollständig sichtbar ist. Die Hero-Animation beginnt so an der echten Kachel.
- Wechselt der Pager die Seite, scrollt das Raster ebenfalls ohne Animation. Damit hat der Rückweg immer ein Ziel.

### Kachelgrenzen

- `MediaCell` meldet `boundsInRoot` in `CellBounds`, einer Tabelle ohne State. Die Animation liest sie beim Öffnen und Schließen, Recomposition gibt es nicht.
- Fehlt die Kachel (nicht komponiert), blendet der Übergang nur ein. Vor dem Schließen ohne Kachel verkleinert er zur Mitte und blendet aus.

### Hero-Geometrie

- Reine Funktionen in `HeroGeometry.kt`: `fitRect`, `cropSourceRect`, `heroFrame`, `transformRect`, `fallbackTile`, `lerpRect`.
- Ziel-Rechteck: von der Kachel zum Fit-Rechteck im Overlay. Quell-Ausschnitt: vom zentrierten Zuschnitt der Kachel zum vollen Bild. Beides linear in `t`, gezeichnet über `drawImage(srcOffset, srcSize, dstOffset, dstSize)`. Keine Verzerrung, kein Sprung, kein schwarzer Rahmen.
- Das Bild kommt aus dem Speicher-Cache von Coil (`MemoryCache`, fester Schlüssel aus ADR 0008). Ohne Cache-Eintrag blendet die Animation nur ein.
- Die Seite (Telephoto) bleibt während der Animation unsichtbar und erscheint erst mit `OPEN`, am selben Rechteck mit derselben Bitmap.
- Dauer 350 ms, Kurve `Emphasized` (`EmberlightMotion.HERO_MS`).

### Schließen

- Vom aktuellen Rechteck: Zieh-Versatz und Verkleinerung werden zusammen mit dem Fortschritt auf die Kachel des aktuellen Elements zurückgeführt, in derselben Dauer.
- Gezoomtes Bild: vor dem Schließen ohne Animation auf ungezoomt zurückgesetzt (`ZoomableState.resetZoom`), dann beginnt die Animation am Fit-Rechteck.

### Wegwischen und Rückgeste

- Wegwischen wie in `SwipeDismiss.kt`: Achse, Schwelle 15 %, Schnellschluss ab 600 dp/s und 5 % der Höhe, Skalierung bis 0,9. Der Hintergrund blendet proportional aus, das Raster scheint durch. Darunter federt das Bild zurück.
- Predictive Back: Der Fortschritt zieht das Bild in Richtung Kachel. Abbrechen federt zurück, Vollenden schließt. Ein Vollenden ohne vorherigen Fortschritt (Zurück-Taste) schließt ebenfalls.

### Navigationsleiste

- Die Navigation-Suite-Bibliothek bietet keinen Ausblend-Zustand (geprüft in 1.4.0). Deshalb zeichnet `EmberlightApp` die Leiste selbst (`NavigationBar`, auf breiten Displays `NavigationRail`).
- Im Viewer blendet die Leiste mit `AnimatedVisibility` (Höhe bzw. Breite, 300 ms) aus. Der Zustand kommt über `NavigationChrome` aus `core:design`, den der Host setzt.
- Das Raster verankert seine Zeilen oben. Beim Ausblenden wächst nur der sichtbare Bereich nach unten, die Spalten brechen nicht neu um.

### Zustandsmaschine

- `OverlayPhase` mit `nextPhase` (rein): `CLOSED`, `OPENING`, `OPEN`, `DRAGGING`, `BACK`, `SETTLING`, `CLOSING`. Veraltete Ereignisse lassen die Phase unverändert.

### Tests

- Unit: `HeroGeometryTest`, `OverlayPhaseTest`, `ViewerOverlayStateTest` (Öffnen, Schließen, Wegwischen über und unter der Schwelle, Schnellschluss, Rückgeste mit Abbruch und Vollenden, Zoom-Reset), `ViewerPlaybackViewModelTest` (ein Player, Freigabe, Stumm, Demo-Medium).
- Compose: `ViewerOverlayComposeTest` (Antippen öffnet das Overlay, Raster bleibt im Baum), `FastScrollGripTest` und `AlbumContentTest` mit dem Overlay, `EmberlightViewerNavigationTest` (Navigationsleiste aus, Rückkehr mit Reiter und Element sichtbar, Wischen wechselt den Titel).
- Nicht automatisiert: Mitten-Golden der Öffnen-Animation, Predictive-Back-Abbruch und Vollenden als Compose-Test, Navigationsleiste-Animation als Frame-Test. Sie sind in der Geräteprüfung zu belegen.

### Geräteprüfung

Gerät: Honor Magic V2, Android 16, Außendisplay mit 60 Hz. Debug-Build des Branches, Demo-Modus. Alle Fotos zeigten die Demo-App auf dem richtigen Display mit DEMO-Badge.

Ergebnisse der Szenarien:

- (a) Öffnen per Tap: Das Bild wächst aus der Kachel, die Nummer ist lesbar, die Leiste blendet aus. Zeitgenaue Zwischenbilder (50, 150 und 400 ms) ließen sich mit `screencap` und Fokus-Prüfung nicht erreichen. Die Flüssigkeit der Animation beurteilt der Nutzer visuell (siehe „Offen“).
- (b) Zweimal wischen, dann Zurück: Der Rückweg führt in die Kachel des dritten Elements, das Raster steht passend. Erfüllt.
- (c) Wegwischen: Das Bild folgt dem Finger, das echte Raster scheint durch. Über der Schwelle schließt der Viewer in die richtige Kachel, darunter federt er zurück. Erfüllt.
- (d) Navigationsleiste: Sie blendet aus und wieder ein, das Raster bleibt unverändert. Erfüllt.
- (e) 2 und 6 Spalten: Nicht automatisiert prüfbar, da kein Pinch per adb möglich ist. Offen, siehe unten.
- (f) Doppeltipp-Zoom, dann Zurück: Zoom-Reset und sauberes Schließen. Erfüllt.
- (g) Innendisplay: Das Gerät war zugeklappt. Offen, siehe unten.

Messung mit `dumpsys gfxinfo` über fünf Öffnen-und-Schließen-Zyklen:

| Build | Frames | Janky | P50 / P90 / P95 / P99 | Missed Vsync |
|---|---|---|---|---|
| Debug | 473 | 5,92 % | 8 / 23 / 32 / 48 ms | 8 |
| Benchmark (maßgeblich) | 484 | 2,07 % | 8 / 12 / 15 / 30 ms | 0 |

Maßgeblich ist der Benchmark-Build. Der Debug-Build ist mit Instrumentierung und ohne Optimierung gemessen und zeigt deshalb mehr Janky-Frames.

### Konsequenzen

- Der Viewer kennt kein Navigationsziel mehr. Galerie und Album sind für den Viewer nicht mehr auf die Route angewiesen. Die Rücksprung-Meldung entfällt.
- Die Navigationsleiste ist eine eigene Komponente. Sie muss bei neuen Top-Level-Zielen in `TopLevelDestination` gepflegt werden.
- Der Halter `ViewerPlaybackViewModel` liegt im Ziel des Rasters. Ein künftiger Space-Viewer bekommt seinen eigenen Halter.

## Konsequenzen

- Der Viewer ist vollständig in `feature:gallery`. Die Galerie hat keine neuen Abhängigkeiten nach außen.
- APK-Größe (unsigniertes Release, R8 aktiv): vorher 2.306.102 Byte, nach 5a 2.987.233 Byte, also +681.131 Byte (+29,5 %). Nach 5b 3.020.002 Byte, also +32.769 Byte (+1,1 %) durch Übergänge, Wegwischen und Predictive Back. Wie sich der Zuwachs auf Media3 und Telephoto verteilt, ist nicht gemessen. Eine Verkleinerung ist kein Ziel dieses ADR und bleibt offen.
- Stabilität (Compose-Compiler-Reports, nur lokal erzeugt): Alle Composables des Viewers sind `restartable` und `skippable`. Ausnahmen sind Helfer, die einen Wert zurückgeben (`rememberVideoPlaybackUi`, `mediaLabel`). Das ist so vorgesehen. Die Modelle `ViewerItem`, `VideoPlaybackUi` und `VideoPlaybackState` sind stabil.
- Stabilität nach 5b: Neu dazu kommen `ViewerPreview` und die Seiten mit Wischen. Sie sind `restartable` und `skippable`. Der Wegwisch-Zustand `DismissState` ist `@Stable`, seine Werte liest der Viewer in den Zeichenphasen (`graphicsLayer`, `drawBehind`), damit sich ein Frame des Ziehens nicht neu zusammensetzt. Helfer mit Rückgabewert: `rememberDismissState`, `rememberPageSettled`, `swipeDownToDismiss` und `sharedMediaBounds` sind nicht skippable, das ist wie bei den bestehenden Helfern vorgesehen. Unverändert unstable bleiben `player: Player?` (Media3) und `zone: ZoneId?`, beides schon vor 5b.
- Die Bildquelle ist austauschbar (`ViewerImageSource`). Tests setzen eine feste Quelle ein, Spaces setzt später eine eigene.
- Videos über Media3 brauchen einen Decoder pro Gerät. Das Verhalten auf schwachen Geräten ist offen.

## Tests und Goldens

- Unit: `ViewerViewModelTest` (Reihenfolge wie das Raster, Startindex, verschwindendes Element, leere Liste, entzogene Berechtigung, Steuerung des Players mit aufzeichnender Fabrik), `ViewerPositionTest`, `VideoPlaybackTest` (Zustandsmaschine), `GridScrollTargetTest` (Scroll-Ziel für das zuletzt Angesehene), `ViewerFormatsTest` (Titel Deutsch und Englisch).
- Compose: `ViewerContentTest` (Wischen wechselt Seite und Titel, Antippen schaltet Leisten, Doppeltipp zoomt über `zoomableState.zoomFraction`, Video zeigt Abspiel-Knopf und ruft `onPlay`, Zurück, Fehlermeldung bei fehlender Datei).
- App: `EmberlightViewerNavigationTest` (Zeitleiste → Viewer → Zurück mit Reiter und Scroll, Album → Viewer → Zurück, Navigationsleiste im Viewer fehlt, Wischen ändert den Titel).
- Goldens (Roborazzi, ADR 0006): Handy Englisch mit Leisten, Handy Englisch im immersiven Modus, Handy Englisch Video mit Steuerung, Handy Deutsch (Titel), Innendisplay 673 × 841 dp.
- Übergänge (5b): `SwipeDismissRulesTest` (Achse, Schwelle, Schnellschluss, Zoom), `ViewerPreviewFitTest` (Fit-Rechteck), `GridScrollWindowTest` (Fenster vor dem Layout, Merken der Meldung). Compose: `ViewerDismissTest` (über der Schwelle schließt, darunter federt zurück, Bild folgt dem Finger, horizontal bleibt beim Pager, gezoomt kein Wegwischen, Predictive Back abbrechen und vollenden), `SharedElementTransitionTest` (Öffnen und Zurück mit und ohne Scope, Zwischenstand im Übergang), `LastViewedScrollTest` (Element weit unten ist im ersten Frame sichtbar; sichtbares Element bleibt; fehlendes Element löscht nur die Meldung).
- Die Viewer-Goldens laufen in einer eigenen Testaufgabe `testViewerGoldens`, je Klasse in einem eigenen Prozess. Im Gesamtlauf der Galerie-Tests entstanden leere, helle Aufnahmen. Die Menge der vorher gelaufenen Tests spielt dabei eine Rolle, kleine Teilmengen sind nicht stabil reproduzierbar (ADR 0006, Befund). Die Ursache im Robolectric-Zustand ist nicht geklärt. Die Isolation über den Prozess ist die Behandlung, keine Ursachenklärung. `check` hängt an der Aufgabe. Korrektur 17.10.2026: Die frühere Aussage „in einem gemeinsamen Prozess“ und die Grünmeldung dazu waren falsch, die Aufgabe verifizierte nicht (ADR 0006, Nachtrag vom 17.10.2026).
- Der Platzhalter „Datei fehlt“ der Content-URI-Quelle wird auf dem Gerät geprüft. Robolectric liefert für unbekannte URIs einen Stream ohne echte Dekodierung. Der Test deckt deshalb den Fehlerpfad der Seite ab, nicht die Probe gegen eine echte Datei.

## Offene Punkte und Gerätetests

Umgesetzt in Paket 5b sind Shared Element, Wegwischen und Predictive Back. Offen sind die folgenden Punkte. Sie brauchen ein echtes Gerät, Robolectric prüft sie nicht.

Übergänge (5b):

- Der Übergang Raster → Viewer und zurück: Das Bild wächst aus der Kachel, ohne Sprung und ohne Verzerrung im Zuschnitt. Die Dauer wirkt angemessen.
- Übergabe an den Rückweg: Beim Wegwischen mit gezogenem Bild und beim Schließen über die Schwelle startet der Rückweg an der gezogenen Stelle und springt nicht in die Mitte. Zeigt sich ein Sprung, wird der Ausblend-Schluss eingeführt (siehe „Wegwischen nach unten“).
- Wegwischen nach unten: Das Bild folgt dem Finger, der Grund wird transparent, die Leisten blenden aus. Ab etwa 15 % der Höhe schließt der Viewer, darunter federt er zurück. Der Schnellschluss greift bei einer kurzen, schnellen Bewegung.
- Predictive Back: Die System-Vorschau der Rückgeste ist sichtbar. Abbrechen lässt den Viewer unverändert, Vollenden schließt ihn.
- Zoom und Pager: Ein gezoomtes Bild wischt nicht zu. Ungezoomt wischt der Viewer nach unten, horizontal bleibt es beim Pager. Doppeltipp zoomt wie bisher.
- Video: Das Standbild ist der Übergang, die Wiedergabefläche erscheint erst danach. Rückweg während der Wiedergabe ohne Artefakte.
- Gezoomtes Raster (Pinch) und Öffnen: Der Startpunkt kann versetzt wirken.
- Navigationsleiste: Sie wechselt beim Öffnen hart (bewusst offen, siehe Abschnitt Navigationsleiste).
- Rückkehr nach Wischen auf eine andere Seite: Die Kachel dieses Elements ist im ersten Frame sichtbar, auch bei großen Bibliotheken.
- Vorschau-Cache: Das Vorschaubild ist beim Öffnen aus dem Raster im Speicher-Cache. Ohne Cache-Eintrag blendet der Übergang nur ein.

Aus Paket 5a (weiterhin offen):

- Verhalten des Pagers am Bildrand bei gezoomtem Bild, Doppeltipp-Gefühl, EXIF-Rotation von Fotos und Videos, Decoder-Last bei sehr großen Bildern, Audio-Fokus und Kopfhörer-Abzug, Wiederherstellung der Systemleisten nach Zurück und Drehen.
- Die Schwelle für sehr große Bilder und der Speicherverbrauch der Kacheln sind auf dem Gerät zu messen.

Aus der Geräteprüfung (offen):

- Szenario (e), 2 und 6 Spalten, und Szenario (g), Innendisplay: nicht automatisiert, manuell durch den Nutzer zu prüfen.
- Flüssigkeit der Öffnen-Animation (a): Die Zwischenbilder waren zeitgenau nicht erreichbar. Die visuelle Beurteilung steht aus.
- P99 von 30 ms beim Benchmark-Build beobachten. Der Wert liegt über P95 (15 ms) und ist nur eine Messung über fünf Zyklen.
- Ein Debug-Intent-Extra für die Zoomstufe im Demo-Source-Set wäre für künftige automatische Tests hilfreich. Damit ließe sich Zoom ohne Pinch auf dem Gerät prüfen.

## Alternativen

- `zoomable-image-coil3` mit Coil-Disk-Cache: verworfen, siehe Abschnitt Bilder.
- Eigenes Modul `feature:viewer`: verschoben. Der Viewer braucht die Raster-Logik, und Spaces braucht ihn noch nicht.
- `media3-ui-compose-material3`: verworfen, die eigene Leiste im Stil der App passt besser.
- Autoplay: verworfen, das Antippen des Abspiel-Knopfes ist Auftrag.
- Eigene Blätterlogik statt `HorizontalPager`: verworfen, der Pager ist Standard und gibt Wischgesten am Bildrand weiter.

## Quellen

- Telephoto (Projekt, Zoom und Subsampling): https://github.com/saket/telephoto
- Media3 (Dokumentation, ExoPlayer und Compose-UI): https://developer.android.com/media/media3
- Immersiver Modus und Systemleisten: https://developer.android.com/training/system-ui/immersive
- Maven-Artefakte und POMs von Telephoto 0.19.0 (Abhängigkeiten geprüft) und Media3 1.11.1 (Google Maven).
- ADR 0006 (Screenshot-Tests), ADR 0007 (Medienzugriff), ADR 0008 (Vorschaubilder ohne Disk-Cache), ADR 0009 (Galerie-Reiter, Zoomstufe).

## Feinschliff nach dem Gerätetest (Korrektur 3)

Status: umgesetzt im Branch `fix/m2-viewer-feinschliff`. Die Geräteprüfung (Honor Magic V2, Demo-Modus, Messung vorher/nachher) ist gelaufen, Ergebnisse unter „Geräteprüfung Feinschliff“. Ein Fehler aus der Prüfung ist behoben (Commit `88bff5d`).

### Befunde

- Blip: Beim Öffnen wurde das Vorschaubild mit dem Ende der Animation entfernt, während die Seite ihr Bild noch nicht zeigte. Im Demo-Vollbild war die Seite bis zum Rendern leer. Beides ergab einen Frame ohne Inhalt, danach erschien das Bild erneut.
- Fehlende Kachel beim Rückweg: `boundsInRoot()` liefert bei einer teilweise abgeschnittenen Kachel nur den sichtbaren Teil. Das Animationsziel wurde dadurch zum Fragment, also falsch oder zu klein. Das Raster prüfte die Sichtbarkeit zudem vor dem Layout und ohne die Leiste.
- Ruckeln beim Schließen aus dem Zoom: `resetZoom(snap())` setzte das Bild hart zurück, bevor die Animation begann.

### Übergabe Hero zur Seite

- Das Vorschaubild bleibt gezeichnet, bis die Seite nachweislich ihr Bild zeigt. Quelle der Meldung bei Fotos: Telephoto `isImageDisplayed`, also der Zustand, in dem das Bild selbst (nicht der Platzhalter) gezeichnet wurde. Beim Video das geladene Standbild. Erst danach blendet es in 100 ms aus.
- Warum nicht `isPlaceholderDisplayed`: Telephoto meldet den Platzhalter auch dann als gezeichnet, wenn es keinen gibt (leerer `EmptyPainter`). Der Test `ViewerHeroHandoverComposeTest` hat das belegt. Mit dieser Meldung wäre die Animation auf einer leeren Seite geendet.
- Rückfall: Meldet die Seite kein Bild, verschwindet das Vorschaubild nach 1 s. Ein Fehler der Seite bleibt damit nicht hängen.
- Die Seite liegt während der Animation unter dem Vorschaubild, mit gleicher Geometrie und voller Deckkraft. So kann Telephoto ihren Platzhalter zeichnen und die Bereitschaft melden.
- Das Fit-Rechteck ist für Hero und Seite dieselbe Berechnung (`fitRect`), auf ganze Pixel gerundet (`snapRect`). Telephoto legt die Seite mit Fließkomma aus. Die Abweichung ist damit höchstens ein halber Pixel.
- Demo-Vollbild: Die Seite zeigt zuerst das Vorschaubild in Thumbnail-Größe (`renderDemoBitmap` mit `THUMBNAIL_LONG_EDGE`, identisch zur Kachel), dann das Vollbild.

### Rückweg: Kachel sichtbar machen, dann messen

- Vor dem Öffnen und vor dem Schließen sowie beim Seitenwechsel prüft `revealTile`, ob die Kachel vollständig unter der Kopfzeile und über der Leiste liegt. Wenn nicht, fordert das Raster ohne Animation an (`requestScrollToItem`) und wartet höchstens zwei Frames auf das Layout.
- Die Grenzen gelten erst als aktuell, wenn die Kachel seit der Anforderung neu gelegt wurde (Zählerstand je Eintrag in `CellBounds`).
- Kachelgrenzen werden ungeclippt gemessen (Position und Größe), nicht mit `boundsInRoot`.
- Sichtfenster: Das Raster meldet seine Grenzen in Root-Koordinaten. Die Oberkante der Leiste merkt sich `NavigationChrome.bottomBarTopPx`, solange die Leiste sichtbar war, weil der Viewer sie ausblendet.
- Ist die Kachel danach nicht sichtbar, blendet der Übergang zur Mitte aus (wie bisher ohne Kachel).

### Schließen aus dem Zoom

- Kein `resetZoom` mehr vor dem Schließen. Der Übergang startet am Inhalts-Rechteck, das Telephoto auf dem Bildschirm zeigt (`transformedContentBounds`, in Overlay-Koordinaten). Das Bild schrumpft aus dem, was man sieht, in die Kachel. Der Zustand des Zooms bleibt unberührt.
- Dasselbe gilt für die Rückgeste: Der Fortschritt verkleinert aus dem gezoomten Zustand. Bricht der Nutzer ab, federt das Bild in den gezoomten Zustand zurück.
- Ausnahme: Liefert Telephoto kein gültiges Rechteck, fällt der Zoom wie bisher zurück. Das ist ein Notfall ohne Startpunkt.
- Einschränkung: Das Startbild ist das Vorschaubild (Speicher-Cache). Im starken Zoom ist es unschärfer als die gezoomte Seite. Die Schärfe stellt sich nach dem Ende des Ausblendens wieder her. Ein Startbild aus dem Subsampling wäre aufwendiger und ist nicht umgesetzt.

### Bewusstes Verhalten: Wegwischen im Zoom schwenkt

Im Zoom gehört die Geste dem Bild. Ein Wisch nach unten verschiebt das Bild, er schließt den Viewer nicht. Das ist gewollt: Im Zoom wird geschwenkt. Wegwischen schließt nur bei ungezoomtem Bild. Die Logik ist unverändert (`canStartDismiss`).

### Debug-Marken

- Nur im Debug-Build (`BuildConfig.DEBUG`, im Galerie-Modul aktiviert), Tag `EmberlightViewer`. Im Release und Benchmark entfällt der Aufruf.
- Marken: `Hero-Animation Start`, `Hero-Animation Ende`, `Seite Bild angezeigt`, `Hero entfernt`, `Hero-Rückfall ohne Bild`, `Seite volle Qualität`, `Ziel-Grenzen aktuell`, `Ziel ohne Kachel`. Jede Zeile trägt die Medien-ID und den Zeitpunkt (ms, monoton).
- Die Reihenfolge der Marken prüfen die Zustandstests.

### Tests

- Unit: `HeroHandoverGeometryTest` (gleiche Fit-Berechnung, Rundung, Start aus dem Zoom bei 1x, 2x, 4x und am Rand, monotoner Übergang), `CellBoundsTest` (Zählerstand, Entfernen, Sichtfenster, Sichtbarkeit unter Kopfzeile und über der Leiste), `ViewerOverlayStateTest` (Bereitschaft, Rückfall, Auswahl verwirft das Vorschaubild, Schließen sichert die Kachel vorher).
- Compose (Robolectric): `ViewerHeroHandoverComposeTest` (Vorschaubild endet nach der Telephoto-Meldung, Rückfall ohne Bild, Schließen aus dem Zoom lässt den Zoom stehen und übergibt das Inhalts-Rechteck).
### Geräteprüfung Feinschliff

Gerät: Honor Magic V2, Android 16, Außendisplay 1060 × 2376, aktive Bildrate 60 Hz. Debug-Build und Benchmark-Build des Branches, Demo-Modus. Jedes Foto nur vom fokussierten Display mit DEMO-Badge.

**Fehler aus der Prüfung, behoben (`88bff5d`):** Beim Schließen meldete `Ziel ohne Kachel` zunächst 10 von 10 Mal. Ursache: Die Navigationsleiste maß beim Ausblenden ihre Oberkante auf 0. Diese Grenze wurde als Sichtgrenze übernommen, dadurch galt jede Kachel als nicht sichtbar und die Animation fiel auf die Mitte zurück. Die Oberkante wird jetzt nur noch gemessen, solange die Leiste sichtbar ist (`EmberlightApp`). Nach dem Fix: 10 von 10 `Ziel-Grenzen aktuell`.

Szenarien (Debug-Build nach dem Fix, Tag `EmberlightViewer`):

- **A, 10× Öffnen und Schließen** (Kacheln 1 bis 10, inklusive Randkacheln und Video). Je Öffnen: Start, Seite Bild, Ende, entfernt, ohne Lücke. Zeitdifferenzen über alle zehn Zyklen: Start bis Seite Bild 41 bis 157 ms, Seite Bild bis Ende 218 bis 335 ms, Ende bis entfernt 106 bis 132 ms (Ausblenden 100 ms). Schließen: 10× `Ziel-Grenzen aktuell`, Fallback 0. Frames: Beim Öffnen liegen die ersten zwei Frames je Öffnen bei 35 bis 60 ms. Das ist über 2× Budget. Im Debug-Build traten 27 solche Frames in 713 abgefragten Frames auf, davon 20 im Öffnen-Übergang.
- **B, 5× zwei bis drei Seiten wischen, dann Zurück.** Die Startkacheln lagen in der untersten sichtbaren Reihe. Ergebnis: `Ziel-Grenzen aktuell` 5 von 5, Fallback 0. Die Zielkachel entspricht jeweils der gewischten Seite (Start 15, nach zwei Wischen 17; Start 32, nach drei Wischen 35; Start 43, nach drei Wischen 46). Foto während des Schließens: Die Kachel 67 fliegt in ihre Position unterhalb der Kopfzeile, die Nummer ist lesbar. Foto nach dem Schließen: Kachel 52 steht direkt unter der Kopfzeile.
- **C, 3× Doppeltipp-Zoom, einmal zusätzlich Schwenken am Rand, dann Zurück.** Ergebnis: `Ziel-Grenzen aktuell` 3 von 3, Fallback 0. Foto während des Schließens (Kachel 52): Das Bild schrumpft aus dem sichtbaren Ausschnitt in die Kachel, ohne Sprung. Die Frame-Ausreißer liegen nur beim Öffnen (zwei Frames je Öffnen, 44 bis 56 ms, Debug-Build).
- **D, Wegwischen und Predictive Back.** Wisch unter der Schwelle (etwa 200 px): Der Viewer bleibt offen, keine Schließen-Marke. Wisch über der Schwelle (etwa 1000 px): Der Viewer schließt mit `Ziel-Grenzen aktuell`. Predictive Back per `motionevent` vom Rand (240 px, Abbruch): Der Viewer bleibt offen (Foto), keine Schließen-Marke. Ein vollendeter Predictive-Back-Schluss ist damit nicht belegt und bleibt offen.

Messung im Benchmark-Build, fünf Öffnen-und-Schließen-Zyklen, `gfxinfo reset` vorher. Die Galerie zeigte in dieser Messung 6 Spalten, vorher waren es 4. Die Werte sind deshalb nur bedingt vergleichbar.

| Messgröße | vorher | nachher |
|---|---|---|
| Janky frames | 2,07 % | 2,49 % |
| P50 / P90 / P95 / P99 | 8 / 12 / 15 / 30 ms | 8 / 13 / 16 / 28 ms |
| Missed Vsync | 0 | 0 |
| Frames gesamt | 484 | 482 |

Bildrate des aktiven Displays: 60 Hz.

Messmethode: `dumpsys gfxinfo framestats` liefert nur die letzten zehn Frames. Für den Verlauf wurde deshalb alle 0,1 s abgefragt und nach Vsync-Zeitstempel zusammengeführt. Die Zeitstempel stimmen mit den Log-Marken überein (gleiche Monotonuhr).

Offen aus dieser Prüfung:

- Die Frame-Ausreißer beim Öffnen (zwei Frames je Öffnen, 36 bis 60 ms) traten im Debug-Build auf. Im Benchmark-Build lag in fünf Zyklen nur ein Frame über 33 ms (35,4 ms, Abfrage nicht vollständig). Die Ursache ist nicht untersucht.
- Vollendetes Predictive Back ist nicht belegt (siehe D).
- Zoomstufen 2 und 6 Spalten sowie das Innendisplay bleiben wie in Korrektur 2 offen.

## Zurück-Taste und Schärfe (Korrektur 4)

Status: umgesetzt im Branch `fix/m2-viewer-zurueck-und-schaerfe`, Geräteprüfung durchgeführt (Honor Magic V2, Android 16, Demo-Modus). Die Messwerte stehen unter „Messwerte“, der Befund zur Rückgeste unter „Geräteprüfung Korrektur 4“. Die Rückgeste bleibt offen (siehe dort).

### Befunde

- Zurück-Taste gedrückt halten und loslassen: Das Medium wird kleiner gezogen und springt zurück, statt zu schließen. Wischen nach unten bleibt unverändert.
- Unschärfe beim Öffnen: Das Hero-Bild war das Raster-Vorschaubild, auf Bildschirmgröße skaliert. Es erschien verpixelt und wurde nach der Animation durch das Vollbild ersetzt.

### Rückgeste: Semantik und Befund

- Die Zuordnung in `ViewerOverlay` folgt der Android-Semantik. Endet der Flow normal (`onBackCompleted` in activity-compose 1.13.0, also `onBackInvoked`), schließt der Viewer, unabhängig vom Fortschritt. Ein Abbruch (`onBackCancelled`) wirft `CancellationException` und federt zurück. Eine Fortschritts-Schwelle gibt es in der Zuordnung nicht.
- Die 3-Tasten-Navigation (Android 15 und neuer) zeigt bei langem Drücken die Vorschau. Die Geräteprüfung belegt, welche Ereignisfolge beim Loslassen tatsächlich eintrifft. Die Marken „Rückgeste Start“, „Rückgeste ohne Fortschritt“, „Rückgeste vollendet“ und „Rückgeste abgebrochen“ (Tag `EmberlightViewer`) halten sie fest.
- Die Tests `ViewerBackGestureComposeTest` prüfen die Zuordnung über den echten `OnBackPressedDispatcher`: (a) started, Fortschritt 0, invoked: schließt. (a2) started ohne Fortschritt, invoked: schließt. (b) started, Fortschritt 0,6, invoked: schließt. (c) started, Fortschritt 0,4, cancelled: federt zurück. (d) einfacher Zurück-Druck ohne started: schließt. Alle fünf waren schon vor der Änderung grün. Ein Fehler in der Zuordnung ließ sich damit nicht reproduzieren.
- Folgerung: Ein Abbruch beim Loslassen kann nur vom Framework kommen. Stimmt die Ereignisfolge am Gerät mit „vollendet“ überein, liegt der Fehler außerhalb dieser Zuordnung. Diese Frage bleibt offen, bis die Marken am Gerät vorliegen (siehe „Geräteprüfung Korrektur 4“).

### Schärfe: Vorschau in Bildschirmgröße

- Methode: Coil-Anfrage auf das Original (`MediaThumbnail` über die Content-URI) mit `size` 1440 Pixel in der langen Kante. Ab API 29 lädt der eigene Fetcher über `ContentResolver.loadThumbnail` in dieser Größe. Das System cached dabei selbst. Vor API 29 dekodiert Coil das Bild gesampelt auf dieselbe Größe.
- Zeitpunkt: Die Anforderung startet beim Öffnen, parallel zur Hero-Animation. Die Nachbarn (±1) laden erst, wenn das gezeigte Element da ist. Ein Seitenwechsel bricht Ladevorgänge ab, die nicht mehr gebraucht werden.
- Hero: Das Raster-Vorschaubild wird in 100 ms zur Bildschirm-Vorschau überblendet, auch mitten in der Animation. Die Seite nutzt sie als Platzhalter, vor dem Telephoto-Basisbild und den Kacheln. Videoseiten melden ihre Bereitschaft erst über die Bildschirm-Vorschau, der Rückfall des Overlays greift, wenn sie fehlt.
- Auflösung 1440: Ein Querformat-Bild wird so auf dem Außendisplay (1060 × 2376) etwa auf die volle Breite gezeigt. Ein Hochformat-Bild ist auf 2376 Pixel Höhe hochskaliert, auf 1440 Pixel. Die Abweichung zur Displayschärfe ist damit gegenüber dem Vollbild sichtbar, aber nur bis zum Ende der Animation. Danach übernimmt das Telephoto-Basisbild.
- Demo: Die Demo-Quelle rendert ihr Motiv für die Vorschau in derselben Größe (Extra `ScreenPreviewExtra`). Der Platzhalter der Demo-Seite ist die Vorschau in Bildschirmgröße, das Vollbild (1280 Pixel) folgt danach.

### Cache-Abwägung

- Disk-Cache aus (ADR 0008). Die Anfrage setzt `diskCachePolicy(DISABLED)` ausdrücklich. Eine Kopie privater Medien im App-Cache entsteht nicht.
- Eigener Schlüssel `media-screen:<uri>`, getrennt vom Raster-Schlüssel. Der Store hält das gezeigte Element und seine Nachbarn (höchstens drei Bitmaps) und entfernt alle anderen Einträge aus dem Speicher-Cache. Die Raster-Thumbnails werden so nicht verdrängt. `release()` beim Schließen gibt alles frei.
- Speichergröße: Eine Bildschirm-Vorschau im Querformat (1440 × 1080 × 4 Byte) ist etwa 6,2 MB groß, im Hochformat (1080 × 1440 × 4 Byte) gleich viel. Drei Bitmaps belegen damit höchstens etwa 19 MB, bevor der Store sie beim Seitenwechsel abgibt.

### Messwerte

Gemessen am Gerät, Stand Branch `fix/m2-viewer-zurueck-und-schaerfe` (Commit 3827c16). Vorher zum Vergleich: Janky 2,49 %, P99 28 ms.

- Szenario A (Debug-Build, 10 Öffnen/Schließen per Tippen, Log `EmberlightViewer`): Die Vorschau war nach 60 bis 94 ms bereit (Median 78 ms ab „Hero-Animation Start“). Sie wurde 73 bis 117 ms nach dem Start im Hero übernommen. Das Hero-Ende kam 342 bis 362 ms nach dem Start. Damit war die Übernahme in 10 von 10 Fällen 239 bis 285 ms vor Animationsende. Fallbacks: 0.
- Szenario B (Debug-Build, Wisch nach unten): Ein langsamer Wisch über 150 px (unter der Schwelle von ca. 15 % der Höhe, also etwa 356 px) ließ den Viewer offen. Ein Wisch über 900 px schloss ihn.- Szenario C (Benchmark-Build, je 5 Öffnen/Schließen nach `gfxinfo reset`, zwei Läufe): Janky 2,26 % in beiden Läufen (legacy 3,29 % bzw. 2,06 %). P50 8 ms. P90 14 bzw. 13 ms, P95 17 bzw. 15 ms, P99 34 bzw. 26 ms. Missed Vsync 0 bzw. 0. Gegenüber dem Vorher (Janky 2,49 %, P99 28 ms) keine Verschlechterung des Jank-Anteils. Der P99-Wert schwankt zwischen den Läufen (26 und 34 ms).
- Speicher (`dumpsys meminfo`, Benchmark-Build): Vor 20 Öffnen/Schließen TOTAL PSS 186 180 KB, Graphics 76 840 KB. Danach TOTAL PSS 203 511 KB, Graphics 81 140 KB. Das ergibt +17,3 MB PSS und +4,3 MB Graphics. Die Schwelle von 30 MB ist eingehalten. Ob der Wert nach weiteren Zyklen plateauiert, ist nicht gemessen.

### Geräteprüfung Korrektur 4

Navigation: `navigation_mode` = 103 (3-Tasten-Navigation laut Auftrag).

Rückgeste durch den Nutzer, drei Versuche mit Haltedauer von etwa 1 s (Log `EmberlightViewer`):

1. „Rückgeste Start“ 12:56:59,5, dann „Rückgeste abgebrochen“ 12:57:01,1 (1,6 s nach dem Start). Der Viewer blieb offen; der nächste Start war wieder Bild id=7.
2. „Rückgeste Start“ 12:57:03,6, dann „Rückgeste vollendet“ 12:57:03,7 (66 ms nach dem Start), danach „Ziel-Grenzen aktuell“. Der Viewer schloss; das nächste Bild (id=8) öffnete sich um 12:57:06,1.
3. „Rückgeste Start“ 12:57:07,5, dann „Rückgeste abgebrochen“ 12:57:09,2 (1,7 s nach dem Start). Der Log endet hier, der Viewer war offen.

Die Marke „Rückgeste ohne Fortschritt“ und Fortschrittswerte erschienen bei den Nutzer-Versuchen nicht. Zwei von drei Haltevorgängen endeten mit „abgebrochen“, einer mit „vollendet“. Zum Vergleich: Bei 10 Schließen per Zurück-Taste (`adb shell input keyevent 4`) folgte jeweils „Rückgeste ohne Fortschritt“ und danach „Rückgeste vollendet“.

Befund: Die 3-Tasten-Navigation meldet beim Loslassen nicht durchgängig „vollendet“. Ob das an der Haltedauer, am Zeitpunkt des Loslassens oder am Framework liegt, ist mit den vorliegenden Marken nicht entscheidbar. Die Zuordnung in `ViewerOverlay` ist unverändert. Ein Fix ist in diesem Auftrag nicht vorgesehen; die Entscheidung liegt beim Projekt. Offen bleibt: Haltedauer und Fortschritt je Versuch protokollieren, damit sich der Befund belegen oder widerlegen lässt.
## Kurzer Blick und Infobereich (Meilenstein 2, Nachtrag)

Status: umgesetzt im Branch `feat/m2-kurzer-blick-und-infobereich`. Geräteprüfung am Honor Magic V2 (Demo-Modus), Ergebnisse unter „Geräteprüfung Kurzer Blick und Infobereich“.

### Android-Semantik und der Befund auf dem Gerät

- Android meldet die Rückgeste mit `onBackStarted`, optional `onBackProgressed`, und endet mit `onBackInvoked` (vollendet) oder `onBackCancelled` (abgebrochen). Die Zuordnung in `ViewerOverlay` bleibt wie in Korrektur 4 beschrieben: Vollenden schließt, Abbruch bleibt.
- Befund aus Korrektur 4: Die Zurück-Taste gehalten, ohne Fortschritt, endet nach etwa 1,6 s mit `onBackCancelled`. Bisher federte das Bild dann kommentarlos zurück.
- Entscheidung: Diese Folge wird zur bewussten Funktion „Kurzer Blick“. Die Semantik bleibt Android-konform: Abbruch heißt bleiben, Vollenden heißt schließen. Der Abbruch wird nicht in ein Schließen umgedeutet.

### Gestaltung des Kurzen Blicks

- Start ohne Fortschritt: Nach einer Gnadenfrist von 80 ms (`PEEK_GRACE_MS`) fährt das Bild in 200 ms auf die Blick-Stufe (Fortschritt 0,8, also etwa 80 bis 85 % Größe, leicht zur Kachel versetzt). Der schwarze Grund sinkt auf 35 % Deckkraft, das Raster bleibt darunter sichtbar. Die Leisten blenden aus.
- Die Gnadenfrist trennt die Fälle. Kommt innerhalb von 80 ms ein Fortschritt, ist es eine Gesten-Navigation, und das Bild folgt dem Finger wie bisher. Kommt keiner, hält der Nutzer die Taste. Ein schnelles Antippen schließt ohne Blick.
- Haptik beim Start des Blicks: `View.performHapticFeedback(CONTEXT_CLICK)`, ohne neue Berechtigung.
- Kachel-Hervorhebung: Ein Rahmen mit Leuchten in `primary` des Themes, 150 ms ein- und ausgeblendet. Die Kachel wird vorher über `beforeClose` (vorhandene Sichtbarkeitslogik) in den sichtbaren Bereich gebracht.
- Abweichung von der Vorgabe: Die Hervorhebung zeichnet das Overlay, nicht die Zelle. Die Blick-Stufe liegt über dem Raster und verdeckt die Kachel. Ein Rahmen in der Zelle wäre darunter nicht zu sehen. Die Zellen werden trotzdem nicht neu zusammengesetzt, ein Zähler in den Tests belegt das.
- Abbruch: Das Bild federt per Feder zurück ins Vollbild, die Hervorhebung blendet aus. Die Debug-Marke „Blick abgebrochen nach X ms“ trägt die Zeit seit dem Start.
- Vollenden: Schließen mit Hero-Animation in die hervorgehobene Kachel, wie bisher.
- Gezoomt gibt es keinen Blick. Die Rückgeste verhält sich wie bisher (Schrumpf aus dem Zoom, Abbruch federt zurück).

### Gesten-Priorität

- Nach unten: Wegwischen, wenn der Infobereich zu ist und das Bild nicht gezoomt ist. Ist der Infobereich offen, schließt die Geste zuerst den Infobereich.
- Nach oben: öffnet den Infobereich, nur ungezoomt und bei geschlossenem Bereich. Ist er offen, geschieht nichts.
- Gezoomt: Wischen schwenkt das Bild, in alle Richtungen. Kein Wegwischen, kein Infobereich.
- Horizontal: Pager. Auch bei offenem Infobereich wechselt das Medium, der Bereich zeigt dann die neuen Angaben.
- Rückgeste bei offenem Infobereich: schließt zuerst den Bereich, der Kurze Blick startet dann nicht.
- Antippen des Bildes schließt einen offenen Infobereich, sonst schaltet es die Leisten um.

### Der Infobereich

- Quellenunabhängiges Modell `MediaDetails` (Anzeigename, MIME, Größe, Album, Ordner, Schätzung des Datums). Die Anzeige kennt nur `ViewerItem`, damit ein Space dieselben Bausteine mit eigener Quelle nutzen kann.
- Darstellung: Material 3 `surfaceContainer`, obere Ecken 28 dp, Inhalt scrollbar. Der Bereich nimmt 60 % der Höhe ein, das Bild rückt so nach oben, dass 40 % der Höhe darüber sichtbar bleiben. Einrasten per Feder bei 50 % oder bei einem Schnellschluss (600 dp/s).
- Inhalt: Wochentag, Datum und Uhrzeit mit demselben Formatierer wie im Titel. Bei einer Zeitstempel-Quelle ungleich `DATE_TAKEN` der Hinweis „Aufnahmedatum unbekannt, Datum der Datei“. Dateiname, Album, Ordner, Auflösung „B × H“ mit Megapixel bei Fotos, Größe über `Formatter.formatShortFileSize`, Dateityp lesbar (JPEG, HEIC, MP4). Videos: Dauer.
- Bildrate ist nicht enthalten. MediaStore liefert sie nicht verlässlich, und `MediaMetadataRetriever` wäre bei jedem Öffnen zu teuer.
- Fehlende Werte fehlen in der Anzeige, nie als „null“.
- Barrierefreiheit: Die Überschrift „Details“ und das Pane des Bereichs sind benannt, jeder Wert ist eine Zeile „Bezeichnung: Wert“. Eine Bedienungshilfen-Aktion am Viewer heißt „Details anzeigen“ bzw. „Details ausblenden“.

### EXIF: lazy, ohne Ortsdaten

- Der Leser liegt in `core:media` (`PhotoExifSource`, Hilt-Bindung in `MediaModule`). Er öffnet die Datei über `ContentResolver.openInputStream` auf dem IO-Dispatcher, ohne Kopie. Gelesen wird erst, wenn der Infobereich offen ist.
- Gelesen werden nur Aufnahmefelder: Hersteller, Modell, Objektiv, Blende, Belichtungszeit, ISO, Brennweite und Blitz (Bit 0). GPS-Felder werden nie ausgewertet und nie angezeigt. Ohne `ACCESS_MEDIA_LOCATION` sind sie ohnehin redigiert, die Berechtigung bleibt aus.
- Einschränkung: `androidx.exifinterface` 1.4.2 parst beim Öffnen den gesamten Kopf, also auch einen eventuellen GPS-Block. Die App wertet ihn nicht aus und speichert nichts davon.
- Ergebnisse bleiben im Speicher, begrenzt auf 64 Einträge (am längsten ungenutzte fallen zuerst heraus). Auch leere Ergebnisse werden gemerkt, damit eine unlesbare Datei nicht bei jedem Öffnen erneut gelesen wird.
- Fehler beim Lesen (Datei fehlt, Berechtigung entzogen, kein EXIF) bleiben still. Es werden dann nur die Basisdaten gezeigt.
- Abhängigkeit: `androidx.exifinterface:exifinterface` 1.4.2 (stabil, Google Maven), ohne Play-Services-Abhängigkeit. `checkNoPlayServices` bleibt grün.

### Architekturentscheidung: der Infobereich liegt in jeder Seite

- Zuerst lag der Infobereich als Geschwister des Pagers im Overlay. Compose liefert eine Zeigereingabe nur an das oberste Element mit Eingabe. Der Infobereich fing horizontales Wischen und Wegwischen ab, der Pager bekam sie nicht. Das belegte der Compose-Test.
- Deshalb ist der Infobereich ein Kind jeder Seite (`ViewerInfoLayer`). Horizontales Wischen, das der Bereich nicht verbraucht, erreicht den Pager über die übliche Verschachtelung. Vertikales Wischen erreicht die Seite.
- Die Bildverschiebung liegt im Seiteninhalt, nicht im Pager. Der Bereich bleibt damit stehen, während das Bild nach oben rückt.
- Tippen gilt dem Bildinhalt, nicht der ganzen Seite. Ein Tipp auf den Bereich schließt ihn nicht.

### Demo-Modus

- `DemoViewerDetailsContribution` (nur Demo-Source-Set, Debug und Benchmark) liefert für die synthetischen Fotos deterministische, plausible EXIF-Angaben („Demo-Kamera“, feste Werte je Index). Videos und fremde Einträge bleiben bei der Quelle des Geräts.
- Im Release-Build gibt es diesen Beitrag nicht. Dort zeigt der Infobereich nur echte Angaben aus dem MediaStore.

### Tests

- Unit: `ViewerPeekStateTest` (Zustandsmaschine des Blicks und des Infobereichs), `OverlayPeekPhaseTest`, `InfoSheetRulesTest` (Achse nach oben, Einrasten), `InfoFormatsTest` (Blende, Belichtung, ISO, Brennweite, Megapixel, Auflösung, MIME, de und en), `MediaDetailsMappingTest`, `PhotoExifMappingTest` (Test-JPEG `core/media/src/test/resources/exif/sample-exif.jpg` ohne GPS-Block), `DemoViewerDetailsTest`.
- Compose (Robolectric): `ViewerInfoComposeTest` (Wischen nach oben öffnet mit Texten, nach unten schließt, Zurück schließt zuerst den Bereich, horizontales Wischen aktualisiert, gezoomt kein Bereich), `ViewerPeekComposeTest` (Blick mit Stufe und Hervorhebung, Abbruch, Vollenden, Fortschritt folgt dem Finger).
- Goldens (en, de, Video): `Viewer_phone_en_info.png`, `Viewer_phone_de_info.png`, `Viewer_phone_en_info_video.png`, `Viewer_phone_en_peek_highlight.png`. Feste Zustände über `snapInfo` und `snapPeek`, ohne Animation.

### Gerätebefunde und Messwerte

Siehe Abschnitt „Geräteprüfung Kurzer Blick und Infobereich“.

## Geräteprüfung Kurzer Blick und Infobereich (17.10.2026)

Gerät: Honor Magic V2, Android 16, Außendisplay (displayId 0, physische ID 4630946324137792644, 1060 × 2376). Debug-Build des Branches für I, II, III, Benchmark-Build für V. Demo-Modus per `--ez emberlight.demo true`, am Ende in beiden Apps aus und gestoppt. Fotos nur vom Außendisplay, nur wenn die Demo-App dort im Fokus war (`topResumedActivity`, Fokus auf Display 0 geprüft). Alle Fotos zeigten die Demo-App mit DEMO-Badge. Logcat nur mit PID und Tag `EmberlightViewer`.

### Bereits belegt aus dem vorigen Lauf

- Der Infobereich öffnet mit korrekten Demo-Daten.
- Horizontales Wischen wechselt das Medium, der Bereich zeigt dann die neuen Angaben.
- Zurück schließt zuerst den Bereich.
- Kurzer Druck: Blick-Marken korrekt.

### Szenario I-Rest: Infobereich offen, Wischen nach unten und Zurück

Ergebnis: nicht bestanden.

- Wischen nach unten auf dem Infobereich (Kopfzeile, oberer Rand und Inhalt, je langsam und schnell, vier Varianten) schloss den Bereich nicht. Fotos davor und danach identisch.
- Wischen nach unten vom Bildbereich oberhalb des Bereichs schloss den Infobereich. Danach blieb der Viewer offen, das Bild aber verkleinert und seitlich versetzt stehen (ca. 85 % Größe, schwarzer Rand), auch nach 4 s unverändert. Erwartet wäre das volle Vollbild. Der Zustand hängt.
- Zurück bei offenem Infobereich: schließt nur den Bereich, der Viewer bleibt offen (Foto davor und danach, belegt).
- Befund offen: Warum das Wischen auf dem Bereich nicht greift, ist nicht geklärt. Das Aufwärtswischen mit derselben Eingabe funktioniert. Vermutung, nicht belegt: Der verschachtelte Scroll des Bereichs verbraucht den Zug vor der Schließlogik.

### Szenario II: Doppeltipp-Zoom

Ergebnis: bestanden.

- Gezoomt: Wischen nach oben öffnet keinen Infobereich, das Bild schwenkt (Foto belegt).
- Gezoomt: Wischen nach unten schließt den Viewer nicht (Foto belegt).

### Szenario III: ohne Bereich und Zoom, Wegwischen nach unten

Ergebnis: bestanden.

- Unter der Schwelle: das Bild federt in die Vollbild-Lage zurück (Foto identisch zum frischen Viewer).
- Über der Schwelle: Der Viewer schließt in die Rasterkachel 1 (Foto belegt). Log: `Ziel-Grenzen aktuell id=1`.

### Szenario IV: echtes Halten

Ergebnis: nicht geprüft, vom Nutzer zu testen.

- `input keyevent --duration <ms> 4` existiert auf diesem Gerät, die Taste wurde damit nicht gehalten geprüft, weil der Befehl innerhalb des Viewers nur die Taste simuliert. Ein Halten am Bildschirm wurde nicht erzeugt. Kein Gerät-Hack.

### Szenario V: Messung Benchmark-Build

Benchmark-Build des Branches, Demo an. `gfxinfo reset`, dann fünfmal Öffnen, Infobereich auf (Wischen nach oben), Infobereich zu (Zurück), Viewer zu (Zurück). 763 Frames.

| Messgröße | Wert | ADR-Vergleich |
|---|---|---|
| Janky (neu) | 3,28 % (25) | ca. 2,3 % |
| Janky (legacy) | 4,33 % (33) | |
| P50 | 8 ms | |
| P90 | 13 ms | |
| P95 | 18 ms | |
| P99 | 29 ms | 26 bis 34 ms |
| Missed Vsync | 1 | |

Einschätzung: P99 liegt im Band, der Janky-Anteil über dem früheren Wert. Eine Einzelmessung mit fünf Zyklen, keine Wiederholung. Ob jeder der fünf Zyklen den Bereich tatsächlich öffnete, ist nicht je Zyklus geprüft.

### Offene Punkte

- Fehler I-Rest (oben): Wischen nach unten auf dem Infobereich schließt nicht, und der Schließweg vom Bild lässt das Bild im verkleinerten Zustand hängen. Der Merge ist deshalb nicht erfolgt.
- Echtes Halten der Zurück-Taste: vom Nutzer zu testen (Kurzer Blick, Stufe und Abbruch bei langem Druck).
