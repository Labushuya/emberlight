# Paketplan Meilenstein 3: Space im vorhandenen WLAN

Stand: 18.10.2026, Nachtrag 20.10.2026 (Entscheidungen zu Paket 3.2 und Designentscheidungen (a) bis (f)), Paket 3.3 umgesetzt am 20.10.2026 (ADR 0017), Paket 3.4 umgesetzt am 20.10.2026 (PR #35, ADR 0018), Nachtrag 21.10.2026 (Aufteilung 3.5a in 3.5a-1 bis 3.5a-3, Stand 21.10.2026: 3.5a-1 umgesetzt in PR #36, 3.5a-2 umgesetzt in PR #37, 3.5a-3 umgesetzt in PR #38; Aufteilung 3.5 in 3.5a bis 3.5c, Paket 3.6 mit Verlängern und Vorlage, Vorab-Release `v0.3.0-alpha.1`, kumulierter Testkatalog). Der Plan ist von der Nutzerin bzw. dem Nutzer bestätigt. Die Pakete folgen aufeinander. Jedes Paket endet mit einem Gerätetest bzw. einem Testkatalog.

Entwürfe der Oberflächen (Mockups, nicht bindend) liegen in [docs/mockups/](mockups/README.md). Dort steht die Zuordnung der Bildschirme zu den Paketen.

Grundlage ist [CLAUDE.md](../CLAUDE.md), Meilenstein 3, und [KONZEPT.md](KONZEPT.md), Abschnitt Spaces.

## Pakete

| Paket | Inhalt |
|---|---|
| 3.0 ✅ | Server-Basis, Foreground-Service, ADR 0012 (#25) |
| 3.1b (GPS-Entfernung, Einwilligung, Markierung; ADR 0015, Draft-PR #28, Umsetzung 19.10.2026; HEIC/HEIF/AVIF umgesetzt, CI-Gegenprobe offen bis grüner Lauf) | Standort beim Upload bereinigen, längenerhaltend, mit Prüfung danach; nicht bereinigbare Dateien nur mit Einwilligung; Markierung „Standort enthalten“. Umfang und Abweichungen: ADR 0015. |
| 3.1 (3.1a umgesetzt, PR #26, Gerätetest offen) | Server-Härtung und Space-Speicher: nur WLAN, CIO-Grenzen, Upload- und Download-API, Inhaltsprüfung, Dedupe, Speicherlimit, GPS-Entfernung; ADR „Schutz gegen Manipulation“. Ohne UI. Lieferung in zwei PRs: **3.1a** (alles außer GPS) und **3.1b** (GPS-Entfernung). 3.1b muss vor dem ersten für Nutzer erreichbaren Upload (Paket 3.5) gemergt sein. Bis dahin ist der Server aus keiner Oberfläche erreichbar. Umfang 3.1a: ADR 0014, Abschnitt „Umfang 3.1a“. |
| 3.2 (umgesetzt 19.10.2026, PR siehe Branch `feature/m3-3.2-spaces-verwalten`, ADR 0016; Nachtrag 20.10.2026; Gerätetest offen) | Space anlegen und verwalten (Name, Titelbild, Farbe, Laufzeit, Aufbewahrung nach dem Ende, Speicherlimit; Liste, Formular und Detail; Titelbild über die Fotoauswahl mit Standort-Entfernung; Migration 2 → 3). Nur Entwürfe, kein Start. Nachtrag 20.10.2026: Namensvorschläge (Würfel), harte Obergrenze des Speicherlimits, Datum und Uhrzeit über Auswahldialoge, Titelbild-Anzeige mit Coil, Bereinigung verwaister Verzeichnisse. Umfang und Abweichungen: ADR 0016. |
| 3.3 (umgesetzt 20.10.2026, ADR 0017; Gerätetest offen, siehe „Offene Punkte nach 3.3“) | Einrichtung und Betrieb: Laufzeitberechtigungen, Akku-Ausnahme, Hersteller-Freigabe (Honor zuerst), Ladegerät-Hinweis, Pausen-Erkennung, Start und Stopp in der App (Voraussetzung von 3.3, nicht von 3.1; siehe ADR 0012, Nachtrag), Start/Stopp-Abfolge über einen Kanal. Netzverlust: automatisches Fortsetzen, wenn dasselbe WLAN mit derselben IP zurückkommt; sonst Hinweis an den Gastgeber, weil sich der QR-Code ändert (ADR 0013). Release-Einstieg entsteht in 3.3 (Start aus der Oberfläche). Danach R8-Prüfung des Serverpfads im Release-Mapping (Routing-Pipeline `installEmberlightModule` muss im Release erhalten bleiben). Die R8-Prüfung bis dahin läuft über den benchmark-Build (ADR 0014, Umsetzung 3.1a). Vorgemerkt aus 3.2 (Nachtrag 20.10.2026): (1) Die beiden Room-Instanzen auf `spaces.db` (App und Server) werden zu einer gemeinsamen, prozessweiten Instanz zusammengeführt. Bis dahin gelten zwei Instanzen auf derselben Datei nicht als sauber. (2) Das Speicherlimit wird beim Start des Space geprüft. Das Limit ist keine Reservierung von Speicher. (3) Ein aktiver Space braucht einen Eintrag in der Tabelle `space`, sonst räumt die Bereinigung verwaister Verzeichnisse (ADR 0016) sein Verzeichnis weg. |
| 3.4 (umgesetzt 20.10.2026, PR #35, ADR 0018; Gerätetest offen) | Einladung und Beitritt: QR-Code bzw. Link mit Einladungsgeheimnis, Zugangsschlüssel pro Teilnehmer, Anzeigename, Gastgeber-Kennzeichnung, Wiederkehr im Browser, Abmelden, Einladung erneuern, Entfernen mit Häkchen für die Uploads, fester Port pro Space. Designentscheidung (d), festgehalten 20.10.2026: Die Gäste-Beitrittsseite zeigt den Hinweis „Verbindung läuft direkt im WLAN zum Handy des Gastgebers, nicht über das Internet. Verschlüsselung folgt in einer späteren Version.“ Nachtrag 3.4b (21.10.2026): Der Hinweis „Einladung wurde erneuert“ erscheint nur nach automatischer Erneuerung und verschwindet mit „Verstanden“ bzw. bei manueller Erneuerung (ADR 0018). |
| vor 3.5 (Pflicht, umgesetzt in 3.4, ADR 0018) | Frühe Ablehnungen (401, 403, 413, 415, 429, 503), wenn der Body noch läuft, zuverlässig zustellen: „lingering close“. Nach der Antwort Body-Reste lesen und verwerfen, höchstens 2 s bzw. 1 MiB, dann schließen. Anlass: `REFUSED_BODY_LINGER_MS` greift laut Messung nicht zuverlässig, die Verbindung schließt teils nach 1–4 ms (ADR 0014, Korrekturen nach dem Gerätetest). Die Gästeseite prüft Größe und Speicher vorab (3.5). Umgesetzt in 3.4: Verwerfen bis 64 MiB oder 2 s, dann Kanal schließen. Die Messung und der Widerspruch aus ADR 0014 stehen in ADR 0018. Die Gäste-Seite prüft die Größe vor dem Senden (3.5). |
| 3.5a-1 (umgesetzt 21.10.2026, PR #36, ADR 0019; Gerätetest offen) | Server-Grundlage für die Medienansicht: signierte, kurzlebige Medien-URLs (Zwecke `view`, `download`, `stream`; 10 bzw. 30 Minuten, ADR 0019), Medien-Gate, 60-s-Nachlauf mit 410, API-Felder (`mine`, `urlsExpireInSeconds`, `viewUrl`, `downloadUrl`, `streamUrl`; `/space` mit `remainingSeconds`, `endsAt`, `revision`, `limits`), Range mit 416 bei Mehrfachbereichen. Dazu die Platzhalterfelder `previewUrl`, `previewState`, `videoCodec`, `viewState` (immer `null`) und der Vertrag für 3.5a-2 und 3.5a-3 (ADR 0019, Abschnitt „Schnittstelle für 3.5a-2 und 3.5a-3“). Ohne Oberfläche. |
| 3.5a-2 (umgesetzt 21.10.2026, PR #37, ADR 0019; Gerätetest offen) | Vorschauen im Server: Bilder und Videos als 320-px-JPEG (`/api/v1/media/{id}/preview`), HEIC/HEIF-Ansicht als 1600-px-JPEG (`viewUrl`, sobald `viewState` `ready` ist), Codec-Erkennung (`videoCodec`), Migration 6 → 7 mit Migrationstest. Füllt die Platzhalter aus 3.5a-1. |
| 3.5a-3 (umgesetzt 21.10.2026, PR #38, ADR 0019 Nachtrag 3.5a-3, ADR 0017 Nachtrag 3.5a-3; Gerätetest offen) | Gäste-Seite der Medienansicht: Raster mit Namen und Platzhaltern, Filter „Meine“, Detail mit Bild oder Video, Download, Video mit signierter Adresse und stiller Erneuerung, Zurückziehen mit Bestätigung, Abfrage alle 10 bis 15 s. Ende-Hinweise für Gäste: Restzeit immer sichtbar, Banner ab 10 Min., Dialog ab 1 Min., Ende-Ansicht (auch nach Neuladen), Text bei Verbindungsverlust. Gastgeber-Benachrichtigungen 10 Min. vor dem Ende und beim Ende, Hinweis im Beenden-Dialog. Browser-Tests (Node mit CDP, headless) in `check`. Auslieferung nur aus dem Space-Speicher. ZIP-Download folgt später. |
| 3.5b (umgesetzt 21.10.2026, PR #39, ADR 0019 Nachtrag 3.5b, ADR 0014 Nachtrag endedAt; Gerätetest offen) | Gäste-Upload im Browser: Fortschritt je Datei (XHR), Vorabprüfung von Größe und Speicher, Wiederholung, Standort-Rückfrage pro Datei mit den Knöpfen „Mit Standort hochladen“ bzw. „Nicht hochladen“. Designentscheidung (e), festgehalten 20.10.2026: Zurückziehen eigener Uploads nur mit kurzer Bestätigung. |
| 3.5c (umgesetzt 21.10.2026, PR #40, ADR 0019 Nachtrag 3.5c; Gerätetest offen) | Gastgeber-Upload und Medienansicht in der App: eine gemeinsame SpaceVault je Space im Prozess (Befund 3.5a-2), System-Fotoauswahl, Kopie mit Standort-Entfernung und Rückfrage je Datei, Fortschritt und Abbrechen, Uploader ist der Gastgeber, Medienraster mit Vorschau und Filter „Meine“, Detail mit Entfernen im laufenden Space. |
| 3.6 | Ende und Nachhalten: Ablauf erzwingen, Aufbewahrung nach dem Ende (Feld heißt in der Oberfläche „Aufbewahrung nach dem Ende“, Begriff Nachhaltefrist im Konzept), Erinnerung mit Aktionen (Vorauswahl „nichts behalten“, Alles/Nichts auswählen), automatische Löschung. Designentscheidungen: (a) „Jetzt löschen“ in der Erinnerung nur mit Bestätigung. (f) Bei „Sofort löschen“ kommt die Erinnerung kurz **vor** dem Ende. Nutzerentscheidung „a“ vom 21.10.2026, Verlängern und Vorlage: **Verlängern** während der Laufzeit, mit Banner bei Gästen. **„Als Vorlage für neuen Space“** nach dem Ende, ohne Medien. Ein beendeter Space bleibt geschlossen. Gastgeber-Benachrichtigung 10 Min. vor dem Ende und beim Ende, Hinweis im Beenden-Dialog (Zuordnung zu 3.6 ist ein Vorschlag). |
| 3.7 | Gezielte Übernahme in `Pictures/Emberlight/<Space>`, danach Release `v0.3.0`. Pflicht (vorgezogen und umgesetzt, ADR 0020): „Über/Lizenzen“-Seite in der App mit Lizenzhinweisen aller mitgelieferten Bibliotheken (Apache-2.0 verlangt Lizenz bzw. NOTICE bei Weitergabe; ohne Play-Services-Plugin, z. B. AboutLibraries, prüfen), Hinweis auf GPL-3.0 und Link auf den Quellcode. Ausdrücklich nennen: die OFL-Schriften Fraunces und Inter (SIL OFL 1.1) mit Copyright-Zeile und Lizenztext aus `core/design/src/main/assets/licenses`. |
| nach 3.5c (nur geplant, nicht umgesetzt) | Vorab-Release `v0.3.0-alpha.1` nach den Paketen 3.5a bis 3.5c, damit die Gäste-Webseite früh auf Geräten erprobt werden kann. Mit einem Endtest durch die Nutzerin bzw. den Nutzer anhand von [TESTKATALOG-M3.md](TESTKATALOG-M3.md). Die Versionslogik für Vorab-Tags ist umgesetzt (CLAUDE.md, ADR 0004, Nachtrag): `versionName` führt das Suffix (`0.3.0-alpha.1`), `versionCode` bleibt monoton. Der Release-Workflow (`release.yml`) setzt Vorab-Tags als GitHub-Prerelease, ohne „latest“. Der Probelauf per Dispatch bleibt. |

## Festgelegt

- In M3 nehmen alle Teilnehmer über die Gäste-Webseite teil. App-zu-App-Beitritt kommt später, weil er Zertifikats-Pinning (siehe [KONZEPT.md](KONZEPT.md), Verschlüsselung) voraussetzt.
- Pro Paket gibt es am Ende einen Gerätetest bzw. einen Testkatalog.
- **GPS-Aufteilung** (18.10.2026): 3.1a und 3.1b bleiben getrennt. 3.1b muss vor dem ersten für Nutzer erreichbaren Upload (3.5) gemergt sein (ADR 0013, GPS).
- **Stopp in der App** ist Voraussetzung von 3.3 (Start aus der Oberfläche), nicht von 3.1 (ADR 0012, Nachtrag).
- **Netzverlust** (18.10.2026): 3.1a stoppt den Server bei Verlust oder Wechsel der WLAN-Schnittstelle bzw. ihrer Adresse. 3.3 setzt automatisch fort, wenn dasselbe WLAN mit derselben IP zurückkommt (ADR 0013, Netz).
- **`/health`** bleibt hinter der Zugriffsprüfung. Die Gäste-Webseite schickt den Token als Header mit (ADR 0014).
- **`SERVER_API_VERSION` bleibt 1.** Erhöhung erst bei inkompatiblen Änderungen nach dem ersten Release mit Routen (`v0.3.0`) (ADR 0014, Version).
- **Grenzwerte** (Dateigrößen, Speicherlimit, Restspeicher, Parallelität, Timeouts) wurden am 18.10.2026 von der Nutzerin bzw. dem Nutzer bestätigt (ADR 0013).
- **Engine**: CIO. Die Entscheidung steht in ADR 0013 („Umsetzung 3.1a“), gestützt auf die Messung. Der Gerätetest fehlt noch.
- **Verlängern und Vorlage** (Nutzerentscheidung „a“, 21.10.2026): Umsetzung in Paket 3.6. Ein beendeter Space bleibt geschlossen. Eine Vorlage übernimmt Einstellungen, aber keine Medien.
- **Medienzugriff im Browser** (21.10.2026): signierte, kurzlebige URLs statt offener Pfade. Die Entscheidung und ihr ADR entstehen mit 3.5a.
- **Testkatalog** (21.10.2026): ein kumulierter Katalog für den Endtest beim Vorab-Release, [TESTKATALOG-M3.md](TESTKATALOG-M3.md). Jedes Paket schreibt seinen Teil fort.
- **Paket-PRs** werden bei grüner CI gemergt (Freigabe der Nutzerin bzw. des Nutzers vom 21.10.2026, siehe CLAUDE.md, Arbeitsweise).

## Designentscheidungen (festgehalten am 20.10.2026)

Die Umsetzung steht im jeweiligen Paket. Hier nur die Zuordnung.

- **(a)** „Jetzt löschen“ in der Erinnerung nur mit Bestätigung. Paket 3.6.
- **(b)** Die acht Akzentfarben, wie umgesetzt (Paket 3.2, `SpaceAccents`).
- **(c)** Speicherlimit-Optionen über der Obergrenze des Geräts sind ausgegraut, mit Hinweis. Umgesetzt in Paket 3.2 (ADR 0016).
- **(d)** Gäste-Beitrittsseite mit Hinweis zur Verbindung im WLAN (siehe Paket 3.4 und 3.5).
- **(e)** Zurückziehen eigener Uploads mit kurzer Bestätigung. Standort-Rückfrage mit „Mit Standort hochladen“ bzw. „Nicht hochladen“. Paket 3.5.
- **(f)** Bei „Sofort löschen“ kommt die Erinnerung kurz vor dem Ende. Paket 3.6.

Entscheidungen 1 bis 4 vom 20.10.2026 (Namensvorschläge, harte Speichergrenze, Begriff „Aufbewahrung nach dem Ende“, Datum und Uhrzeit über Auswahldialoge) sind in Paket 3.2 umgesetzt, siehe ADR 0016.

## Entscheidungsdokumente

- Paket 3.1: [ADR 0013](adr/0013-schutz-gegen-manipulation.md) (Schutz gegen Manipulation, Entwurf) und [ADR 0014](adr/0014-space-speicher-und-server-api.md) (Space-Speicher und Server-API, Entwurf).
- Die Entwürfe sind vor der Implementierung von 3.1a zu prüfen. Offene Entscheidungen stehen am Ende der jeweiligen ADR.

## Testkatalog pro Paket

- **3.1a**: Die Pflicht-Tests aus [ADR 0012](adr/0012-lokaler-server.md) (Abschnitt „Pflicht vor Paket 3.1“) und die Tests aus ADR 0014 (Abschnitt „Teststrategie für 3.1a“). Dazu ein Gerätetest mit dem Debug-Einstieg aus ADR 0014.
- **3.1b**: Tests der GPS-Entfernung je Format mit echten Beispieldateien, dazu Prüfung mit einem Fremdprogramm (z. B. ExifTool). Gerätetest mit Fotos und Videos aus der Galerie.
- **3.2**: Regeln und Voreinstellungen (`SpaceRulesTest`, `SpaceFormTest`), Titelbild-Pipeline (`SpaceCoverStoreTest`: Kopie, Standort entfernt, nicht bereinigbar abgewiesen, altes Bild beim Wechsel entfernt), Repository mit Löschen und Speicher (`SpaceRepositoryTest`), Migration 2 → 3 (`SpacesDatabaseMigrationToVersionThreeTest`), ViewModels und Compose-Tests für Liste, Formular und Detail, Goldens für Liste (leer und gefüllt), Formular und Detail auf Telefon und großem Display, deutsch und englisch. Gerätetest: Testkatalog in der PR-Beschreibung. Nachtrag 20.10.2026: Namensvorschläge (`NameIdeasTest`: feste Zufallsquelle, Wortlisten je Sprache mit mindestens 40 Einträgen, Länge und Grammatik), harte Obergrenze (`SpaceRulesTest`, `SpaceRepositoryTest`, `SpaceEditViewModelTest`), verwaiste Verzeichnisse (`OrphanSpaceDirectoriesTest`), Goldens `SpaceDetail_phone_de` neu, Formular de, en und Tablet neu.
- **3.3 bis 3.7**: Der Testkatalog wird zu Beginn des jeweiligen Pakets im Entwurf festgelegt. Ab 21.10.2026 wird der Katalog kumulativ in [TESTKATALOG-M3.md](TESTKATALOG-M3.md) fortgeschrieben, Gliederung nach Funktionen.

## Offene Punkte nach 3.3

- Gerätetest 3.3 mit dem Testkatalog aus der PR-Beschreibung (Honor-Einstellung, Start, Beenden, Ablauf, WLAN aus und an, Pausen-Banner).
- Schwelle für Pausen (2 min) auf dem Gerät messen (ADR 0017).
- Lizenzen der Quellen für die Herstellerliste am Quelltext prüfen (ADR 0017, Quellen). Die Angaben im Auftrag konnten nicht belegt werden.
- Vorab-Version `v0.3.0-alpha`: Planung oben, Umsetzung nach 3.5.
- Lingering close bleibt Pflicht vor 3.5 (oben, unverändert).

## Offene Punkte nach 3.5c

- Gerätetest 3.5c mit dem Testkatalog, Abschnitt 8 (Fotoauswahl, Fortschritt, Abbrechen, Standort-Rückfrage, Video-Wiedergabe, Zoom, Entfernen, Sicht des Gasts im Browser).
- Lesbarkeit der Auswahl nach einem Prozess-Tod: Eine Standort-Rückfrage liest die Datei erneut über die Adresse der Auswahl. Ist das Recht dann weg, meldet die Reihe „Die Datei konnte nicht gelesen werden.“. Ob das auf dem Gerät vorkommt, ist zu prüfen.
- Die Medienansicht zeigt die ersten 200 Medien. Nachladen folgt mit der Medienliste über Seiten (ADR 0014, Cursor).
- Der Galerie-Viewer bleibt unberührt. Die Extraktion nach `core:viewer` ist offen.
- Auf großen Displays wechselt die Ansicht nur das Raster (3 bzw. 5 Spalten). Eine Zweispaltenansicht mit Detail fehlt.
- Die Vorschau-Worker laufen prozessweit. Ein beendeter Space behält seine Vault bis zum Prozessende. Speicher ist klein, der Verbrauch wird am Gerät beobachtet.

## Offene Punkte nach 3.5a-3

- Gerätetest 3.5a-3 mit dem Testkatalog (Abschnitte 6 und 6b, Handy und PC). Die Browser-Tests decken die Fälle ab, die Wartezeiten und der Browser des Telefons nicht.
- Die stille Erneuerung nach 30 Minuten ist nicht automatisiert (Entwickler-Prüfung im Testkatalog, Punkt 6.6).
- Nach dem Merge von 3.5a-2: Die Browser-Tests prüfen die echten Vorschauen. Die Platzhalter-Erwartungen für Video und HEIC im Galerie-Test ändern sich dann.
- Die Gastgeber-Benachrichtigungen sind auf dem Gerät nicht geprüft (Testkatalog 4.24 bis 4.26).

## Offene Punkte nach 3.4

- Gerätetest 3.4 mit dem Testkatalog aus der PR-Beschreibung (Beitritt im Browser, Wiederkehr, Abmelden, Entfernen mit und ohne Uploads, Erneuern, Adressänderung bei belegtem Port, Sperre nach Fehlversuchen).
- Die Gäste-Seite vor dem Beitritt zeigt keine Daten des Space (ADR 0018, Abweichungen). Farbe und Titelbild der Seite sind offen.
- Gäste-Seite im Browser noch nicht ausgeführt (nur statische Prüfung und Server-Tests).

## Offene Punkte nach 3.5b

- Gerätetest 3.5b mit Abschnitt 7 des Testkatalogs (Handy und PC): Auswahl, Ablage am PC, Fortschritt, Standort-Rückfrage, Dublette, Speicher voll, Netzabbruch während eines Uploads (WLAN aus), Seite offen lassen.
- Beim manuellen Beenden gibt es keine 410-Antwort, Gäste sehen den Verbindungsverlust statt der Stoppzeit (ADR 0014, Nachtrag endedAt). Entscheidung der Nutzerin bzw. des Nutzers über einen kurzen Nachlauf.
- Auslegungen im Upload (Wiederholungen, Obergrenze für Wartezeiten bei 429 und 503, Verhalten bei vollem Speicher): ADR 0019, Nachtrag 3.5b.
