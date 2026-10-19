# ADR 0016: Space anlegen und verwalten (Meilenstein 3, Paket 3.2)

Status: Angenommen, 19.10.2026, mit den Abweichungen unten. Umsetzung in Paket 3.2. Die Festlegungen der Nutzerin bzw. des Nutzers (Felder, Voreinstellungen, Titelbild, Status, Bildschirme) stehen im Paketplan (Stand 18.10.2026) und in [KONZEPT.md](../KONZEPT.md).

## Kontext

Paket 3.2 bringt die erste Oberfläche für Spaces: Liste, Formular und Detail. Die Eigenschaften eines Space müssen gespeichert werden, ohne den Server (Paket 3.3) zu berühren. Das Titelbild ist das erste Medium, das Nutzer in den Space-Speicher geben. Es muss dieselben Regeln einhalten wie ein Upload: Inhaltsprüfung, Größe und Entfernung des Standorts (ADR 0015).

## Entscheidungen

### Eigenschaften und Regeln

- **Laufzeit als Regel, nicht als Zeitpunkt.** Eine feste Dauer (2 h bis 1 Woche) wird beim Start in einen Zeitpunkt umgerechnet, das passiert in Paket 3.3. Ein „Eigenes Ende“ ist ein absoluter Zeitpunkt in UTC-Millisekunden, nur in der Zukunft und höchstens 30 Tage entfernt.
- **Nachhaltefrist** in Tagen, `0` heißt „Sofort löschen“. Eigene Werte 1 bis 90.
- **Speicherlimit** in Bytes, 0,5 bis 64 GiB. Die Warnung „Limit größer als freier Speicher minus 2 GiB“ ist nur ein Hinweis und blockiert nicht.
- **Farbe** als Index 0 bis 7 in `SpaceAccents` (`core:design`). Gespeichert wird der Index, nicht die Farbe. Die Farben sind auf dem dunklen Schema kontrastgeprüft (`SpaceAccentContrastTest`).
- **Validierung in der Domäne** (`SpaceRules.validate`), nicht im Formular. Das Formular rechnet nur Text in Werte um und bekommt die Fehler zurück. Die Oberfläche zeigt einen Fehler am Feld, sobald der Nutzer das Feld berührt hat oder gespeichert wird.
- **Voreinstellungen** (`SpaceDefaults`): Farbe 1, Laufzeit 1 Tag, Nachhaltefrist 1 Woche, Speicherlimit 4 GiB, Name leer.

### Status-Modell

`DRAFT`, `ACTIVE`, `ENDED`, `DELETED` sind angelegt. In 3.2 entstehen nur Entwürfe. Bearbeiten ist nur im Entwurf erlaubt. `SpaceRepository.update` lehnt einen anderen Status ab, auch wenn die Oberfläche es nicht anbietet.

### Titelbild-Pipeline

- Die Auswahl läuft über die System-Fotoauswahl (`PickVisualMedia`, nur Bilder). Die App braucht dafür keine Berechtigung.
- `share:space` bekommt einen `InputStream`, nie einen URI. Das Öffnen des URI geschieht in `feature:space`. Die Quelltext-Sperre `checkSpaceSources` bleibt damit grün.
- Ablauf in `SpaceCoverStore`: Kopf lesen und nur Bilder akzeptieren, begrenzt auf 50 MiB kopieren und dabei den Hash bilden, Struktur prüfen, dann `LocationScrubber` (Plan/Apply). Nicht bereinigbarer Standort ergibt `LocationNotRemovable`. Für Titelbilder gibt es keine Einwilligung.
- Ablage als `cover-<sha256>` im Space-Verzeichnis, getrennt von Medien (`<sha256>`). Dadurch löscht die Bereinigung beim Serverstart (`SpaceVault.recover`) keine Titelbilder. `SpaceStorage.deleteUnlisted` überspringt sie.
- Wechsel und Entfernen: Ein neues Bild ersetzt das alte erst beim Speichern. Ein Abbruch behält das gespeicherte Bild und entfernt das ungespeicherte (`retainCovers`).

### Persistenz

- Tabelle `space` in `spaces.db`, Version 2 → 3, additive Migration `MIGRATION_2_3`. Tests: Migration mit Medien aus Version 2 (`SpacesDatabaseMigrationToVersionThreeTest`) und Repository (`SpaceRepositoryTest`).
- Die Kennung eines neuen Space wird im Formular erzeugt und im Entwurf gehalten (`SavedStateHandle`). So lässt sich das Titelbild vor dem Speichern ablegen.
- Löschen: Medien-Einträge und Space-Eintrag in einer Transaktion, danach der gesamte Speicher des Space (`SpaceStorage.deleteEverything`).
- Speicherort wie beim Server: `noBackupFilesDir/spaces` (ADR 0014).

### Isolation

- `feature:space` hängt von `share:space` ab. Die Richtung ist erlaubt (ADR 0014).
- `feature:space` greift für Space-Daten nicht auf `core:media` zu.
- `share:space` enthält keine Galerie- oder Speicher-Tokens (`checkSpaceSources`).

### Navigation und Layout

- Ziele: Liste (`SpacesRoute`), Detail (`SpaceDetailRoute(id)`), Formular (`SpaceEditRoute(id?)`). Alle drei gehören zum Top-Level-Ziel Spaces, die Navigationsleiste bleibt dort markiert.
- Ab 600 dp Breite stehen Liste und Detail nebeneinander. Die Auswahl bleibt über Drehungen erhalten. Darunter öffnet Antippen das Detail als eigenes Ziel.
- Ungespeicherte Änderungen fragen beim Zurückgehen (Schaltfläche und Systemgeste) nach.

## Abweichungen und offene Punkte

- **Datum und Uhrzeit als Textfelder** (`TT.MM.JJJJ`, `HH:MM`), nicht als Kalender- und Zeitauswahl. Grund: Der Umfang von 3.2 ist ohnehin groß. Die Felder prüfen strikt, und die Formatangabe steht am Feld. Eine Auswahl mit Kalender ist nachzuziehen.
- **Teilnehmer ohne Bezug zum Space.** Die Tabelle `participant` hat keine `space_id`. Beim Löschen eines Space bleiben Teilnehmer erhalten. Das ist in Paket 3.4 zu lösen, wenn Teilnehmer angelegt werden.
- **Verwaiste Entwürfe.** Wird die App beendet, während ein neuer Entwurf ein Titelbild hat, bleibt sein Verzeichnis liegen. Die Bereinigung beim Start ist nicht umgesetzt. Sie kann keine Verzeichnisse von Spaces anfassen, die noch in der Datenbank stehen.
- **Zwei Datenbank-Instanzen auf `spaces.db`.** Die App hält eine Instanz für Spaces. Der Server (`SpaceVault`) öffnet eine eigene. Sie lesen und schreiben unterschiedliche Tabellen. Ab 3.3 ist zu prüfen, ob eine gemeinsame Instanz nötig wird.
- **Titelbild-Anzeige** dekodiert verkleinert. Das ist auf dem Gerät nicht gemessen.
- Die Anzeige der Speicherwarnung nutzt den freien Speicher von `noBackupFilesDir`.

## Konsequenzen

- `share:space` hat mit Titelbildern und Verwaltung einen zweiten Zweck, bleibt aber ohne Oberfläche und ohne Ktor.
- Die Liste der Accent-Farben ist eine Designentscheidung in `core:design`. Eine Änderung braucht den Kontrasttest.
- Titelbilder sind die erste Stelle, an der Standort-Bereinigung außerhalb des Uploads läuft. Ihre Tests liegen in `SpaceCoverStoreTest`.
