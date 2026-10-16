# ADR 0010: Vollbild-Viewer

Status: angenommen, 16.10.2026

## Kontext

Paket 5a aus Meilenstein 2 bringt den Vollbild-Viewer für Fotos und Videos: Wischen zwischen Medien, Zoom bis zu sehr großen Bildern, Video-Wiedergabe, ausblendbare Leisten und einen immersiven Modus. Der Übergang aus dem Raster (Shared Element) und das Wegwischen nach unten folgen in Paket 5b und sind nicht Teil dieses ADR.

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

## Konsequenzen

- Der Viewer ist vollständig in `feature:gallery`. Die Galerie hat keine neuen Abhängigkeiten nach außen.
- APK-Größe (unsigniertes Release, R8 aktiv): vorher 2.306.102 Byte, nachher 2.987.233 Byte, also +681.131 Byte (+29,5 %). Wie sich der Zuwachs auf Media3 und Telephoto verteilt, ist nicht gemessen. Eine Verkleinerung ist kein Ziel dieses ADR und bleibt offen.
- Stabilität (Compose-Compiler-Reports, nur lokal erzeugt): Alle Composables des Viewers sind `restartable` und `skippable`. Ausnahmen sind Helfer, die einen Wert zurückgeben (`rememberVideoPlaybackUi`, `mediaLabel`). Das ist so vorgesehen. Die Modelle `ViewerItem`, `VideoPlaybackUi` und `VideoPlaybackState` sind stabil.
- Die Bildquelle ist austauschbar (`ViewerImageSource`). Tests setzen eine feste Quelle ein, Spaces setzt später eine eigene.
- Videos über Media3 brauchen einen Decoder pro Gerät. Das Verhalten auf schwachen Geräten ist offen.

## Tests und Goldens

- Unit: `ViewerViewModelTest` (Reihenfolge wie das Raster, Startindex, verschwindendes Element, leere Liste, entzogene Berechtigung, Steuerung des Players mit aufzeichnender Fabrik), `ViewerPositionTest`, `VideoPlaybackTest` (Zustandsmaschine), `GridScrollTargetTest` (Scroll-Ziel für das zuletzt Angesehene), `ViewerFormatsTest` (Titel Deutsch und Englisch).
- Compose: `ViewerContentTest` (Wischen wechselt Seite und Titel, Antippen schaltet Leisten, Doppeltipp zoomt über `zoomableState.zoomFraction`, Video zeigt Abspiel-Knopf und ruft `onPlay`, Zurück, Fehlermeldung bei fehlender Datei).
- App: `EmberlightViewerNavigationTest` (Zeitleiste → Viewer → Zurück mit Reiter und Scroll, Album → Viewer → Zurück, Navigationsleiste im Viewer fehlt, Wischen ändert den Titel).
- Goldens (Roborazzi, ADR 0006): Handy Englisch mit Leisten, Handy Englisch im immersiven Modus, Handy Englisch Video mit Steuerung, Handy Deutsch (Titel), Innendisplay 673 × 841 dp.
- Die Viewer-Goldens laufen in einer eigenen Testaufgabe `testViewerGoldens` in einem gemeinsamen Prozess. Im Gesamtlauf der Galerie-Tests entstanden leere, helle Aufnahmen. Die Menge der vorher gelaufenen Tests spielt dabei eine Rolle, kleine Teilmengen sind nicht stabil reproduzierbar (ADR 0006, Befund). Die Ursache im Robolectric-Zustand ist nicht geklärt. Die Isolation über den Prozess ist die Behandlung, keine Ursachenklärung. `check` hängt an der Aufgabe.
- Der Platzhalter „Datei fehlt“ der Content-URI-Quelle wird auf dem Gerät geprüft. Robolectric liefert für unbekannte URIs einen Stream ohne echte Dekodierung. Der Test deckt deshalb den Fehlerpfad der Seite ab, nicht die Probe gegen eine echte Datei.

## Offene Punkte für Paket 5b und Gerätetests

- Shared Element aus dem Raster: Die Medien-ID und der Platzhalter aus dem Cache sind vorbereitet. Der Übergang selbst fehlt.
- Wegwischen nach unten: fehlt. Die Seite trägt dafür keine Geste.
- Gerätetests, bevor 5a als fertig gilt: Verhalten des Pagers am Bildrand bei gezoomtem Bild, Doppeltipp-Gefühl, EXIF-Rotation von Fotos und Videos, Decoder-Last bei sehr großen Bildern, Audio-Fokus und Kopfhörer-Abzug, Wiederherstellung der Systemleisten nach Zurück und Drehen.
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
