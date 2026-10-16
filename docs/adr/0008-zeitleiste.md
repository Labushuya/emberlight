# ADR 0008: Zeitleiste der Galerie

Status: angenommen, 16.10.2026. Nachtrag Paket 3b (Pinch-Zoom, Anker, adaptive Spalten, Schnell-Scrollleiste), siehe unten.

## Kontext

Paket 3a aus Meilenstein 2 bringt die Zeitleiste: Raster mit Datums-Überschriften, Vorschaubilder, Berechtigungs- und Leerzustände. Der Index (ADR 0007) liefert Metadaten, keine Bilder. Zu klären waren:

- wie Vorschaubilder geladen und gehalten werden, ohne Kopien privater Medien im App-Cache,
- wo der Thumbnail-Code liegt, damit Alben (Paket 4) ihn mitnutzen,
- wie Datums-Überschriften entstehen und getestet werden,
- wie die Berechtigungs-UX mit „ausgewählten Fotos“ und dauerhaft verweigerten Anfragen umgeht,
- welche Grid-Entscheidungen gelten.

Pinch-Zoom und Schnell-Scrollleiste sind Paket 3b und nicht Teil dieses ADR, außer als offene Punkte.

## Entscheidung

### Vorschaubilder mit Coil 3.6.3

- Ein App-weiter `ImageLoader` als Hilt-Singleton (`ImageLoaderModule` in `app`). `EmberlightApplication` implementiert `SingletonImageLoader.Factory` und liefert diesen Loader.
- Speicher-Cache an, **Disk-Cache aus** (`diskCachePolicy(CachePolicy.DISABLED)`). Das System cached MediaStore-Thumbnails ohnehin. Kopien privater Vorschaubilder landen so nicht im App-Cache.
- Kein Netzwerk-Modul. Artefakte: `coil` (Kern, in `core:media`), `coil-compose` (in `feature:gallery`), `coil-video` (in `app`), `coil-test` nur für Tests. `checkNoPlayServices` bleibt grün.
- Ab API 29 lädt ein eigener Fetcher über `ContentResolver.loadThumbnail(uri, Size, CancellationSignal)` in der längeren Kante der Zielgröße. Der `CancellationSignal` hängt am Coroutine-Job, bricht Coil ab, stoppt auch das Laden im System.
- Vor API 29 bildet ein Mapper das Modell auf die Content-URI ab. Coils Standard-Decoder übernimmt. Videos laufen dann über `VideoFrameDecoder`.
- Das Modell `MediaThumbnail(uri, type)` ist eine Datenklasse mit String-URI. Der Vollbild-Viewer (Paket 5) lädt Originale über die URI und nutzt dieses Modell nicht.
- Platzhalter: Fläche in `surfaceContainerHigh`. Crossfade ist aus (`crossfade(false)`), damit ein schnell scrollendes Raster keine Überblendungen rechnet.

### Ort des Thumbnail-Codes

- `core:media` (Paket `thumbnail`): Modell, Fetcher, Mapper und `ComponentRegistry.Builder.addMediaThumbnails(sdkInt)`. Es ist Medienzugriff, und der Fetcher braucht die MediaStore-URIs und das SDK-Verhalten aus demselben Modul.
- `feature:gallery`: die Zellen-Composable `MediaCell`. Alben liegen in `feature:gallery` und können sie direkt nutzen.
- Die Abhängigkeitsrichtung bleibt feature → core. `core:design` bekommt keinen Medienbezug.

### Gruppierung Tag und Monat

- `TimelineGrouping.group(items, mode, today, zone)` läuft in O(n) über den bereits sortierten Index, ohne erneutes Sortieren. Berechnung auf `DefaultDispatcher` (injiziert, `Dispatchers.Default`). Das Ergebnis ist eine unveränderliche Liste aus `@Immutable`-Modellen.
- Tagesgruppen bis 4 Spalten, Monatsgruppen ab 5. In Paket 3a ist nur die Standardstufe (4) erreichbar. Die Monatsgruppierung ist vorbereitet und getestet.
- Überschriften: „Heute“ und „Gestern“, sonst Wochentag und Datum. Die Jahreszahl steht nur außerhalb des laufenden Kalenderjahres. Monate als „Oktober 2026“. Der Text entsteht in der UI mit `DateFormat.getBestDateTimePattern(locale, skeleton)`, damit Reihenfolge und Wörter je Sprache stimmen.
- `Clock` und `ZoneId` sind injizierbar (`TimelineModule`). Tests ersetzen sie durch feste Werte.

