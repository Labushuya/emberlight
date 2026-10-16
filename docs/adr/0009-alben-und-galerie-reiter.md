# ADR 0009: Alben und Reiter der Galerie

Status: angenommen, 16.10.2026

## Kontext

Paket 4 aus Meilenstein 2 bringt Reiter Fotos und Alben in der Galerie, die Alben-Übersicht und die Album-Ansicht. Die Album-Ansicht soll dieselben Bausteine nutzen wie die Zeitleiste (ADR 0008): Raster, Gruppierung, Pinch-Zoom, Schnell-Scrollleiste, Berechtigungs- und Leerzustände. Zu klären waren:

- wie der Wechsel zwischen Reitern funktioniert, ohne mit Gesten des Rasters zu kollidieren,
- wie Reiter und Scrollposition beim Wechsel des Ziels erhalten bleiben,
- wie Alben sortiert werden und wo das Sammelalbum „Sonstige“ steht,
- wie die Album-Ansicht typsicher adressiert wird,
- wie die Zoomstufe zwischen Galerie und Album geteilt wird.

## Entscheidung

### Reiter nur per Antippen

- Die Reiter sind eine `SecondaryTabRow` mit zwei `Tab`s. Der Wechsel geht nur per Antippen. Es gibt keinen `HorizontalPager` und kein Wischen zwischen den Reitern.
- Begründung: Der Reiter-Wechsel per Wischen würde mit drei Gesten um dieselbe Bewegung konkurrieren. Das Raster braucht Wischen für Scrollen und Zweifinger-Zoom (ADR 0008), die Schnell-Scrollleiste braucht Ziehen am Ende-Rand, und das Vollbild (Paket 5) braucht Wischen für Medien. Ein Pager am Rand hätte Wischen doppelt belegt.
- Die Reiterleiste bleibt sichtbar, wenn die Top-App-Bar beim Scrollen einklappt. Die Reiter sind die Navigation innerhalb der Galerie. Sie sollen jederzeit erreichbar sein, auch wenn der Inhalt weit unten ist. Die Titelzeile klappt ein, damit der Inhalt mehr Platz bekommt.
- Der gewählte Reiter ist `rememberSaveable`. Die Scrollzustände beider Raster liegen im Screen, nicht im Reiter. So bleiben sie beim Wechsel des Reiters und beim Wechsel zu Spaces und zurück erhalten (die Navigation sichert den Zustand des Ziels, siehe ADR 0002).
- Titel der Galerie: „Galerie“ bzw. „Gallery“.

### Ein Zustand für beide Reiter

- `GalleryUiState` trägt den Rasterzustand (`grid`) und die Alben (`albums`). Laden, Berechtigungsanfrage, Leerzustand und der Hinweis-Banner (PARTIAL oder fehlender Medientyp) gelten für beide Reiter gemeinsam.

### Alben-Sortierung und „Sonstige“

- Die Alben erscheinen in der Reihenfolge des Index, also neuestes Element zuerst.
- Das Sammelalbum für Einträge ohne Ordner (`bucketId` ist `null`) steht immer zuletzt, auch wenn es zeitlich vorn liegt. Die Regel gilt für die Anzeige (`albumsForDisplay`), `core:media` bleibt unverändert.
- Anzeigename: „Sonstige“ bzw. „Other“. Ein Ordner ohne Namen heißt „Album ohne Namen“ bzw. „Unnamed album“.
- Anzahl als Plural mit Zahlenformat der Sprache („1.234 Elemente“, „1,234 items“).
- Karte: quadratisches Titelbild (das neueste Element) mit abgerundeten Ecken aus dem Theme, darunter der Name in einer Zeile. Ist das Titelbild ein Video, markiert ein Symbol das. Die Karte ist ein Button, ihre Beschreibung lautet etwa „Kamera, 1.234 Elemente“.
- Raster: `GridCells.Adaptive(160 dp)`. Auf dem Handy passen zwei Spalten, auf einem Faltgerät im Innendisplay (673 dp) drei, auf einem Tablet mehr.

### Album-Route

- `AlbumRoute(bucketId: Long?)` ist eine typsichere `@Serializable`-Route. Sie trägt nur die Ordner-ID. `null` steht für das Sammelalbum. Den Namen liest die Ansicht aus dem Index. Der Name kann sich ändern, die Route bleibt stabil.
- Öffentliche Navigation in `feature:gallery`: `NavGraphBuilder.albumScreen(onBack, onMediaClick)` und `NavController.navigateToAlbum(bucketId)`. `app` verdrahtet sie.
- Top-Level-Zuordnung: Ein Ziel der Navigationsleiste umfasst jetzt eine Liste von Routen (`TopLevelDestination.routes`). Die Galerie umfasst `GalleryRoute` und `AlbumRoute`, daher bleibt die Galerie in der Album-Ansicht markiert. Ein verschachtelter Navigationsgraph wurde verworfen, weil er für eine einzelne zusätzliche Route eine eigene Graph-Route braucht.
- Verschwindet das Album, etwa weil alle Einträge gelöscht wurden, meldet der Zustand `Gone`: „Dieses Album ist nicht mehr vorhanden.“ mit Zurück-Knopf.

