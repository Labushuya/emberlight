# ADR 0022: Übernahme in die Galerie

Status: Umgesetzt mit den Abweichungen unten, 22.10.2026 (Paket 3.7). Gerätetests offen (Testkatalog Abschnitt 11).

## Kontext

Der Gastgeber soll ausgewählte Medien eines Space in die Galerie holen. Das ist der einzige Weg, auf dem Space-Medien
die App-internen Space-Speicher verlassen (CLAUDE.md, „Strikte Trennung von Space und Galerie“). Die Galerie darf dabei
nicht vom Space abhängen (`checkGalleryNoSpaceDependency`). Die Space-Module dürfen keine Galerie-Zugriffe im Quelltext
haben (`checkSpaceSources`). Die Übernahme braucht deshalb eine eigene Schreibseite.

## Entscheidungen

### Auswahl

- **Vorauswahl nichts.** „Alle“ wählt alle Medien aus, die noch nicht übernommen sind. „Nichts“ hebt die Auswahl auf.
- Anzeige von Anzahl und Gesamtgröße der Auswahl. Medien mit „Standort enthalten“ sind gekennzeichnet.
- Bereits übernommene Medien sind mit „Bereits übernommen“ markiert und lassen sich nicht erneut wählen (`ExportSelection`).
- Der Einstieg liegt im Space-Detail bei laufenden und beendeten Spaces. Bei `DELETED` und während der Löschung fehlt er.

### Ziel und Namen

- Ziel ist `Pictures/Emberlight/<Space-Name>/`. Der Ordnername wird bereinigt (verbotene Zeichen und Steuerzeichen zu
  `_`, Leerraum zusammengezogen, Punkte und Leerzeichen am Rand entfernt, höchstens 80 Zeichen, leer wird „Space“).
- Der Ordner wird beim ersten Übernehmen festgelegt und in `space_export` gespeichert. Ist der Name durch einen anderen
  Space oder durch einen Ordner aus einer früheren Übernahme belegt, heißt er „<Name> (2)“, „(3)“ usw.
- Dateinamen behalten den Originalnamen ohne Pfadanteile. Kollisionen im Ordner ergeben „<Name> (2).<Endung>“.
- **Videos gehören in denselben Ordner.** Die Android-Dokumentation „Shared storage: Media“ führt `Pictures/` ausdrücklich
  als Verzeichnis für Videos auf (neben `DCIM/` und `Movies/`). Videos gehen in die Video-Tabelle des Medienspeichers.

### Kopie und Hash-Prüfung

- Kopiert wird die **gespeicherte Space-Datei**. Das ist die bereinigte Fassung bzw. die mit Einwilligung (ADR 0015).
  Der Hash im Vermerk der Space-Datei ist der Hash des gespeicherten Inhalts.
- Jede Kopie wird nach dem Schreiben zurückgelesen und per SHA-256 mit dem Hash der Space-Datei verglichen.
- Bei Abweichung oder Fehler wird die Kopie verworfen und als Fehler gemeldet. Auf Android 10 und neuer ist die Kopie bis
  zur Prüfung ausstehend (`IS_PENDING = 1`). Verworfen wird per Löschen des Medieneintrags. Vor Android 10 wird die Datei
  gelöscht, die die App selbst angelegt hat.
- Fehler je Datei brechen den Lauf nicht ab. Die Ergebnisliste nennt übernommen, übersprungen und Fehler mit Grund
  (Abweichung, Schreibfehler, fehlende Space-Datei, anderer Fehler).
- **Aufnahmedatum.** Der Space speichert nur den Zeitpunkt des Hochladens. Das ist nicht das Aufnahmedatum. Übernommen wird
  das EXIF-Feld `DateTimeOriginal` der gespeicherten Datei, wenn es lesbar ist. Sonst bleibt `DATE_TAKEN` leer, und das
  System setzt das Datum des Hinzufügens. Videos haben in diesem Stand kein Aufnahmedatum.

### MediaStore-Wege

| Android | Weg |
|---|---|
| 10 und neuer (API 29+) | Medienspeicher: `RELATIVE_PATH = Pictures/Emberlight/<Ordner>/`, `IS_PENDING = 1` bis zur Prüfung, dann `0`. Bilder in der Bilder-Tabelle, Videos in der Video-Tabelle. Keine Berechtigung nötig, weil die App nur ihre eigenen Einträge bearbeitet. |
| 8 bis 9 (API 26 bis 28) | Direkte Datei unter `Pictures/Emberlight/<Ordner>/`, danach Medienindex (`MediaScannerConnection`). `WRITE_EXTERNAL_STORAGE` mit `maxSdkVersion="28"` im Manifest von `share:export`. Die Galerie (`core:media`) bekommt die Berechtigung nicht. |