### Berechtigungs-UX

Orientiert an der Doku zu „ausgewählten Fotos“ (Android 14). Die Anfrage nutzt `rememberLauncherForActivityResult(RequestMultiplePermissions)` mit `requiredPermissions(sdk)`.

- **Kein Zugriff (NONE):** Vollflächige Erklärung mit Icon, Titel in Fraunces und Hinweis, dass nichts die App verlässt. Button „Zugriff erlauben“.
- **Dauerhaft verweigert:** Der Dialog erscheint nicht mehr. Dann steht dort „In Einstellungen öffnen“ (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Merkmal „schon gefragt“:** DataStore Preferences (`androidx.datastore:datastore-preferences` 1.2.1, stabil). Gespeichert wird nur der Boolean „Anfrage gestellt“, nicht der Berechtigungsstatus. Der Status wird bei jeder Abfrage frisch gelesen.
- Regel (`timelineUiState`): Dialog anbieten, wenn „noch nie gefragt“ oder die Systemerklärung anzeigbar ist (`shouldShowRequestPermissionRationale`). Sonst nur Einstellungen.
- Das Merkmal wird erst nach der Antwort des Systemdialogs gesetzt. Würde es beim Start der Anfrage gesetzt, stünde während des Dialogs fälschlich „nur Einstellungen“ da.
- **PARTIAL (Android 14+):** Inhalt zeigen, Banner oben: „Du hast Zugriff auf ausgewählte Fotos gegeben.“ Aktionen „Mehr auswählen“ (erneute Anfrage, das System zeigt den Auswahldialog) und „Alle erlauben“ (Einstellungen).
- **Ein Typ fehlt (ab API 33):** Banner „Videos sind noch nicht freigegeben.“ bzw. für Fotos, mit „Zugriff erlauben“. Grundlage ist `MediaLibrary.missingTypes` aus ADR 0007.
- `ON_RESUME` ruft `repository.refresh()` auf, damit Änderungen aus den Einstellungen sofort greifen. Der erste Vordergrund-Moment lässt das aus, weil der Index dann gerade erst startet.

### Grid

- `LazyVerticalGrid` mit `GridCells.Fixed(columns)`. `columns` ist Parameter von `GalleryContent`, Standard `DEFAULT_COLUMNS = 4`.
- Quadratische Zellen mit Zuschnitt (`ContentScale.Crop`), 2 dp Abstand.
- Überschriften über volle Breite (`GridItemSpan(maxLineSpan)`). Sticky-Header gibt es in `LazyVerticalGrid` nicht, nur in `LazyColumn`. Deshalb normale Überschriften, die mit dem Inhalt scrollen. Das ist die einzige stabile Option in der eingesetzten Compose-Version.
- Stabile Keys: Überschriften über den Gruppenschlüssel (`day:2026-10-16`), Medien über die URI (eindeutig über beide Collections). `contentType` für Kopfzeilen, Banner und Medien.
- Video-Kennzeichnung: Abspiel-Symbol und Dauer (`m:ss` bzw. `h:mm:ss`) unten links über einem dezenten Verlauf. Die Zelle fasst Bild und Dauer zu einem Eintrag zusammen. Die Beschreibung lautet „Video, 0:42, vom 16. Oktober 2026“.
- Antippen ruft `onMediaClick` nach außen auf. Der Viewer (Paket 5) ist noch nicht verdrahtet.
- Edge-to-edge: Der Inhalt läuft unter einer schlichten, beim Scrollen einklappenden Top-App-Bar „Fotos“ (Fraunces). Die Bar nutzt `exitUntilCollapsedScrollBehavior`. Paket 4 ergänzt dort die Reiter.
- Barrierefreiheit: Jede Zelle trägt eine Beschreibung, Überschriften sind als Heading markiert.

### Performance

- Zell-Composables und UI-Modelle sind laut Compose-Compiler-Report (`reportsDestination`, nur lokal, nicht committet) restartable und skippable. Keine unstabilen Parameter in den Galerie-Composables.
- Datumsformatierer werden pro Sprache einmal erzeugt (`TimelineFormats`), nicht pro Zelle.
- Vorschaubilder werden in Zellgröße in Pixeln geladen, nicht als Original.

### Tests

- Reine Logik ohne Android: Gruppierung, Zustandsabbildung, Dauerformat.
- ViewModel mit Fake-Repository und Fake-Store, `StandardTestDispatcher`.
- Compose-Tests auf `GalleryContent` mit festen Daten und `coil-test` (`FakeImageLoaderEngine`).
- Goldens: Zeitleiste Telefon en und de, Tablet quer en, Berechtigung, PARTIAL-Banner, Leerzustand, fehlender Videotyp. App-Screenshots mit Hilt und Fake-Index.
- Hilt-Ersatz für Tests: `MediaRepositoryModule` (öffentlich, bindet die MediaStore-Implementierung über den Qualifier `@MediaStoreSource`) und `TimelineModule` (Uhr, Zeitzone, Dispatcher) lassen sich mit `@TestInstallIn` ersetzen. Die Implementierung selbst bleibt intern.

## Konsequenzen

- Die Release-APK wächst um rund 425 KB (1,74 MiB auf main, 2,15 MiB auf diesem Stand). Ursache sind Coil-Kern, Coil-Compose, Coil-Video und DataStore.
- Die Galerie braucht keinen Disk-Cache. Kaltstart und Scrollen hängen am System-Thumbnail-Cache, bei sehr großen Bibliotheken kann das langsamer sein als ein eigener Cache. Das ist bewusst (Datenschutz) und auf dem Gerät zu prüfen.
- Die Standardstufe ist in 3a fest. Auf breiten Fenstern (Tablet quer) entstehen dadurch große Zellen. Die adaptive Spaltenzahl kommt mit 3b, weil ab 5 Spalten die Monatsgruppen greifen.

## Offene Punkte für Paket 3b

- **Speicherbedarf des Index bei großen Bibliotheken** (ADR 0007, Schätzung nicht gemessen).

Die Punkte zu Pinch-Zoom, Anker, adaptiven Spalten und Schnell-Scrollleiste sind mit Paket 3b umgesetzt (siehe Nachtrag). Offen bleiben die Messungen auf dem Gerät, siehe dort.

## Nachtrag Paket 3b: Pinch-Zoom, Anker, adaptive Spalten, Schnell-Scrollleiste

Dieser Nachtrag ersetzt die Aussagen zur festen Spaltenzahl im Abschnitt „Grid“ (`DEFAULT_COLUMNS = 4`) und in den Konsequenzen. Die Spalten folgen jetzt der Zoomstufe und der Breite.

### Zoomstufen als Kachelbreite

- Fünf Stufen, definiert über die Ziel-Kachelbreite in dp: `[180, 120, 90, 72, 52]` (`ZoomLevels.TILE_WIDTHS_DP`). Stufe 0 hat die größten Kacheln, Stufe 4 die kleinsten.
- Spalten = `max(1, floor(Breite / Zielbreite))`, Breite ist die Breite des Rasters in dp. Die Werte ergeben:

| Stufe | Ziel dp | 412 dp (Handy) | 673 dp (Innendisplay) | 900 dp (Tablet) |
|---|---|---|---|---|
| 0 | 180 | 2 | 3 | 5 |
| 1 | 120 | 3 | 5 | 7 |
| 2 (Standard) | 90 | 4 | 7 | 10 |
| 3 | 72 | 5 | 9 | 12 |
| 4 | 52 | 7 | 12 | 17 |

- Die Gruppierung hängt an der **Stufe**, nicht an der Spaltenzahl. Stufen 0 bis 2 gruppieren nach Tagen, Stufen 3 und 4 nach Monaten. So zeigt ein Tablet auf der Standardstufe Tage in zehn Spalten statt Monate.
- Standard ist Stufe 2. Die gewählte Stufe speichert `DataStoreZoomLevelStore` (`timeline_zoom`) und stellt sie beim Start wieder her. Eine Nutzerwahl hat Vorrang vor einer später eintreffenden Speicherantwort.
- Die Gruppierung wird nur bei Bedarf berechnet und je Art im ViewModel gehalten (`TimelineGroupingCache`). Ein Umschalten zwischen Tagen und Monaten rechnet nicht erneut. Der Cache verfällt, wenn sich die Liste (Identität) oder der Tag ändert. Die Berechnung läuft auf dem Default-Dispatcher.
- Die Ziel-Kachelbreite ist eine Untergrenze: Die tatsächliche Kachel ist wegen der 2-dp-Abstände geringfügig größer (`ZoomLevels.tileWidth`).

### Gesten-Umsetzung

- Zweifinger-Erkennung: Die Geste wertet in der **Initial-Phase** aus, also vor dem Raster. Erst mit zwei Fingern wird das Ereignis konsumiert. Ein Finger scrollt und fliegt wie bisher. Verlässt ein Finger den Bildschirm, endet die Geste.
- Visuelles Feedback: Das Raster wird per `graphicsLayer` skaliert und verschoben. Die Werte (Skalierung, Ankerpunkt, Verschiebung) liest nur die Layer-Lambda. Jeder Frame zeichnet die Ebene neu, es gibt keine Recomposition.
- Schwellen: Das kumulative Verhältnis des Fingerabstands seit der letzten Basis. Ab 1,3 (auseinander) eine Stufe größere Kacheln, ab 0,77 (zusammen) eine Stufe kleinere. Danach wird die Basis zurückgesetzt, damit mehrere Stufen in einer Geste möglich sind.
- Kontinuität der Kachelgröße: Beim Wechsel wird die Skalierung mit dem Verhältnis der Kachelbreiten multipliziert (alt zu neu). Die Kacheln behalten damit ihre Größe auf dem Bildschirm, sie springen nicht.
- Ränder: Auf Stufe 0 und 4 wird die Bewegung in die Richtung über den Rand gedämpft (Faktor 0,25), es gibt keinen Wechsel.
- Rückführung: Nach dem Loslassen geht die Skalierung mit einer gedämpften Feder (`DampingRatioNoBouncy`, `StiffnessMediumLow`) auf 1 zurück, die Verschiebung auf 0.

### Anker-Verfahren

- Anker ist die Medien-Zelle unter der Fingermitte. Liegt die Mitte in keiner Zelle, nimmt das Verfahren die nächstliegende Medien-Zelle. Überschriften sind keine Anker. Die Zuordnung liefert `ZoomAnchor.pick` aus `layoutInfo.visibleItemsInfo`.
- Der Anker wird über seine **URI** (Schlüssel) wiedergefunden. Nach dem Wechsel liegt der Punkt, der vorher unter der Fingermitte war, wieder unter ihr: Die Oberkante der Zelle ist `Fingermitte − Anteil × neue Kachelgröße` (`ZoomAnchor.targetTop`). Der Anteil ist die Lage des Punkts innerhalb der alten Zelle.
- Die Zielposition wird als `requestScrollToItem(index, paddingTop − Ziel)` in der **Komposition** gesetzt (`SideEffect`), im selben Frame wie das neue Raster. So gibt es kein Zwischenbild mit altem Layout und neuer Transformation.
- Index und Position: `TimelineIndex` bildet Banner, Überschriften und Medien auf die flache Reihenfolge des Rasters ab. Die Zuordnung Medium → Position entsteht erst beim Anker (lazy).
- Horizontal folgt der Anker dem Spaltenumbruch und bleibt nicht an seiner x-Position. Der vertikale Abstand ist der Maßstab des Auftrags („möglichst“ an derselben Position).

### Schnell-Scrollleiste

- Erscheint nur, wenn der Inhalt mehr als drei Bildschirmhöhen hat. Die Höhe schätzt `FastScrollMapping.isUseful` aus Zellenzahl, Spalten und Zellhöhe.
- Am Ende-Rand, RTL-fähig (`Alignment.CenterEnd`, Abstände in `end`), mit System-Insets (`safeDrawing`). Der sichtbare Griff ist 4 dp breit und 48 dp hoch, die Berührungsfläche 48 dp breit über die volle Höhe.
- Sichtbarkeit: beim Scrollen und Ziehen an, nach 1.500 ms Ruhe ausgeblendet (animierte Deckkraft).
- Mapping: Anteil ↔ Index über `layoutInfo.totalItemsCount`, also einschließlich Überschriften und Banner. Die Funktionen liegen in `FastScrollMapping`.
- Drosselung: Während des Ziehens springt das Raster per `scrollToItem`. Ein `conflate` lässt nie mehr als einen Sprung laufen, der neueste Wert gewinnt. Bei 50.000 Einträgen wird so nicht jeder Zwischenwert gerendert.
- Blase beim Ziehen: Auf Tagesstufen der Monat („Oktober 2026“), auf Monatsstufen das Jahr („2026“). Beide Texte entstehen mit den Formatierern der Überschriften (`TimelineFormats.scrollLabel`).
- Haptik: `HapticFeedbackType.SegmentTick`, nur wenn die Blase den Monat oder das Jahr wechselt, nicht beim Beginn des Ziehens. Die Haptik läuft über die View-Schnittstelle und braucht keine neue Berechtigung (kein `VIBRATE` nötig).

### Barrierefreiheit

- Bedienungshilfen „Ansicht vergrößern“ und „Ansicht verkleinern“ (`CustomAccessibilityAction`) wirken wie der Pinch. Der Anker ist die Zelle in der Raster-Mitte, die Ansicht bleibt ohne Transformation. An der Grenze geben die Aktionen `false` zurück.
- Die Stufe ist per `stateDescription` hörbar, als Plural: „4 Spalten“ bzw. „4 columns“ (`feature_gallery_zoom_columns`).
- Die Schnell-Scrollleiste meldet sich als Schieberegler (`progressBarRangeInfo`) mit `setProgress`. Damit springt auch TalkBack an eine Position.

### Tests und Goldens

- Reine Logik: Stufen und Spalten für 412, 673 und 900 dp, Grenzen und Dämpfung, Anker mit Tag→Monat-Wechsel, Scroll-Mapping in beide Richtungen und an den Rändern, Index und Datumsanker, Gruppierungs-Cache (Umschalten rechnet nicht neu).
- ViewModel: Persistenz und Begrenzung der Stufe, Vorrang der Nutzerwahl.
- Robolectric: Pinch nach außen und innen, Grenzen ohne Wechsel, Ein-Finger-Scrollen, Bedienungshilfen, Ziehen der Leiste mit Blasentext für Monat und Jahr, DataStore-Persistenz.
- Goldens (`src/test/screenshots`): Handy Stufe 0 (2 Spalten, Tage) und Stufe 4 (7 Spalten, Monate), Innendisplay 673 × 841 dp auf der Standardstufe, Handy mit gehaltenem Griff und Blase. Das Tablet-Golden ist neu aufgenommen, weil die Spalten jetzt der Breite folgen (10 statt 4 Spalten auf Standardstufe). Die übrigen Handy-Goldens sind unverändert, sie haben weiterhin 4 Spalten.

### Offene Punkte für die Gerätemessung

- Gefühl des Pinch: Schwellen 1,3 und 0,77, Dämpfung 0,25 und die Feder der Rückführung auf echter Hardware prüfen.
- Anker: vertikale Position nach dem Wechsel auf echtem Gerät messen, horizontal ist der Versatz beim Spaltenumbruch zu erwarten.
- Schnell-Scrollleiste bei 50.000 Einträgen: Ruckeln beim Ziehen, Drosselung und Haptik-Qualität (`SegmentTick` ist Gerätesache).
- Zielbreiten 180/120/90/72/52 dp: ob die Stufen auf dem Gerät als sinnvoll abgestuft wirken.
- Start mit gespeicherter Stufe: Der Zustand wartet auf die erste Antwort des Speichers, wie bei der Berechtigungs-Abfrage. Ob die Wartezeit auf dem Gerät spürbar ist, ist zu messen.
- Stufenwechsel per Pinch bei sehr großen Bibliotheken: Dauer der Gruppierung im Default-Dispatcher beim ersten Wechsel auf die andere Art.

## Quellen

- Coil 3 (Fetcher, Mapper, `diskCachePolicy`, coil-test): https://coil-kt.github.io/coil/
- ContentResolver.loadThumbnail (ab API 29): https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)
- Ausgewählte Fotos und Videos ab Android 14: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- DataStore Preferences: https://developer.android.com/topic/libraries/architecture/datastore
- ADR 0006 (Screenshot-Tests, Coverage), ADR 0007 (Medienzugriff).
