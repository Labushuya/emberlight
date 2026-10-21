# ADR 0021: Ende, Aufbewahrung, Erinnerung und Löschen

Status: Angenommen, 21.10.2026 (Paket 3.6b). Umgesetzt mit den Abweichungen unten. Paket 3.6c (automatisches Löschen, Status `DELETED`, Vorlage) ist im Nachtrag am Ende beschrieben und umgesetzt, Gerätetest offen. Die Aussagen „folgen in 3.6c“ und „fehlen noch“ in den Abschnitten zu 3.6b gelten für den Stand von 3.6b.

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

- **Löschung und Vorschau-Worker.** Der Worker einer geöffneten Vault läuft im Prozess weiter. `SpaceVaults.forget` ruft darum `retire()` auf der Vault: Der Worker wird abgebrochen, und das Merkmal „zurückgezogen“ wird unter dem Commit-Lock gesetzt. Danach schreibt die Vault keine Vorschau mehr. Vor dem Rendern, vor dem Übernehmen und während `purging` wird nichts geschrieben. Eine Teildatei wird in jedem Fall entfernt. Ein Test prüft den Fall „Löschung startet, während der Worker rendert“. Offen: Öffnet während der Löschung jemand eine neue Vault für denselben Space, hat sie eine eigene Sperre und eigenen Worker. Das ist im Code nicht ausgeschlossen. Ein Restrisiko bleibt bis zur Prüfung am Gerät.
- **Löschen bei Abbruch im Dateisystem.** Ein Fehler beim Entfernen einer Datei lässt den Space mit `purging` stehen. Die Oberfläche zeigt „Die Löschung ist unterbrochen“, der nächste Start setzt fort. Ein Test prüft den Abbruch nach dem Merkmal, nicht jeden einzelnen Dateischritt.
- **Frist ändern während einer Drehung.** Der Dialog hält seine Auswahl über `rememberSaveable`. Die Dauer startet bei der heutigen Restfrist, gerundet auf volle Tage.
- **Gerätetest offen.** Die Erinnerung, die Aktionen und der Wecker nach einem Neustart sind nur mit Robolectric geprüft. Der Testkatalog Abschnitt 10 (Punkte 10.16 bis 10.29) listet den Gerätetest.
- **Goldens.** Telefon de und en für Aufbewahrungsansicht, Frist ändern, Jetzt löschen und die Liste. Ein großes Display ist nicht eigens aufgenommen. Die Liste auf dem Tablet zeigt dieselbe Karte wie auf dem Telefon.
- **Liste: Status.** Die Karte zeigte bisher für jeden Space den Status „Entwurf“. Das ist mit 3.6b auf den tatsächlichen Status umgestellt. Das ist eine Korrektur über den Auftrag hinaus, sie ist nötig, damit das Löschdatum bei beendeten Spaces verständlich ist.

## Konsequenzen

- `share:space` bekommt den Unterordner `retention` mit reinen Regeln, Planung, Repository und Löschung. Die Regeln sind ohne Android testbar.
- `feature:space` bekommt den Unterordner `retention` für Wecker, Empfänger, Benachrichtigung und Routen. Der Empfänger ist im Manifest des Moduls eingetragen und nicht exportiert.
- Die Datenbankversion ist 8. Die Migration `MIGRATION_7_8` hat einen eigenen Test.
- 3.6c nutzt `SpacePurge` für die automatische Löschung nach Fristende. Der Status `DELETED` und der Listeneintrag fehlen noch.

## Nachtrag Paket 3.6c (21.10.2026): automatisches Löschen, Status `DELETED`, Vorlage

Status: umgesetzt in 3.6c, Gerätetest offen (Testkatalog Abschnitt 10, Punkte 10.30 bis 10.38). Mit diesem Nachtrag sind die offenen Punkte oben erledigt, soweit sie den Prozess betreffen.

### Automatisches Löschen

- **Fällig ist ein Space**, der beendet ist, nicht gerade gelöscht wird und dessen Aufbewahrung zu jetzt abgelaufen ist (`RetentionRules.effectiveUntil` ≤ jetzt). Die Planung (`RetentionReminderPlanner.plan`) liefert dafür `purgeDue`. Bei „Sofort löschen“ fällt die Löschung auf das Ende.
- **Vorrang vor der Erinnerung.** `RetentionReminders.checkNow` löscht zuerst, dann plant es die Erinnerungen. Ein Space, dessen Löschung fällig ist, bekommt keine Erinnerung mehr. Der Planner überspringt ihn ebenfalls.
- **Auslöser.** (1) Start der App (`startupCatchUp`, mit Fortsetzen unterbrochener Löschungen). (2) Jede Anzeige der Liste (`SpacesListViewModel`). (3) Der Wecker: Der nächste Zeitpunkt ist jetzt auch das Ende der Aufbewahrung. (4) Ein WorkManager-Lauf `RetentionWorker` (`work-runtime-ktx` 2.12.0, ohne Play Services), stündlich, `ExistingPeriodicWorkPolicy.KEEP`. Eingeplant wird er einmal je Prozessstart in `EmberlightApplication.onCreate`. Die Application liefert auch die WorkManager-Konfiguration (`Configuration.Provider`). WorkManager verlangt mindestens 15 Minuten, das System kann den Lauf verschieben. Der Wecker bleibt das engere Sicherheitsnetz.
- **Serialisierung.** `checkNow` läuft unter einer Sperre. Wecker, Lauf, Start und Liste können sich nicht überlappen und damit keine Erinnerung doppelt senden.
- **Fehler.** Scheitert eine Löschung, bleibt ihr Merkmal `purging` gesetzt. Die übrigen Löschungen laufen weiter. Der nächste Auslöser setzt die Löschung fort. Der Fehler wird nicht protokolliert (bekannte Schwäche, kein Logging im Projekt).

