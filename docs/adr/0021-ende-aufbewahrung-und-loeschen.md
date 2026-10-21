# ADR 0021: Ende, Aufbewahrung, Erinnerung und Löschen

Status: Angenommen, 21.10.2026 (Paket 3.6b). Umgesetzt mit den Abweichungen unten. Die automatische Löschung nach Fristende, der Status `DELETED` mit Listeneintrag und die Vorlage folgen in Paket 3.6c. Das Verlängern während der Laufzeit und die Vorlage gehören zu 3.6a.

## Kontext

Nach dem Ende bleiben die Medien eines Space für eine gewählte Frist im Space-Speicher (KONZEPT, Abschnitt „Ende und Nachhalten“). Der Gastgeber muss die Frist jederzeit ändern können, eine Erinnerung vor Fristende bekommen und die Medien vorher löschen können, ohne dass ein Versehen sie wegnimmt. Die Erinnerung ist eine Benachrichtigung mit Aktionen. Paket 3.6b liefert die Grundlage dafür: das Schema, die Regeln, die gemeinsame Löschung und die Oberfläche. Paket 3.6c baut die automatische Löschung darauf.

## Entscheidungen

### Frist

- **Speicherung.** `space.retention_until` ist nur gesetzt, wenn der Gastgeber die Frist geändert hat. Ohne Änderung gilt Ende plus Frist (`retention_days`). Die Migration 7 → 8 ist additiv, bestehende Spaces behalten ihre Frist.
- **Änderung zählt ab jetzt.** Eine neue Frist von „1 Woche“ heißt eine Woche ab dem Zeitpunkt der Änderung (Auslegung zu Paket 3.6b). Die Alternative, ab dem Ende zu zählen, hätte eine Verkürzung auf „1 Tag“ nach drei Tagen sofort fällig gemacht. Das Datum in der Liste passt damit zur gewählten Dauer.
- **Dauer.** 0 Tage heißt „sofort“. Eigene Dauern liegen zwischen 1 und 90 Tagen. `RetentionRules.isValidDays` prüft das in der Domäne, nicht im Formular.
- **„1 Tag länger aufbewahren“** setzt die Frist auf das spätere von Fristende und jetzt, plus einen Tag. Die Aktion löscht nichts.
- **Nur beendete Spaces** haben eine Frist. Ein laufender Space und ein Space, der gerade gelöscht wird, lassen sich nicht ändern (`RetentionChange.NotChangeable`).

### Erinnerung

- Ab zwei Tagen Frist: 24 h vor dem Ende der Frist.
- Kürzere Fristen (ein Tag, eigene Dauern unter zwei Tagen): nach der Hälfte der Frist, spätestens 1 h vor dem Ende der Frist, nie vor dem Ende des Space (`RetentionRules.reminderAfterEndAt`).
- „Sofort löschen“: zusammen mit der Vorwarnung zehn Minuten vor dem Ende des Space. Endete der Space früher, sofort. Der Text ist „Aufbewahrung: sofort löschen – sichere jetzt, was du behalten möchtest“. Der Benachrichtigungstyp ist eigen, die Vorwarnung aus ADR 0017 bleibt unverändert.
- **Je geplantem Zeitpunkt höchstens einmal.** `space.retention_reminded_for` merkt den gesendeten Zeitpunkt. Eine Änderung der Frist plant neu, dabei wird der Merker geleert.
- **Planung.** `RetentionReminderPlanner` ist frei von Android-Typen. Ein inexakter Wecker (`AlarmManager.setAndAllowWhileIdle`, ohne Play Services) weckt den Empfänger zum nächsten Zeitpunkt. Der Start der App setzt unterbrochene Löschungen fort und holt verpasste Erinnerungen nach. Das deckt einen Neustart des Geräts ab, weil der Wecker dabei verloren geht.
- **Ohne Berechtigung** bleibt die Erinnerung offen, ihr Merker wird nicht gesetzt. Das Detail und die Liste zeigen den Hinweis „Erinnerungen sind aus“. Sobald die Berechtigung da ist, kommt die Erinnerung beim nächsten Start oder Wecker.
- WorkManager wurde nicht eingeführt. Ein Wecker reicht, und eine neue Abhängigkeit war nicht nötig.

### Benachrichtigung und Aktionen

