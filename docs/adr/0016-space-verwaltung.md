# ADR 0016: Space anlegen und verwalten (Meilenstein 3, Paket 3.2)

Status: Angenommen, 19.10.2026, mit den Abweichungen unten. Nachtrag vom 20.10.2026: Die Entscheidungen der Nutzerin bzw. des Nutzers zu Namensvorschlägen, harter Speichergrenze, Begriff „Aufbewahrung nach dem Ende“ und Datum- und Uhrzeit-Auswahl sind umgesetzt. Die Festlegungen (Felder, Voreinstellungen, Titelbild, Status, Bildschirme) stehen im Paketplan (Stand 18.10.2026, [PLAN-M3.md](../PLAN-M3.md)) und in [KONZEPT.md](../KONZEPT.md). Die Entwürfe für Mockups und Designentscheidungen liegen in [docs/mockups](../mockups/README.md).

## Kontext

Paket 3.2 bringt die erste Oberfläche für Spaces: Liste, Formular und Detail. Die Eigenschaften eines Space müssen gespeichert werden, ohne den Server (Paket 3.3) zu berühren. Das Titelbild ist das erste Medium, das Nutzer in den Space-Speicher geben. Es muss dieselben Regeln einhalten wie ein Upload: Inhaltsprüfung, Größe und Entfernung des Standorts (ADR 0015).

## Entscheidungen

### Eigenschaften und Regeln

- **Laufzeit als Regel, nicht als Zeitpunkt.** Eine feste Dauer (2 h bis 1 Woche) wird beim Start in einen Zeitpunkt umgerechnet, das passiert in Paket 3.3. Ein „Eigenes Ende“ ist ein absoluter Zeitpunkt in UTC-Millisekunden, nur in der Zukunft und höchstens 30 Tage entfernt.
- **Aufbewahrung nach dem Ende** (intern `retentionDays`, Nachhaltefrist) in Tagen, `0` heißt „Sofort löschen“. Eigene Werte 1 bis 90. Die Oberfläche nennt das Feld „Aufbewahrung nach dem Ende“ (en: „Keep after the end“), darunter steht ein Erklärtext. Interne Bezeichner bleiben.
- **Speicherlimit mit harter Obergrenze** (Nachtrag 20.10.2026, Entscheidung 2). In Bytes, 0,5 bis 64 GiB. Die Obergrenze des Geräts ist `SpaceRules.maxStorageBytes(freier Speicher)`: freier Speicher minus 2 GiB Puffer, abgerundet auf halbe GiB. Darunter, also bei weniger als 0,5 GiB, lässt sich kein Space anlegen, das Formular erklärt den Grund. Voreinstellungen über der Obergrenze sind ausgegraut und mit „Nicht genug freier Speicher, max. X GiB“ markiert. Ein eigener Wert darüber ist ein Fehler (`STORAGE_ABOVE_MAX`) und sperrt das Speichern. Die Anzeige „max. X GiB verfügbar“ steht am Feld.
  - Die Obergrenze ist keine Reservierung. Die Prüfung läuft im Formular und erneut beim Speichern im Repository. Der Speicher wird über `StorageProbe` gemessen, einer testbaren Abstraktion (`SpacesModule` liefert `StatFs` auf `noBackupFilesDir`). Ist der Speicher nicht lesbar, ist die Obergrenze `0`: Das ist der sichere Fehlerfall.
  - Für einen neuen Space gilt: Passt die Voreinstellung 4 GiB nicht unter die Obergrenze, wird die größte zulässige Voreinstellung vorausgewählt. Passt keine, bekommt das Feld die Obergrenze als eigenen Wert.
  - Für Paket 3.3 vorgemerkt: Die Prüfung beim Start des Space. Das Limit ist keine Reservierung.
- **Farbe** als Index 0 bis 7 in `SpaceAccents` (`core:design`). Gespeichert wird der Index, nicht die Farbe. Die Farben sind auf dem dunklen Schema kontrastgeprüft (`SpaceAccentContrastTest`).
- **Validierung in der Domäne** (`SpaceRules.validate`, mit der Obergrenze als Pflichtparameter), nicht im Formular. Das Formular rechnet nur Text in Werte um und bekommt die Fehler zurück. Die Oberfläche zeigt einen Fehler am Feld, sobald der Nutzer das Feld berührt hat oder gespeichert wird.
- **Voreinstellungen** (`SpaceDefaults`): Farbe 1, Laufzeit 1 Tag, Aufbewahrung 1 Woche, Speicherlimit 4 GiB, Name leer. Beim Anlegen ist das Namensfeld leer, es gibt keinen automatischen Vorschlag.

### Datum und Uhrzeit (Nachtrag 20.10.2026, Entscheidung 4)