### Platz

- Vor dem Start wird der Platz geprüft: Summe der Dateien plus **2 GiB Mindest-Restspeicher** (ADR 0013). Die Reserve bleibt
  nach dem Schreiben frei, damit das System und andere Apps nicht ausgebremst werden. Reicht der Platz nicht, wird nichts
  geschrieben und die Meldung nennt benötigten und freien Platz.
- Der freie Platz wird auf dem Datenträger von `getExternalFilesDir` gemessen, also dem Datenträger von `Pictures/`.

### Doppelte vermeiden

- Tabelle `export_media` mit einem Vermerk je übernommenem Medium (Schema 10 → 11, additiv). Eine erneute Übernahme
  überspringt es ohne Kopie. Der Vermerk bleibt auch dann, wenn die Kopie in der Galerie später gelöscht wird. Er sagt,
  dass dieses Medium übernommen wurde, nicht, dass die Kopie noch da ist.

### Robustheit: Hintergrundauftrag

- Die Übernahme läuft als WorkManager-Auftrag mit Vordergrund-Benachrichtigung (`dataSync`). So läuft sie weiter, wenn die
  App verlassen wird. Eine Coroutine im Vordergrund würde mit dem Bildschirm beendet. WorkManager liefert den Auftrag
  zusätzlich nach einem Neustart erneut. Benötigt werden `FOREGROUND_SERVICE_DATA_SYNC` (Android 14) und der Dienst von
  WorkManager mit dem Typ `dataSync` im Manifest von `feature:space`.
- Ein Auftrag je Space (`KEEP`). Die Auswahl liegt in einer Datei im App-Speicher, weil WorkManager Eingaben auf 10 KB
  begrenzt. Der Worker liest nur Dateien aus seinem eigenen Verzeichnis und entfernt sie am Ende.
- Fortschritt (Dateien und Bytes) wird je fertiger Datei und je MiB gemeldet. Die Benachrichtigung hat die Aktion „Abbrechen“.
- Abbruch: Die Coroutine wird beendet. Die gerade angelegte Kopie wird außerhalb des Abbruchs verworfen (`NonCancellable`). Fertige Kopien
  bleiben, mit Vermerk.

### Löschung und Übernahme schließen sich aus

- `SpaceExportGate` hält je Space eine Sperre im Prozess (`SpaceExportGate.shared`). Die Übernahme hält sie für den ganzen
  Lauf. `SpacePurge` setzt zuerst das Merkmal `purging` und wartet dann auf die Sperre.
- Die Übernahme prüft `purging` vor jeder Datei. Sie bricht danach sauber ab. Die Löschung wartet höchstens auf die
  laufende Datei.
- Startet eine Übernahme bei `purging` oder `DELETED`, wird nichts geschrieben (`NotExportable`).
- **Kopien in der Galerie liegen außerhalb des Space.** `SpacePurge` löscht nur den Space-Ordner, Medien, Teilnehmer, Pausen
  und den Ordner-Eintrag. Die Schreibseite ist nicht Teil der Löschung. Test `ExportPurgeInteractionTest`:
  Die fertige Kopie bleibt erhalten, sie wird nicht verworfen.
- Die Vermerke `export_media` und `space_export` des Space werden mit dem Space gelöscht. Die Kopien bleiben.

### Erinnerung „Ansehen“

- Die Aktion „Ansehen“ der Aufbewahrungs-Erinnerung (ADR 0021) öffnet die Auswahl des Space (`retention_export/<id>`).
  Das Tippen auf die Benachrichtigung öffnet weiterhin das Detail.

## Modulstruktur

- `share:space`, Paket `export`: Regeln (Namen, Auswahl, Platz), Vermerke (`ExportLedger`), Gate (`SpaceExportGate`) und die
  Übernahme (`SpaceExporter`) über die Schnittstelle `ExportSink`. Ohne Android-Medienzugriff (`checkSpaceSources` bleibt grün).
  Die Aufnahmezeit liest `CaptureTime` mit `ExifInterface`.