### Gemeinsamer Baustein

Galerie und Album nutzen dieselben Teile:

- `MediaGrid` (Paket `timeline`): das Raster mit Gruppierung, Pinch-Zoom mit Anker, Schnell-Scrollleiste und Banner. Der Scroll-Zustand kommt vom Aufrufer.
- `MediaStateBody`: Laden, Berechtigung, Leerzustand, „Album fehlt“ und das Raster.
- `MediaGridState` mit `MediaScope` (`All` oder `Bucket(bucketId)`). Die Abbildung `mediaGridState(...)` ist für beide Ansichten gleich. Sie unterscheidet nur den Geltungsbereich.
- `MediaGridViewModel` als gemeinsame Basis. Sie hält Index, Berechtigungsmerkmale, Zoomstufe und den Gruppierungs-Cache. `GalleryViewModel` und `AlbumDetailViewModel` sind dünne Unterklassen.
- `MediaThumbnailImage` für Zellen und Albumkarten, `accessBannerItem` für den Banner, `rememberMediaPermissionRequest` für Systemdialog und Rückkehr aus den Einstellungen.
- Abweichung vom Auftrag: Der Auftrag sah ein eigenes `AlbumsViewModel` vor. Die Alben stehen stattdessen im Zustand der Galerie. Ein zweites ViewModel hätte Laden und Berechtigung doppelt gehalten. Der Zustand „ein State für beide Reiter“ ließ sich so direkt umsetzen.

### Geteilte Zoomstufe

- `ZoomLevelStore` ist ein Singleton. Die Nutzerwahl liegt im Speicher des Singletons und wird zusätzlich in DataStore geschrieben. Jede Ansicht liest denselben Wert. Ein Wechsel in der Album-Ansicht wirkt in der Galerie und umgekehrt, ohne Neustart.
- Die Regel „Nutzerwahl vor Gespeichertem“ liegt in der reinen Funktion `resolveZoomLevel`. Eine späte Antwort des Speichers überschreibt so keine Wahl.

### Gruppierungs-Cache beim Album

- Der Filter auf ein Album läuft einmal je Quelllistenobjekt (`ScopedItems`). Die gefilterte Liste ist dieselbe Instanz, solange der Index sich nicht ändert. Dadurch greift der Gruppierungs-Cache auch beim Wechsel der Zoomstufe im Album.

## Konsequenzen

- Ein Raster, eine Gruppierung und ein Zustandsrahmen. Änderungen am Raster wirken in Galerie und Album gleich.
- Die Galerie braucht für die Alben keine zweite Abfrage. Die Alben kommen aus dem Index, den `core:media` ohnehin baut.
- Die Anzeigeregel für „Sonstige“ liegt in `feature:gallery`. Ändert sich die Sortierung im Index, muss die Regel mitgeprüft werden (durch `AlbumOrderingTest`).
- Beim Filter eines großen Index läuft jede Änderung des Index einmal über alle Einträge (O(n)). Das ist bei 50.000 Einträgen auf dem Gerät zu messen (offen, siehe ADR 0007).
- Offen für den Gerätetest: Zweifinger-Zoom in der Album-Ansicht, Erhalt der Scrollposition beim Wechsel zu Spaces, Verhalten der Karten bei vielen Alben.

## Alternativen

- Reiter per `HorizontalPager` mit Wischen: verworfen, Konflikte mit Pinch-Zoom, Schnell-Scrollleiste und dem Vollbild-Wischen.
- Reiterleiste zusammen mit der Top-App-Bar einklappen: verworfen, die Reiter wären beim Scrollen nicht erreichbar.
- Verschachtelter Navigationsgraph für die Galerie: verworfen, siehe oben.
- Album-Name in der Route: verworfen, der Name kann veraltet sein und macht die Route unnötig groß.
- Eigenes `AlbumsViewModel`: verworfen, siehe oben.
- Zoomstufe je Ansicht: verworfen, der Auftrag verlangt eine geteilte Stufe.

## Quellen

- Navigation Compose, typsichere Routen mit `@Serializable` und `toRoute`: https://developer.android.com/guide/navigation/design/type-safety. Nullable Argumente (`Long?`) sind dort nicht beschrieben. Sie sind durch den Test `AlbumDetailViewModelTest` (Sammelalbum mit `null`) belegt.
- Ausgewählte Fotos und Videos ab Android 14: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- ADR 0002 (Navigation), ADR 0007 (Medienzugriff), ADR 0008 (Zeitleiste).