- Das „Eigene Ende“ nutzt die Auswahl von Material 3: `DatePickerDialog` mit `DatePicker` für das Datum, eine Dialogbox mit `TimePicker` für die Uhrzeit. Das Datum lässt sich nur von heute bis 30 Tage voraus wählen.
- Das Formular hält Datum und Uhrzeit typisiert (`LocalDate`, `LocalTime`). Die Umrechnung in UTC-Millisekunden geschieht in der Zeitzone des Geräts (`SpaceForm.untilMillis`). Der Picker arbeitet in UTC-Mitternacht des gewählten Tages, dafür gibt es eine eigene Umrechnung.
- Anzeige in der Sprache des Geräts (`FormatStyle.MEDIUM` und `SHORT`), nicht mehr mit festem Muster.
- Die Regeln bleiben: Zukunft, höchstens 30 Tage (`SpaceRules`). Die Prüfung liegt bei der Domäne.

### Namensvorschläge (Nachtrag 20.10.2026, Entscheidung 1)

- Ein Würfel neben dem Namensfeld (Icon mit `contentDescription`) schlägt Adjektiv und Substantiv vor, etwa „Tanzende Waschbären“ oder „Dancing Raccoons“.
- Die Wortlisten liegen als `string-array` in `strings.xml` (de und en), je 48 Einträge, und damit als Ressourcen. Das Deutsche steht in der Form, die zu einem Plural ohne Artikel passt (starke Deklination, Endung „-e“), die Substantive stehen im Plural und groß. So ist jede Kombination grammatisch richtig. Der Test prüft die Endungen, die Großschreibung und die Länge (höchstens 40 Zeichen) über alle Kombinationen.
- Generator (`NameIdeas.suggest`) mit übergebener Zufallsquelle. Das ViewModel bekommt `kotlin.random.Random` über Hilt, Tests übergeben `Random(42)`.
- **Ersetzen:** Ist das Feld leer, übernimmt der Würfel den Vorschlag sofort. Enthält es einen eigenen Namen, erscheint eine Rückfrage („Namen ersetzen?“, „Ersetzen“ / „Eigenen behalten“). Gewählt wurde die Rückfrage statt einer Undo-Snackbar. Sie kommt ohne Zeitfenster und ohne gemerkten Vorzustand aus und lässt sich im Compose-Test eindeutig prüfen. Der Vorschlag bleibt editierbar.

### Status-Modell

`DRAFT`, `ACTIVE`, `ENDED`, `DELETED` sind angelegt. In 3.2 entstehen nur Entwürfe. Bearbeiten ist nur im Entwurf erlaubt. `SpaceRepository.update` lehnt einen anderen Status ab, auch wenn die Oberfläche es nicht anbietet.

### Titelbild-Pipeline

- Die Auswahl läuft über die System-Fotoauswahl (`PickVisualMedia`, nur Bilder). Die App braucht dafür keine Berechtigung.
- `share:space` bekommt einen `InputStream`, nie einen URI. Das Öffnen des URI geschieht in `feature:space`. Die Quelltext-Sperre `checkSpaceSources` bleibt damit grün.
- Ablauf in `SpaceCoverStore`: Kopf lesen und nur Bilder akzeptieren, begrenzt auf 50 MiB kopieren und dabei den Hash bilden, Struktur prüfen, dann `LocationScrubber` (Plan/Apply). Nicht bereinigbarer Standort ergibt `LocationNotRemovable`. Für Titelbilder gibt es keine Einwilligung.
- Ablage als `cover-<sha256>` im Space-Verzeichnis, getrennt von Medien (`<sha256>`). Dadurch löscht die Bereinigung beim Serverstart (`SpaceVault.recover`) keine Titelbilder. `SpaceStorage.deleteUnlisted` überspringt sie.
- Wechsel und Entfernen: Ein neues Bild ersetzt das alte erst beim Speichern. Ein Abbruch behält das gespeicherte Bild und entfernt das ungespeicherte (`retainCovers`).
- **Anzeige** (Nachtrag 20.10.2026): Coil 3 lädt die Datei aus dem Space-Speicher, verkleinert auf 512 px, mit EXIF-Ausrichtung, Cache und Abbruch. Begründung: Der bisherige `BitmapFactory`-Weg ignoriert die EXIF-Ausrichtung (ein Hochformatfoto mit Drehmarke erschiene quer), und Coil ist im Projekt schon etabliert. Abhängigkeit nur in `feature:space`, nicht in `share:*`. Die Quelle ist eine Datei, kein URI, kein `MediaStore`.

### Persistenz