- `share:export` (neu): `MediaStoreExportSink` implementiert `ExportSink` für Android 10 und neuer und für die Vorstufe.
  Hängt nur von `share:space` ab. Die Galerie hängt nicht davon ab.
- `feature:space`, Paket `export`: Worker, Auftragswarteschlange, ViewModel, Bildschirm.
- Die Galerie (`core:media`, `feature:gallery`) hängt von keinem dieser Module ab. Das prüft `checkGalleryNoSpaceDependency`.

## Abweichungen und offene Punkte

- **Keine Vorschaubilder im Raster.** Die Auswahl zeigt Art, Größe und Kennzeichen, aber noch keine Bilder. Die Vorschauen
  aus 3.5a-2 könnten genutzt werden. Das ist offen, weil es die Oberfläche vergrößert.
- **Kein eigener Isolationsfilter für `share:export`.** Die Abhängigkeitsregel ist mit dem Modul nur durch Konvention
  festgelegt (nur `share:space` darunter). Eine Build-Prüfung wie `checkSpaceIsolation` ist noch nicht gebaut.
- **Verwaiste ausstehende Einträge nach einem Absturz.** Bricht der Prozess zwischen Anlegen und Freigabe ab, bleibt ein
  Eintrag mit `IS_PENDING = 1` zurück. Android räumt solche Einträge nach einer Frist auf. Eine Bereinigung beim Start
  fehlt in diesem Stand.
- **Vermerk ohne Kopie.** Löscht die Nutzerin bzw. der Nutzer die Kopie in der Galerie, bleibt der Vermerk. Eine erneute
  Übernahme derselben Datei ist dann nicht möglich. Eine Prüfung gegen den Medienspeicher wäre möglich, ist aber nicht
  gebaut.
- **Videos ohne Aufnahmedatum.** Das Datum eines Videos ist nicht gespeichert und nicht ausgelesen.
- **Robolectric.** Der Medienspeicher ist ein Fake (`FakeMediaStoreProvider`). Der freie Platz wird dort nicht gemessen. Der
  Weg vor Android 10 hat keinen Test. Das prüft der Gerätetest (Testkatalog 11.12).
- **Goldens der Auswahl** (`SpaceExport_phone_de`, `SpaceExport_phone_en`, `SpaceExport_tablet_de`) sind aufgenommen und
  visuell geprüft. Die Auswahl ist dafür als zustandslose Oberfläche `SpaceExportContent` getrennt.
- **Touch-Tests** auf dem aufgeklappten Faltdisplay (`w673dp-h728dp`) und dem breiten Display (`w840dp-h900dp`):
  „Alle“, „Nichts“, „Übernehmen“ werden per `performTouchInput { click() }` ausgelöst. Der Fehler „Space starten“ auf dem
  Faltdisplay wird nicht hier behoben, nur das eigene UI ist abgesichert.

## Tests

- `share:space`: `ExportNamesTest`, `ExportSelectionTest` (inkl. Platzregel), `CaptureTimeTest`, `SpaceExporterTest`
  (Hash-Prüfung, Duplikate, Platz, Fehler je Datei, Abbruch, Pfadfehler, Zustände des Space), `ExportPurgeInteractionTest`
  (Löschung wartet auf die laufende Kopie, Kopie bleibt), `SpacesDatabaseMigrationToVersionElevenTest`.
- `share:export`: `MediaStoreExportSinkTest` (ausstehend bis zur Freigabe, Videos, Verwerfen, Medientyp), `MediaStoreExportEndToEndTest`.
- `feature:space`: `SpaceExportViewModelTest` (Vorauswahl, Alle und Nichts, bereits übernommen, gelöscht),
  `SpaceExportWorkerTest` (Eingaben, fremde Pfade, eigene Auswahl wird entfernt), `RetentionExportRouteTest`.
- Goldens: `SpaceDetailEnded` (de, en) und `SpaceRetentionDetail` (de, en) wurden wegen des neuen Einstiegs neu aufgenommen.

## Konsequenzen

- Die Übernahme ist die einzige Stelle, an der Space-Medien in die Galerie gelangen. Der Weg ist durch die Auswahl, die
  Hash-Prüfung und die Sperre gegen die Löschung begrenzt.
- `SpacePurge` bekommt einen Parameter für die Sperre. Die Löschung wartet damit auf eine laufende Übernahme.
- Die Datenbank ist Version 11.
