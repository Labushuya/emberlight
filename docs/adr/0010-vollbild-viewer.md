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

Status: umgesetzt im Branch `fix/m2-viewer-overlay`. Die Gerätebefunde sind unten festgehalten. Die Geräteprüfung vor dem Merge steht aus, siehe „Geräteprüfung“.

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

Die Prüfung auf dem Gerät (Demo-Modus, Fotos, `dumpsys gfxinfo`) ist Teil der Abnahme vor dem Merge. Ergebnisse und Messwerte stehen in der PR-Beschreibung. Dieser Abschnitt wird nach der Prüfung um die Befunde ergänzt.

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
- Die Viewer-Goldens laufen in einer eigenen Testaufgabe `testViewerGoldens` in einem gemeinsamen Prozess. Im Gesamtlauf der Galerie-Tests entstanden leere, helle Aufnahmen. Die Menge der vorher gelaufenen Tests spielt dabei eine Rolle, kleine Teilmengen sind nicht stabil reproduzierbar (ADR 0006, Befund). Die Ursache im Robolectric-Zustand ist nicht geklärt. Die Isolation über den Prozess ist die Behandlung, keine Ursachenklärung. `check` hängt an der Aufgabe.
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