### Status `DELETED` mit Listeneintrag

- **Abschluss der Löschung** (`SpacePurge.purge`): Merkmal `purging` gesetzt, Vault freigegeben, Medien und Vorschauen entfernt (`SpaceStorage.deleteAllButCovers`), dann in einer Transaktion Medien, Teilnehmer, Pausen und der Übergang nach `DELETED` (`SpaceDao.markDeleted`). Der Übergang setzt `deleted_at`, löscht Frist, Erinnerungsmerker, Einladung (Hash, Zeitstempel, verschlüsseltes Geheimnis) und Port und setzt `purging` zurück.
- **Titelbild bleibt.** Der Eintrag zeigt es. Name, Farbe und Einstellungen bleiben ebenfalls.
- **Schema 9 → 10.** Additiv: `space.deleted_at` (INTEGER, `NULL`). Migration `MIGRATION_9_10` (nach dem Hotfix mit `MIGRATION_8_9`), Test `SpacesDatabaseMigrationToVersionTenTest` (Kette 8 → 10).
- **Aus Liste entfernen** (`SpacePurge.remove`). Nur für `DELETED` und ohne `purging`. Erst die Zeile (Transaktion), dann das Verzeichnis inklusive Titelbild. Bricht es dazwischen ab, bleibt ein verwaistes Verzeichnis zurück. Das räumt `OrphanSpaceDirectories` beim ersten Zugriff je Prozess weg (ADR 0016).
- **Keine Bearbeitung und kein Start** bei `ENDED` und `DELETED`. Das war schon so, die Oberfläche zeigt nur noch „Als Vorlage für neuen Space“ und „Aus Liste entfernen“.

### Uploads und Vault

- **Uploads** (`SpaceVault.ingest`) prüfen zu Beginn `purging` und `DELETED`. Dann antworten sie mit `RejectReason.SpaceDeleting`. Der Server übersetzt das in `410` mit `space_closed`, wie nach dem Ende (ADR 0014). Der Gastgeber-Upload zeigt „Der Space wird gelöscht. Uploads werden nicht mehr angenommen.“.
- **Keine neue Vault** (`SpaceVaults`). `vaultOf` liefert `null`, `vaultFor` wirft `SpaceNotOpenableException`, wenn der Space `purging` ist oder `DELETED`. Die Prüfung läuft unter der Sperre der Vault-Registrierung, also gleichzeitig mit dem Öffnen. Damit öffnet sich nach dem Löschen im Prozess keine zweite Vault mehr. Das Restrisiko „Vault-Doppelung“ aus Paket 3.6b ist für den Prozess geschlossen, nicht für einen zweiten Prozess (es gibt keinen, die App läuft in einem Prozess).

### Vorlage für einen neuen Space

- **Aufruf.** Im Detail eines beendeten oder gelöschten Space: „Als Vorlage für neuen Space“. Das öffnet das Formular über `SpaceEditRoute(template = …)`.
- **Übernommen:** Name, Farbe, Aufbewahrung, Laufzeit-Regel und Speicherlimit. Das Titelbild kopiert die Formularlogik über `storeCover` in den neuen Entwurf, mit Standort-Entfernung wie bei jedem Titelbild. Medien und Teilnehmer nicht.
- **Neu geprüft:** Ein festes Ende, das nicht in der Zukunft und nicht höchstens 30 Tage entfernt liegt, wird auf die Voreinstellung (ein Tag) gesetzt. Ein Speicherlimit über der Obergrenze des Geräts wird wie bei einem neuen Space gewählt (`SpaceForm.forNewSpace`). Beide Fälle zeigt das Formular als Hinweis (`TemplateNote`). Die Prüfung beim Speichern bleibt unverändert.
- **Logik** in `feature:space` (`form/SpaceTemplate`), ohne Android. Ein Test für jede Grenze, für gelöschte und beendete Spaces.
- **Der Original-Space** bleibt unverändert. Die Vorlage legt nichts an, bevor gespeichert wird.

### Abweichungen und Auslegungen