- Tabelle `space` in `spaces.db`, Version 2 → 3, additive Migration `MIGRATION_2_3`. Tests: Migration mit Medien aus Version 2 (`SpacesDatabaseMigrationToVersionThreeTest`) und Repository (`SpaceRepositoryTest`).
- Die Kennung eines neuen Space wird im Formular erzeugt und im Entwurf gehalten (`SavedStateHandle`). So lässt sich das Titelbild vor dem Speichern ablegen.
- Löschen: Medien-Einträge und Space-Eintrag in einer Transaktion, danach der gesamte Speicher des Space (`SpaceStorage.deleteEverything`).
- Speicherort wie beim Server: `noBackupFilesDir/spaces` (ADR 0014).

### Verwaiste Verzeichnisse (Nachtrag 20.10.2026)

- Beim ersten Zugriff auf das Repository je Prozess (`SpaceRepository.ensureSwept`) entfernt `OrphanSpaceDirectories` Verzeichnisse unter der Space-Wurzel, die keinen Eintrag in `space` haben.
- Angefasst werden nur Einträge, deren Name eine gültige Space-Kennung ist. Fremde Namen, Dateien wie `spaces.db`, Symlinks und alles außerhalb der Wurzel bleiben unberührt. Ein Baum, der einen Symlink enthält, bleibt ganz stehen.
- Der Lauf liegt vor jeder Schreibung der Sitzung. So kann er keinen Entwurf dieser Sitzung treffen. Der Lauf gilt nur für den jeweiligen Prozess. Ein aktiver Space im Server (Paket 3.3) muss einen Eintrag in `space` haben, sonst würde sein Verzeichnis als verwaist gelöscht. Das ist in 3.3 zu beachten.

### Isolation

- `feature:space` hängt von `share:space` ab. Die Richtung ist erlaubt (ADR 0014).
- `feature:space` greift für Space-Daten nicht auf `core:media` zu.
- `share:space` enthält keine Galerie- oder Speicher-Tokens (`checkSpaceSources`).

### Navigation und Layout

- Ziele: Liste (`SpacesRoute`), Detail (`SpaceDetailRoute(id)`), Formular (`SpaceEditRoute(id?)`). Alle drei gehören zum Top-Level-Ziel Spaces, die Navigationsleiste bleibt dort markiert.
- Ab 600 dp Breite stehen Liste und Detail nebeneinander. Die Auswahl bleibt über Drehungen erhalten. Darunter öffnet Antippen das Detail als eigenes Ziel.
- Ungespeicherte Änderungen fragen beim Zurückgehen (Schaltfläche und Systemgeste) nach.

## Abweichungen und offene Punkte

- [x] **Datum und Uhrzeit als Textfelder.** Erledigt 20.10.2026: Auswahl über `DatePickerDialog` und `TimePicker` (Entscheidung 4).
- [ ] **Teilnehmer ohne Bezug zum Space.** Die Tabelle `participant` hat keine `space_id`. Beim Löschen eines Space bleiben Teilnehmer erhalten. Das ist in Paket 3.4 zu lösen, wenn Teilnehmer angelegt werden.
- [x] **Verwaiste Entwürfe.** Erledigt 20.10.2026: Bereinigung beim ersten Zugriff je Prozess, nur Kennungen mit fehlendem Eintrag (siehe oben).
- [ ] **Zwei Datenbank-Instanzen auf `spaces.db`.** Die App hält eine Instanz für Spaces. Der Server (`SpaceVault`) öffnet eine eigene. Sie lesen und schreiben unterschiedliche Tabellen. Festgelegt für Paket 3.3: eine gemeinsame, prozessweite Instanz (siehe `PLAN-M3.md`).
- [x] **Titelbild-Anzeige** dekodiert verkleinert. Erledigt 20.10.2026: Coil 3 mit Größenbegrenzung und EXIF-Ausrichtung (siehe oben).
- [x] **Speicherwarnung über den freien Speicher.** Erledigt 20.10.2026: harte Obergrenze mit Prüfung beim Speichern (siehe oben). Offen für 3.3: Prüfung beim Start.
- [ ] **Gerätetest** aller Abläufe, Testkatalog in der PR-Beschreibung.
- [ ] **Symlink-Tests unter Windows.** `OrphanSpaceDirectoriesTest` prüft Symlinks nur, wenn das System sie erlaubt (`Assume`). Auf dem Entwicklungsrechner ohne Entwicklermodus laufen diese Fälle nicht. CI (Linux) führt sie aus.

## Konsequenzen

- `share:space` hat mit Titelbildern und Verwaltung einen zweiten Zweck, bleibt aber ohne Oberfläche und ohne Ktor.
- Die Liste der Accent-Farben ist eine Designentscheidung in `core:design`. Eine Änderung braucht den Kontrasttest.
- Titelbilder sind die erste Stelle, an der Standort-Bereinigung außerhalb des Uploads läuft. Ihre Tests liegen in `SpaceCoverStoreTest`.
- Die Wortlisten sind Ressourcen. Neue Einträge in einer Sprache müssen die Grammatik- und Längenprüfung in `NameIdeasTest` bestehen.
