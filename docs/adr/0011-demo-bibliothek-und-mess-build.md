# ADR 0011: Demo-Bibliothek und Mess-Build

Status: angenommen, 17.10.2026.

## Kontext

Gerätetests (Gesten, Animationen, Bildschirmfotos, Messungen) laufen bisher mit der echten Bibliothek des Nutzers. Dabei entstehen
Aufnahmen privater Fotos, die in Berichte, Goldens oder Issues wandern könnten. Gleichzeitig braucht eine Messung einen Build, der
Release-nah optimiert ist, aber mit der Demo-Bibliothek arbeitet. Zu klären waren:

- wie die Testdaten entstehen, ohne Dateien auf dem Gerät anzulegen,
- wie der Demo-Modus aktiviert und angezeigt wird,
- wie sichergestellt wird, dass Release-Builds nichts davon enthalten,
- welcher Build für Messungen dient.

## Entscheidung

### Synthetische Bibliothek

- `app/src/demo/kotlin/.../demo/SyntheticLibrary.kt` erzeugt die Einträge als reine Funktion des Index. Dieselbe Bibliothek
  entsteht bei jedem Start, ohne Zufall und ohne Wanduhr (Bezugsdatum fest).
- 20.000 Einträge über drei Jahre, streng absteigend sortiert wie der Index. Etwa jeder zehnte Eintrag ist ein Video mit Dauer
  zwischen 5 s und 305 s. Zwölf Alben. Vier Seitenverhältnisse (hoch, quer, quadratisch, Panorama).
- Vorschaubilder entstehen im Speicher: ein Farbverlauf je Eintrag, darauf Nummer und Datum. Der Fetcher bedient nur die
  synthetischen URIs (`emberlight-demo://media/<id>`) und ist vor den Medien-Thumbnails eingetragen.
- Videos im Demo-Modus haben nur ein Standbild. Ein Abspielen liefert keine Datei. Eine Meldung dafür ist offen, siehe unten.
- Tests in `app/src/test/.../demo/SyntheticLibraryTest.kt`: Determinismus, Reihenfolge, Zeitraum, Videoanteil, zwölf Alben,
  vier Formen, URI-Umkehrung.

### Aktivierung

- Start-Intent: `adb shell am start -n io.github.labushuya.emberlight.debug/io.github.labushuya.emberlight.MainActivity --ez emberlight.demo true`.
  Ausschalten mit `--ez emberlight.demo false`.
- Der Schalter liegt in DataStore (`demo_mode`) und bleibt bis zum Ausschalten gespeichert.
- Die Activity ruft alle `LaunchIntentHandler` bei jedem Start und jedem neuen Intent auf. Der Demo-Beitrag liest den Extra.
- `SwitchingMediaRepository` wählt zur Laufzeit: Ist der Schalter an, folgt der Index der Demo-Quelle, sonst dem MediaStore.
  Ein Wechsel wirkt ohne Neustart. Die Berechtigungsabfrage wird im Demo-Modus nicht gestellt, weil der Index Vollzugriff meldet.
- Im Demo-Modus zeigt ein Hinweis „DEMO“ oben rechts (`DemoBadge`, Testmarke `demo-badge`). Der Hinweis ist ein `AppOverlay`
  und taucht in Goldens nicht auf, weil die Tests ohne Beiträge laufen.

### Source-Sets und Erweiterungspunkte

- Der Demo-Code liegt in `app/src/demo`. Das Source-Set gehört zu `debug` und `benchmark`, nie zu `release`
  (`sourceSets` in `app/build.gradle.kts`).
- Der Kern in `core:media` kennt nur die Schnittstelle `AlternativeMediaSource`. Die Mehrfachbindung `Set<AlternativeMediaSource>` ist
  leer, wenn kein Source-Set sie füllt. Dasselbe Muster haben `CoilComponents`, `LaunchIntentHandler` und `AppOverlay` in `app`.
- Die Erweiterungspunkte stehen in `app/src/main` ohne jeden Demo-Bezug. Der Release-Build hat sie leer.

### Release-Sicherheit

- Der Release-Build enthält keine Demo-Klassen und keine Demo-Zeichenketten. Beleg: Im Release-DEX finden sich weder `SyntheticLibrary`
  noch `DemoModeStore` noch `emberlight-demo://`. Die Prüfung läuft über das DEX der APK.
- Ein Mess-Build behält die Demo-Quelle. Er ist nicht debuggbar, profilierbar und R8-optimiert (siehe unten).

### Mess-Build `benchmark`

- Build-Typ `benchmark`: `initWith(release)`, Signatur mit dem Debug-Schlüssel, `applicationIdSuffix = ".benchmark"`,
  `isDebuggable = false`, `matchingFallbacks = [release]`. Minify und Shrink kommen aus `release`.
- `app/src/benchmark/AndroidManifest.xml` setzt `<profileable android:shell="true"/>`, nur für diesen Build.
- `app/src/benchmark/res` setzt den Namen „emberlight benchmark“.
- Belege (Build vom Stand dieses ADR): `aapt2 dump xmltree` zeigt `profileable shell=true` und keinen `debuggable`-Eintrag. `aapt2 dump
  badging` nennt `io.github.labushuya.emberlight.benchmark` und `emberlight benchmark`. Das Mapping (`mapping/benchmark/mapping.txt`)
  führt die Demo-Klassen auf, die Verkleinerung läuft also. Die Release-APK hat keine Demo-Treffer.

### CI

- `assembleBenchmark` ist nicht in der CI. Der Build braucht mit R8 mehrere Minuten zusätzlich, die Release-APK prüft die CI bereits.
  Der Mess-Build wird lokal gebaut und unter `H:\DEV\#claude\emberlight-builds` abgelegt, siehe Abschluss des PR.

### Icon-Varianten

- Das Launcher-Icon hat drei Varianten, um Debug- und Benchmark-Builds auf dem Startbildschirm zu unterscheiden. Release bleibt unverändert.
- `debug`: RGB-Komplement jeder Farbe (255 minus R, G, B), Alpha unverändert, auch bei Verläufen.
- `benchmark`: Graustufen per Luminanz (0,2126 R + 0,7152 G + 0,0722 B), Alpha unverändert.
- Umsetzung über Source-Set-Ressourcen: `app/src/debug/res/drawable` und `app/src/benchmark/res/drawable` überschreiben die Layer Hintergrund und Vordergrund. Der `monochrome`-Layer bleibt aus `main`.
- Die Varianten sind aus den Original-XMLs erzeugt und eingecheckt. Das Erzeugungsskript liegt nicht im Repo, die Ausgabe ist die Quelle.
- Kontrolle: Gerenderte PNGs der drei Varianten (Robolectric, native Grafik) wurden per Sichtprüfung abgenommen.

## Konsequenzen

- Gerätetests ohne private Daten sind möglich. Die Aufnahmen zeigen synthetische Nummern und Datumsangaben.
- Der Debug-Build wächst um das Demo-Source-Set, der Release-Build nicht.
- Offen: Videos im Demo-Modus lassen sich nicht abspielen. Der Viewer zeigt dann einen Wiedergabefehler. Eine klare Meldung und das
  Abschalten der Wiedergabe im Demo-Modus sind nicht umgesetzt. Das braucht eine kleine Änderung im Viewer, die in Paket 2 mit dem
  Overlay-Umbau passt.
- Offen: Die Demo-Bilder sind bewusst grob. Für Messungen der Dekodierung (großer Bestand realer Fotos) taugen sie nur eingeschränkt.