1. **Sofort löschen** löscht zum Ende, nicht erst zur Erinnerung. Die Erinnerung kommt weiterhin zehn Minuten vorher. Dann ist das Löschen „sofort“ gemeint.
2. **Nachlauf während „Sofort löschen“.** Die Löschung fällt auf das Ende. Im Nachlauf (60 s) antwortet der Server ohne Medien mit `410`. Ob die Vault während des Nachlaufs schon geschlossen ist, ist am Gerät zu prüfen (Testkatalog 10.37).
3. **Port.** Der Eintrag `DELETED` hat keinen Port mehr. Ein gelöschter Space startet nie wieder.
4. **Erinnerung und Löschung zum selben Zeitpunkt.** Die Löschung gewinnt, die Erinnerung entfällt. Eine Erinnerung für einen bereits gelöschten Space geht nicht mehr raus.

### Offen

- **Gerätetest** (Testkatalog 10.30 bis 10.38): Liste, Entfernen, Vorlage, Upload während der Löschung, Wecker und Lauf bei ausgeschalteter App, Doze.
- **WorkManager und Doze.** Ob der stündliche Lauf im Akkubetrieb zuverlässig greift, ist am Gerät zu beobachten. Der Wecker ist das Sicherheitsnetz, und der Start der App holt alles nach.
- **Fehler bei der automatischen Löschung** werden wiederholt und protokolliert (`EmberlightRetention`, siehe Nachtrag 3.6d). Eine Anzeige für die Nutzerin bzw. den Nutzer fehlt weiterhin.

## Nachtrag 3.6d: Löschen während des Nachlaufs, Protokoll (21.10.2026)

**Befund (vor dem Nachtrag).**
1. **Uploads im Strom.** `SpaceVault.ingest` prüfte `purging` nur am Anfang. Ein Upload, der vor dem Ende begann und während des 60-s-Nachlaufs noch Bytes empfing, konnte nach der Löschung in `commit` die Teildatei in den Space-Ordner verschieben und einen Medieneintrag anlegen. Die Löschung war da schon durch.
2. **Vorschauen.** `previewPath` legt den Ordner `previews/` bei Bedarf neu an. Ein Schreiben nach der Löschung war durch `writable()` verhindert, aber nur für den Vorschau-Worker, nicht für den Upload-Pfad.
3. **Vault im Nachlauf.** Der Server hält seine Vault bis zum Ende des Nachlaufs. `forgetVault` gibt sie frei (`retire`), danach schreibt sie keine Vorschau mehr. Für Uploads gab es keine vergleichbare Sperre.
4. **Gescheiterte Löschungen.** `RetentionRepository.purgeDue` nahm nur Spaces aus der Planung, die nicht `purging` sind. Eine gescheiterte Löschung blieb damit bis zum nächsten Start der App liegen, obwohl der stündliche Lauf sie fortsetzen soll.

**Lösung.**
- `SpaceVault.closedForUploads()` = Vault per `retire` geschlossen oder Space `purging`/`DELETED`. Die Prüfung läuft am Anfang, direkt nach dem Strom und erneut unter dem Commit-Lock. Dort gibt es `RejectReason.SpaceDeleting`, und die Teildatei wird verworfen.
- Die Reihenfolge der Löschung bleibt: Merkmal `purging` setzen, dann `forgetVault` (`retire`, wartet auf den Commit-Lock), dann Dateien. Ein Commit läuft also entweder vollständig vor der Löschung oder gar nicht.
- `purgeDue` setzt gescheiterte Löschungen (`purging`) zuerst fort, danach die fälligen.
- Ein Fehler bei `resumePurges` hält die übrigen Spaces nicht mehr auf.

**Protokoll.** `RetentionLog` schreibt mit `Log.w` unter `EmberlightRetention`: Space-Kennung, Klassenname der Ausnahme und der Versuch seit Prozessstart. Keine Fehlermeldung, keine Namen, keine Pfade. Der Versuch zählt nur im Speicher und beginnt beim Neustart neu, eine Persistenz dafür gibt es nicht.

**Absicherung.**
- `UploadAcrossPurgeTest` (share:space): Ein Upload steht mitten im Strom, die Löschung läuft ab, danach ist das Ergebnis `SpaceDeleting`. Es bleibt keine Datei im Space-Ordner und kein Medieneintrag. Zweiter Fall: `retire` allein verhindert die Übernahme.
- `RetentionFailureLogTest` (share:space): Protokoll je Versuch ohne Fehlermeldung, Zähler beginnt nach einem Erfolg neu, gescheiterte Löschung wird im nächsten Lauf fortgesetzt.

**Restrisiko.** Stürzt der Prozess mitten im Upload nach der Löschung ab, kann eine Teildatei `tmp-*.part` im Ordner des gelöschten Space liegen. Für gelöschte Spaces öffnet sich keine Vault mehr, `recover` läuft also nicht. Die Bereinigung beim Start für `DELETED` ist offen. Der Umfang ist eine Teildatei eines Uploads.