- Eigener Kanal „Aufbewahrung“ (`space_retention`). Eine Meldung je Space, eine neue ersetzt die alte.
- Aktionen nach dem Ende: „Ansehen“ (öffnet das Detail), „1 Tag länger aufbewahren“ (Broadcast an `RetentionReceiver`, ohne Löschung), „Jetzt löschen“ (öffnet das Detail mit geöffneter Bestätigung über `retention_purge/<id>`). Löschen erfolgt nie direkt aus der Benachrichtigung.
- Vor dem Ende eines „Sofort löschen“-Space gibt es nur „Ansehen“. Eine Verlängerung ist vor dem Ende nicht möglich, weil die Frist erst mit dem Ende beginnt.

### Löschen

- **Eine Funktion für 3.6b und 3.6c**: `SpacePurge.purge`. Sie löscht nur beendete Spaces. „Jetzt löschen“ in der App verlangt eine Bestätigung (Designentscheidung (a)).
- **Reihenfolge, damit Abbruch nichts verliert:**
  1. Merkmal `purging` setzen (der Space ist dann nicht mehr änderbar und wird als „wird gelöscht“ angezeigt).
  2. Vault des Space freigeben (`SpaceVaults.forget`), damit keine Vorschau nach dem Löschen schreibt.
  3. Speicher des Space entfernen (Titelbild, Medien, Vorschauen, Verzeichnis). Idempotent.
  4. In einer Transaktion: Medien, Teilnehmer, Pausen, Space-Eintrag.
- Bricht der Prozess dazwischen ab, bleibt der Space mit `purging` stehen. `resumeAll` setzt ihn beim nächsten Start fort. Ein Space ohne Eintrag gilt als gelöscht.
- **Einladung.** Der Schlüssel im Android-Keystore (ADR 0018) ist für alle Spaces gemeinsam. Mit dem Space-Eintrag verschwindet das verschlüsselte Geheimnis.

### Medienraster

- Die Aufbewahrungsansicht zeigt das Medienraster aus 3.5c (`HostMediaSection`) nur lesend. Entfernen und Übernehmen gibt es hier nicht. „In Galerie übernehmen“ kommt erst mit 3.7.

## Abweichungen und offene Punkte

- **Löschung und Vorschau-Worker.** Der Worker einer geöffneten Vault läuft im Prozess weiter. Nach `forget` schreibt er nichts mehr über die Vault. Ob ein Vorschau-Job während des Löschens eine Datei neu anlegt, ist nicht abschließend geprüft. Eine so angelegte Datei fiele bei der nächsten Bereinigung verwaister Verzeichnisse weg (ADR 0016). Am Gerät zu beobachten.
- **Löschen bei Abbruch im Dateisystem.** Ein Fehler beim Entfernen einer Datei lässt den Space mit `purging` stehen. Die Oberfläche zeigt „Die Löschung ist unterbrochen“, der nächste Start setzt fort. Ein Test prüft den Abbruch nach dem Merkmal, nicht jeden einzelnen Dateischritt.
- **Frist ändern während einer Drehung.** Der Dialog hält seine Auswahl über `rememberSaveable`. Die Dauer startet bei der heutigen Restfrist, gerundet auf volle Tage.
- **Gerätetest offen.** Die Erinnerung, die Aktionen und der Wecker nach einem Neustart sind nur mit Robolectric geprüft. Der Testkatalog Abschnitt 10 (Punkte 10.11 bis 10.24) listet den Gerätetest.
- **Goldens.** Telefon de und en für Aufbewahrungsansicht, Frist ändern, Jetzt löschen und die Liste. Ein großes Display ist nicht eigens aufgenommen. Die Liste auf dem Tablet zeigt dieselbe Karte wie auf dem Telefon.
- **Liste: Status.** Die Karte zeigte bisher für jeden Space den Status „Entwurf“. Das ist mit 3.6b auf den tatsächlichen Status umgestellt. Das ist eine Korrektur über den Auftrag hinaus, sie ist nötig, damit das Löschdatum bei beendeten Spaces verständlich ist.

## Konsequenzen

- `share:space` bekommt den Unterordner `retention` mit reinen Regeln, Planung, Repository und Löschung. Die Regeln sind ohne Android testbar.
- `feature:space` bekommt den Unterordner `retention` für Wecker, Empfänger, Benachrichtigung und Routen. Der Empfänger ist im Manifest des Moduls eingetragen und nicht exportiert.
- Die Datenbankversion ist 8. Die Migration `MIGRATION_7_8` hat einen eigenen Test.
- 3.6c nutzt `SpacePurge` für die automatische Löschung nach Fristende. Der Status `DELETED` und der Listeneintrag fehlen noch.
