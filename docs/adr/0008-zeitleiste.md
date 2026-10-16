# ADR 0008: Zeitleiste der Galerie

Status: angenommen, 16.10.2026

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

- **Pinch-Zoom der Spalten mit Anker:** Die Spaltenzahl kommt über `onColumnsChanged` in das ViewModel und regruppiert dort. Offen ist, wie der sichtbare Datums-Anker beim Zoomwechsel erhalten bleibt. Vorschlag: Index des ersten sichtbaren Elements vor dem Wechsel merken und danach über `scrollToItem` wiederfinden.
- **Schnell-Scrollleiste:** Die Gruppenschlüssel sind stabil und eindeutig, damit eine Leiste pro Monat oder Tag darauf aufsetzen kann. Offen sind Gestaltung und Verhalten bei sehr vielen Gruppen.
- **Adaptive Spaltenzahl:** Breitenstufen (Tablet quer) mit Monatsgruppen ab 5 Spalten. Dazu Test auf dem Gerät.
- **Speicherbedarf des Index bei großen Bibliotheken** (ADR 0007, Schätzung nicht gemessen).

## Quellen

- Coil 3 (Fetcher, Mapper, `diskCachePolicy`, coil-test): https://coil-kt.github.io/coil/
- ContentResolver.loadThumbnail (ab API 29): https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)
- Ausgewählte Fotos und Videos ab Android 14: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- DataStore Preferences: https://developer.android.com/topic/libraries/architecture/datastore
- ADR 0006 (Screenshot-Tests, Coverage), ADR 0007 (Medienzugriff).
