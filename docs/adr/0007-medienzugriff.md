# ADR 0007: Medienzugriff (MediaStore)

Status: angenommen, 16.10.2026

## Kontext

Meilenstein 2 bringt Zeitleiste, Alben und Vollbild. Die Oberfläche braucht eine Datenschicht, die alle Medien leicht und vollständig bereitstellt, ohne Bilddaten zu laden. Die Zeitleiste zeigt Datums-Überschriften, Pinch-Zoom verankert am Datum und einen Fast-Scroller. Dafür braucht sie die Gesamtliste mit allen Zeitstempeln, nicht nur eine Seite.

Zu klären waren:

- wie der Index gehalten wird (Paging oder Liste im Speicher),
- welche MediaStore-Collections abgefragt werden,
- wie der effektive Zeitstempel bestimmt wird,
- wie Änderungen erkannt werden,
- welches Berechtigungsmodell gilt.

## Entscheidung

### In-Memory-Index statt Paging 3

Die App fragt nur leichte Metadaten ab (keine Bilddaten) und hält den Index als Liste im Speicher.

- Datums-Überschriften, Pinch-Zoom mit Anker und Fast-Scroller brauchen die Gesamtliste, ein Paging-Modell macht sie aufwendig.
- Die Abschätzung pro Eintrag: ein `MediaItem` belegt etwa 300 bis 500 Byte auf dem Heap (Schätzung aus Objekt-Overhead, `String`-Feldern und geboxten Zahlen, nicht auf dem Gerät gemessen). Bei 50.000 Einträgen sind das grob 15 bis 25 MB. Das liegt im Rahmen eines Android-Heaps. Zu groß wird es erst, wenn Bilddaten oder Bitmaps dazukommen, und die gehören nicht in den Index.
- Ein Eintrag ist klein genug, weil `uri` ein String ist und die Alben nur einen Verweis auf das Cover halten. Mögliche Reduktion bei Bedarf: Strings wie `mimeType` oder `volumeName` über eine kleine Intern-Tabelle zusammenlegen.

Der Aufwand ist O(n log n) für das Sortieren je Collection und O(n) für das Zusammenführen. Der Test `MediaIndexPerformanceTest` prüft das über das Verhältnis zweier Größen.

### Getrennte Collections, nicht MediaStore.Files

- Bilder aus `MediaStore.Images.Media` und Videos aus `MediaStore.Video.Media`, jeweils getrennt abgefragt.
- `MediaStore.Files` zeigt unter Scoped Storage nur eigene Dateien. Das scheidet aus.
- Ab API 29 `getContentUri(MediaStore.VOLUME_EXTERNAL)`, also alle externen Volumes. Davor `EXTERNAL_CONTENT_URI`.
- Die beiden Ergebnisse werden je nach effektivem Zeitstempel zu einer Liste zusammengeführt. Die Ordnung ist total: absteigend nach Zeitstempel, bei Gleichstand Typ (Bild vor Video), dann ID aufsteigend.

### Effektiver Zeitstempel

Reihenfolge der Quellen:

1. `DATE_TAKEN` in Millisekunden, falls größer als 0.
2. Sonst `DATE_MODIFIED` in Sekunden mal 1000, falls größer als 0.
3. Sonst `DATE_ADDED` in Sekunden mal 1000.

Die Quelle wird als `TimestampSource` mitgeführt, für Anzeige und Fehlersuche.

### Ausschlüsse

- **Pending:** ab API 29 `is_pending = 0` in der Selektion. Einträge, die gerade geschrieben werden, erscheinen nicht.
- **Papierkorb:** ab API 30 ist er standardmäßig ausgeblendet. Die MediaStore-Doku sagt dazu: „By default, trashed items are filtered away from operations.“ Die App setzt keine eigene Bedingung, `QUERY_ARG_MATCH_TRASHED` wird nicht verwendet.

### Beobachtung

- Ein `ContentObserver` (`notifyForDescendants = true`) auf beide Collections, als `callbackFlow`.
- Entprellung 400 ms. Eine Welle von Änderungen löst also eine Abfrage aus.
- Ab API 30 liest die App vor jeder Abfrage `MediaStore.getGeneration(context, VOLUME_EXTERNAL)`. Ändert sich die Generation nicht, entfällt die Abfrage. Die Generation wird vor der Abfrage gelesen, damit eine Änderung während der Abfrage eine weitere Runde auslöst.
- `refresh()` erzwingt eine Abfrage ohne Prüfung der Generation. Die UI ruft sie bei der Rückkehr aus den Systemeinstellungen auf (Paket 3).
- Alle Auslöser laufen durch eine einzige Sammel-Coroutine. Abfragen laufen nie parallel.

### Berechtigungsmodell

Drei Stufen, `MediaAccess`:

- **FULL:** alle Medien lesbar.
- **PARTIAL:** nur vom Nutzer ausgewählte Medien (ab Android 14).
- **NONE:** kein Zugriff.

Regeln in `mediaAccess(sdkInt, granted)`, gespiegelt an der Doku zu „ausgewählten Fotos“:

- API 33 und höher, `READ_MEDIA_IMAGES` oder `READ_MEDIA_VIDEO` gewährt: FULL.
- API 34 und höher, nur `READ_MEDIA_VISUAL_USER_SELECTED`: PARTIAL.
- API 32 und darunter, `READ_EXTERNAL_STORAGE` gewährt: FULL.
- Sonst NONE.

Offene Lücke: Gewährt der Nutzer ab API 33 nur einen der beiden Typen, gilt das als FULL. Der fehlende Typ fehlt dann still. Die App fordert immer beide an. Paket 3 muss das erkennen und nachfragen. Eine eigene Stufe dafür gibt es nicht.

Berechtigungen im Manifest von `core:media`: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` und `READ_EXTERNAL_STORAGE` mit `maxSdkVersion="32"`. Kein `WRITE_EXTERNAL_STORAGE`, kein `MANAGE_EXTERNAL_STORAGE`, kein `ACCESS_MEDIA_LOCATION`.

### Fehlerverhalten

- `SecurityException` (keine oder entzogene Berechtigung) führt zu einer leeren Liste mit `mediaAccess = NONE`. Die App stürzt nicht ab und protokolliert nichts, das ist kein Fehler.
- Ist die Berechtigung nach der Prüfung nicht mehr gewährt, erzwingt die Abfrage per `SecurityException` die gleiche Antwort.
- Andere Fehler werden protokolliert, der letzte gute Zustand bleibt erhalten. Die Sammel-Coroutine läuft weiter.

### Modelle und Compose

- `MediaItem`, `Album` und `MediaLibrary` sind Datenklassen mit `@Immutable` aus `androidx.compose.runtime`. Für die Stabilität im UI-Layer reicht die Runtime-Bibliothek. Sie enthält keine UI-Abhängigkeiten und keine Compiler-Plugins.
- Die Content-URI ist ein `String`. `android.net.Uri` wäre für Coil direkter, würde aber die Modelle auf der JVM unbrauchbar machen, weil `Uri` in JVM-Tests nur als Stub existiert. Die UI ruft `Uri.parse(item.uri)` auf.
- `MediaLibraryState` ist eine sealed-Schnittstelle mit `Loading` und `Ready`. Der Ladezustand ist damit von „keine Medien“ unterscheidbar.

### Hilt und Dispatcher

- `MediaRepository` mit `@Binds` auf `MediaStoreMediaRepository` (Singleton).
- Der IO-Dispatcher kommt über den Qualifier `@IoDispatcher` aus einem kleinen Modul. Tests setzen dafür einen `StandardTestDispatcher` mit virtueller Zeit ein.

### Konsequenz für Robolectric

Library-Module erzeugen das Test-Manifest nur, wenn `isIncludeAndroidResources` gesetzt ist. Ohne das lief Robolectric in `core:media` auf SDK 23 statt auf dem Ziel-SDK 37, drei Gateway-Tests schlugen fehl. Das Flag gilt jetzt zentral für alle Android-Module über `configureUnitTestResources()` in der Convention. Die Messung und die Screenshot-Prüfung stehen in ADR 0006.

## Konsequenzen

- Der Index liegt vollständig im Speicher. Bei sehr großen Bibliotheken (deutlich über 50.000) wird das neu bewertet.
- Die Zeitleiste ist deterministisch, weil die Ordnung total ist.
- Die Berechtigungslücke bei nur einem Typ (siehe oben) muss in Paket 3 gelöst werden.
- `MediaColumnNames` nutzt Strings für Spalten ab API 29, weil die Konstanten dort in `MediaColumns` liegen und vor API 29 in anderen Interfaces. Die Werte sind identisch. Der Robolectric-Test mit Fake-Provider prüft Projektion und Mapping gegen diese Namen. Auf einem Gerät ist die Abfrage noch nicht geprüft (offener Punkt).

## Alternativen

- **Paging 3:** verworfen. Die Zeitleiste braucht die Gesamtliste, Paging würde Datums-Überschriften, Anker und Fast-Scroller erschweren.
- **`MediaStore.Files` mit einer gemeinsamen Abfrage:** verworfen, siehe oben.
- **Sortierung im Provider per `ORDER BY`:** verworfen. Der effektive Zeitstempel ist eine Fallback-Kette. Ob der MediaProvider einen solchen Sortierausdruck zulässt, ist nicht belegt. Die Sortierung passiert im Code und ist dort testbar.
- **Polling statt Beobachtung:** verworfen. Der ContentObserver ist die vorgesehene Schnittstelle, Polling kostet unnötig Akku.
- **Eine Abfrage pro Änderung ohne Entprellung:** verworfen. Eine Kamera-Aufnahme löst mehrere Änderungen aus.
- **`android.net.Uri` im Modell:** verworfen, siehe oben.

## Quellen

- MediaStore-Referenz (Konstanten und Methoden): https://developer.android.com/reference/android/provider/MediaStore
- Ausgewählte Fotos und Videos ab Android 14: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Verifiziert gegen die Stubs und `api-versions.xml` der lokalen Plattform 37.0 (API-Level der Konstanten, `getGeneration` ab 30, `VOLUME_EXTERNAL` ab 29, Papierkorb-Standardverhalten laut Javadoc).
- Kotlin-Coroutines 1.11.0, Turbine 1.2.1 (Maven Central).
