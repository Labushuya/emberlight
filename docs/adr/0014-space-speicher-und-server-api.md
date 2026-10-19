# ADR 0014: Space-Speicher und Server-API (Meilenstein 3, Paket 3.1)

Status: Angenommen, 18.10.2026. Umsetzung in Paket 3.1a, siehe Abschnitt „Umsetzung 3.1a“. Die Festlegungen des Entwurfs gelten, soweit dort nicht abgewichen wird.

## Kontext

Paket 3.1 braucht einen Speicher für Space-Medien, einen Index und eine API, über die Gäste hochladen und herunterladen. Der Server läuft im Foreground-Service (ADR 0012). Die Galerie darf den Space-Speicher nicht erreichen (CLAUDE.md, „Strikte Trennung von Space und Galerie“).

## Module

### Entscheidung

- Neues Modul **`share:space`**: Domänenmodell, `SpaceStorage`, Medien-Index, Inhaltsprüfung, Dedupe. Ohne Ktor. Es ist ein Android-Library-Modul, weil Room darin liegt (siehe Persistenz). Der Pfad des Space-Verzeichnisses kommt von außen (Dienst), `share:space` fragt keinen `Context` ab.
- `share:server` hängt von `share:space` ab. Ktor-Routen rufen nur die öffentlichen Typen von `share:space` auf.
- Abhängigkeitsregeln:
  - `share:space` darf nicht von `:app`, `:feature:*`, `:core:media` oder `:share:server` abhängen (transitiv, wie `checkServerIsolation`). Vorschlag: die Build-Sperre auf `share:space` ausdehnen.
  - `:core:media`, `:feature:gallery` und `:app` dürfen nicht von `:share:space` oder `:share:server` abhängen (neue Sperre `checkGalleryNoSpaceDependency`). Vorschlag. Die bisherige Sperre prüft nur die Gegenrichtung.
  - Quelltext-Prüfung `checkServerSources` gilt künftig auch für `share/space/src` (Vorschlag).

### Alternativen

- **Alles in `share:server`**: einfacher, aber Domänenlogik ist dann nur mit Ktor-Tests prüfbar, und die Galerie-Sperre müsste an zwei Stellen gelten. Verworfen.
- **Speicher in `core:data`**: `core:data` ist nicht gesperrt (ADR 0012, Offene Punkte). Eine Abhängigkeit von dort auf die Galerie wäre unbemerkt möglich. Verworfen.

## Persistenz

### Befund

- Room ist **nicht** im Projekt. Eine Suche in allen `*.toml`, `*.kts` und `*.gradle` ergab keinen Treffer (Stand 18.10.2026).
- CLAUDE.md nennt Room im Technik-Abschnitt. Die Einführung folgt also der Vorgabe, braucht aber eine Abhängigkeit im Version Catalog und KSP (CLAUDE.md nennt KSP).

### Entscheidung

- Room mit **eigener Datenbankdatei** `spaces.db`, abgelegt unter `noBackupFilesDir/spaces/spaces.db` (nicht `databases/`). Begründung: `databases/` ist nicht gesondert vom Backup geschützt, Dateien in `noBackupFilesDir` sind es (ADR 0013, Speicher).
- **Vermutung, offen**: Room erlaubt einen absoluten Dateinamen im `databaseBuilder`. Das lässt sich am SDK nicht prüfen. `Context.getDatabasePath` ist im SDK abstrakt, die Implementierung (`ContextImpl`) liegt nicht lokal vor (ADR 0013, Android-API-Fakten, Nr. 5). **Im 3.1a-Robolectric-Test zu verifizieren**: Die Datenbank liegt tatsächlich unter `noBackupFilesDir/spaces/`, nicht unter `databases/`. Falls der absolute Pfad nicht geht, ist die Datenbank über `Room.databaseBuilder` mit eigenem Dateipfad oder über den Factory-Hook anzulegen. Der Pfad wird im Gerätetest kontrolliert.
- **Belegt**: `Context.getNoBackupFilesDir()` ist abstrakt und ab API 21 verfügbar (ADR 0013, Android-API-Fakten, Nr. 4).
- Felder (Vorschlag, mit der Begründung aus ADR 0013):

| Tabelle | Felder |
|---|---|
| `space` | `id`, `name`, `color`, `expires_at`, `retention_until`, `storage_limit_bytes`, `created_at`. In 3.1 nur als Stub, befüllt in 3.2. |
| `participant` | `id`, `space_id`, `display_name` (eindeutig pro Space), `role` (HOST/GUEST), `token_hash` (SHA-256). In 3.1 nur der Gastgeber als Test-Principal, befüllt in 3.4. |
| `media` | `id` (Base64url, 128 bit), `space_id`, `sha256` (32 Byte), `mime`, `size_bytes`, `original_name` (bereinigt, max. 120 Zeichen), `uploader_id`, `created_at` (UTC, Millisekunden), `storage_name` (Zufallsname, nie vom Client), `state` (`READY`, `DELETING`). |

- Eindeutigkeit: `media(space_id, sha256)` unique (Dedupe). `media(id)` primary key. Index `media(space_id, created_at, id)` für die Liste.
- `state` dient der Bereinigung. Beim Start werden `DELETING`-Einträge und Dateien ohne Eintrag entfernt (Vorschlag).

### Trennung von der Galerie

Die Datenbank ist für den Galerie-Code nicht erreichbar. Das sichert der Code auf drei Ebenen:

1. **Modulgrenze**: Galerie-Module hängen nicht von `share:space` ab, und `share:space` hängt nicht von der Galerie ab. Beides prüft der Build (siehe Module).
2. **Speicherort**: Die Datei liegt in `noBackupFilesDir` der App. Die Galerie greift auf `MediaStore` zu, nicht auf den app-internen Speicher.
3. **Konstanten**: Der Pfad wird nur in `share:space` gebildet.

Grenze dieser Absicherung: Es ist eine Organisationsgarantie im Build, keine Sicherheitsgrenze innerhalb des Prozesses. Code derselben App kann die Datei öffnen. Die Sicherheitsgrenze ist die App-Sandbox (ADR 0013, Bedrohungsmodell).

## Ein aktiver Space pro Serverlauf

- In M3 bedient der Server genau einen Space. Die Pfade enthalten keine Space-ID, die Zugangsschlüssel gelten für diesen Space.
- Begründung: Mehrere Spaces in einem Server würden Mandantentrennung in jeder Route verlangen. Das vergrößert die Angriffsfläche, ohne einen Nutzen in M3 (Gastgeber gleichzeitig nur ein Space, KONZEPT).
- Ein Wechsel des Spaces geschieht über Stopp und Start des Dienstes.

## API v1

Präfix `/api/v1`, außer `/health`. Alle Antworten sind JSON, außer dem Medieninhalt. Jede Antwort hat `X-Content-Type-Options: nosniff` und `Cache-Control: no-store`, außer dem Medieninhalt (siehe unten).

Jede Anfrage durchläuft zuerst die Prüfungen aus ADR 0013: Host, Netz, Authorization.

### Endpunkte

| Methode und Pfad | Zweck | Erfolg | Fehler |
|---|---|---|---|
| `GET /health` | Status | `200` `{"status":"ok","apiVersion":1}` | `401`, `403` |
| `GET /api/v1/space` | Name, Farbe, Ablauf, Limits, Belegung | `200` | `401`, `403`, `410` (Space beendet) |
| `GET /api/v1/media?cursor=&limit=` | Liste: ID, Uploader-Anzeigename, Typ, Größe, Zeit | `200` mit `items` und `nextCursor` | `400` (`bad_cursor`), `401`, `403`, `410` |
| `POST /api/v1/media` | Upload eines Mediums (roher Body) | `201` neu, `200` bereits vorhanden | `400`, `401`, `403`, `410`, `413`, `415`, `422`, `429`, `507` |
| `GET /api/v1/media/{id}/content` | Download (Range möglich) | `200`, `206` | `401`, `403`, `404` |
| `DELETE /api/v1/media/{id}` | Löschen | `204` | `401`, `403`, `404` |

### Upload

- Request: `Content-Type: application/octet-stream`, anderer Typ ergibt `415`. Der Body ist die Datei.
- `X-Emberlight-Filename`: percent-encodiertes UTF-8. Nach dem Dekodieren höchstens 255 Byte, sonst `400` (`bad_filename`). Die Bereinigung (Vorschlag):
  - Unicode-Normalisierung NFC
  - Steuerzeichen und `/ \ :` entfernen
  - führende Punkte entfernen
  - auf 120 Zeichen kürzen
  - leer nach Bereinigung: `datei`
  - Der bereinigte Name steht nur in `original_name` und in `Content-Disposition`.
- `Content-Length` ist optional. Ist es größer als das Byte-Limit, kommt sofort `413`. In jedem Fall zählt der Server die gelesenen Bytes und bricht beim Limit ab (`413`, Teildatei gelöscht).
- Antwort `201`: `{"id":"…","type":"image/jpeg","size":12345,"duplicate":false,"warning":null}`. Bei Dedupe `200` mit `duplicate: true` und der vorhandenen ID. Der Uploader ist der erste.
- `warning`: `"storage_80"`, sobald der Space mindestens 80 % seines Limits belegt. Sonst `null`.
- `507` mit Code `storage_full` (Space-Limit erreicht) oder `device_storage_low` (Mindest-Restspeicher unterschritten).
- `429` mit `Retry-After: 5` bei zu vielen parallelen Uploads (Startwerte aus ADR 0013).
- `422` mit `location_not_removable` (ab 3.1b, siehe ADR 0013).

### Download

- Antwort `200` bzw. `206`: `Content-Type` aus der Erkennung, `Content-Length`, `Accept-Ranges: bytes`.
- `Content-Disposition: attachment; filename="<ASCII-Fallback>"; filename*=UTF-8''<percent-encodiert>` nach RFC 6266 [ADR 0013, Q13]. Der ASCII-Fallback ersetzt Nicht-ASCII-Zeichen durch `_`.
- `Cache-Control: no-store`. Medien werden nicht zwischengespeichert, weil sie beim Ablauf des Spaces sofort unerreichbar sein sollen (KONZEPT). Vorschlag.

### Liste

- Sortierung: `created_at` absteigend, dann `id`. Standard-`limit` 50, Maximum 100.
- `cursor` ist undurchsichtig: Base64url von `created_at` und `id`. Ungültig: `400` `bad_cursor`.

### Löschen

- `204` nach dem Löschen. Erlaubt: der Uploader für eigene Medien, der Gastgeber für alle (KONZEPT).
- Gast, fremdes vorhandenes Medium: `403`. Unbekannte ID: `404`. Dass ein Medium existiert, ist für Teilnehmer ohnehin über die Liste sichtbar, daher verrät die Unterscheidung nichts Neues.

### Fehlerformat

- Einheitlich: `{"error":"<code>"}` plus optionale Felder (z. B. `"limit"` bei `413`).
- Die Codes sind maschinenlesbar. Die Texte stehen in der Gäste-Webseite (3.5) in `de` und `en`. Der Server enthält keine Nutzertexte (CLAUDE.md: alle Nutzertexte in `strings.xml`).
- Codes: `unauthorized`, `forbidden_host`, `forbidden_network`, `forbidden`, `not_found`, `bad_request`, `bad_cursor`, `bad_filename`, `payload_too_large`, `unsupported_media_type`, `location_not_removable`, `too_many_uploads`, `storage_full`, `device_storage_low`, `space_closed`.

### Statuscodes im Überblick

| Code | Bedeutung |
|---|---|
| 200 | Erfolg, Dedupe-Treffer oder Liste |
| 201 | Neues Medium gespeichert |
| 204 | Gelöscht |
| 206 | Teil-Download (Range) |
| 400 | Fehlerhafte Anfrage (Cursor, Dateiname) |
| 401 | Kein gültiger Token, auch bei mehreren `Authorization`-Headern |
| 403 | Host oder Netz nicht erlaubt, oder Recht fehlt |
| 404 | Unbekannte Medien-ID |
| 410 | Space ist beendet |
| 413 | Body über dem Limit |
| 415 | Falscher `Content-Type` oder Inhalt nicht erlaubt |
| 422 | Ortsdaten nicht sicher entfernbar (3.1b) |
| 429 | Zu viele parallele Uploads |
| 507 | Speicher voll (Space oder Gerät) |

### Version

- **Entschieden (18.10.2026)**: `SERVER_API_VERSION` bleibt 1. `/api/v1` ist die erste Fassung mit Routen. Die Version wird erst bei inkompatiblen Änderungen nach dem ersten Release mit Routen (`v0.3.0`) erhöht.
- **Aufgabe für 3.1a**: Die KDoc von `SERVER_API_VERSION` in `KtorLocalServer.kt` (Zeile 25) lautet heute „Erhöhen, wenn sich Routen oder Antworten für Gäste-Geräte ändern.“ Sie wird auf die Regel oben umgestellt. Das ist ein reiner Kommentar, kein Verhaltenswechsel. Der bestehende Test `/health` prüft `apiVersion` über die Konstante.

## Offene Entscheidungen

1. **Room-Pfad**: offen. Absoluter Dateipfad im `databaseBuilder` ist im 3.1a-Robolectric-Test zu verifizieren (siehe Persistenz).
2. **`/health` hinter der Authorization (entschieden, 18.10.2026)**: `/health` bleibt hinter der Zugriffsprüfung, wie in ADR 0012. Die Gäste-Webseite schickt den Token als Header mit, auch für den Health-Check.
3. **Version (entschieden, 18.10.2026)**: `SERVER_API_VERSION` bleibt 1 (siehe Version).
4. **Zwischenstand ohne GPS-Entfernung (entschieden, 18.10.2026)**: 3.1a und 3.1b bleiben getrennt, 3.1b vor dem ersten erreichbaren Upload (siehe ADR 0013, Offene Entscheidungen, Punkt 2).

## Umfang 3.1a

Festgelegt am 18.10.2026. Zu 3.1a gehört alles außer der GPS-Entfernung (3.1b).

- Zugriffsprüfung (Host, Netz, Authorization) in die Phase `Setup` verlegen. Test „kein Plugin antwortet vorher“ (ADR 0013, Reihenfolge der Prüfungen).
- Manifest von `share:server`: `ACCESS_NETWORK_STATE` ergänzen (Pflicht für `registerNetworkCallback`, ADR 0013, Android-API-Fakten).
- `<device-transfer>`-Regeln in `app/src/main/res/xml/data_extraction_rules.xml` ergänzen. Heute fehlt der Abschnitt, damit ist die Geräte-zu-Geräte-Übertragung für App-Daten nicht eingeschränkt (ADR 0013, Speicher). Die Ausschlüsse entsprechen `<cloud-backup>`.
- Build-Sperren für `share:space` in beide Richtungen: `share:space` darf nicht von `:app`, `:feature:*`, `:core:media` oder `share:server` abhängen (transitiv). Galerie-Module (`:core:media`, `:feature:gallery`, `:app`) dürfen nicht von `share:*` abhängen. Die Richtung „Galerie → Space“ ist neu (`checkGalleryNoSpaceDependency`, Vorschlag).
- Prüfung, dass das Release-Manifest keinen Debug-Einstieg enthält (Build-Prüfung `checkReleaseManifest`, Vorschlag).
- Room-Pfad per Robolectric-Test absichern: prüfen, dass die Datenbank unter `noBackupFilesDir/spaces/` angelegt wird und der absolute Dateiname funktioniert (siehe Persistenz, offen).
- `SERVER_API_VERSION`-KDoc anpassen (siehe Version).
- Zugriffsprüfung auf `Setup` und Protokollierung ohne Roh-URI (ADR 0013, Logging) sind ebenfalls Teil von 3.1a.

## Teststrategie für 3.1a

### Pflicht-Tests aus ADR 0012 (Abschnitt „Pflicht vor Paket 3.1“)

| Pflicht | Test | Ebene |
|---|---|---|
| Erreichbarkeit nur WLAN | Host-Header fremd → `403`. Absender außerhalb des Subnetzes → `403`. Bindung an die WLAN-IP. | JVM mit echtem Socket (Host und Netz injiziert). Bindung zusätzlich am Gerät. |
| Explizite CIO-Grenzen | Body-Limit mit und ohne `Content-Length`. Kopf-Zeile über 8192 Byte → `400`. Idle-Timeout gesetzt. Kopf-Gesamtgröße als dokumentiertes Restrisiko (Test beschreibt das Verhalten). | JVM mit echtem Socket |
| Kein Plugin vor der Zugriffsprüfung | Test-Plugin in `Plugins` und `Fallback` antwortet, unauthentifizierte Anfrage erhält trotzdem `401`. | JVM |
| Downloads | Header `nosniff`, `Content-Type` aus der Erkennung, `Content-Disposition` nach RFC 6266. | JVM |
| Mehrfache Authorization | Zwei Header → `401`. | JVM |
| Stopp in der App | Vorerst nur Server-Ebene (Stopp gibt Port frei). Der Stopp aus der Oberfläche folgt in 3.3 (Voraussetzung von 3.3, siehe ADR 0012, Nachtrag). | JVM, Gerät in 3.3 |
| Logging | Route-Template statt Roh-URI. Kein Token, kein Header im Protokoll. | JVM mit Log-Sammler |

### Pflicht-Tests für den Upload (ADR 0012)

| Test | Ergebnis |
|---|---|
| Upload bei `DenyAll` | `401`, keine Datei im Space-Verzeichnis |
| Chunked Upload ohne `Content-Length`, Body über dem Limit | Abbruch am Byte-Limit, `413`, keine Datei |
| `Content-Length` klein, Body groß | Abbruch am Byte-Limit (nicht am angegebenen Wert), `413`, keine Datei |
| Verbindungsabbruch mitten im Upload | Teildatei gelöscht, kein Index-Eintrag |
| Ziel außerhalb von `SpaceStorage` (`..`, Symlink) | abgewiesen, nichts geschrieben |

### Zusätzliche Tests

- Host-Prüfung (richtige und falsche IP, falscher Port).
- Subnetz-Prüfung (Absender im und außerhalb des Subnetzes, Randfälle `/30`, `/32`).
- Inhaltsprüfung je Format mit präparierten Dateien: Signaturen aus ADR 0013 (JPEG, PNG, GIF, WebP, HEIC, AVIF, MP4, 3GP, MOV mit und ohne `ftyp`), Sequenzen abgewiesen, falsche Endung, Polyglot (PNG mit angehängtem HTML bleibt `image/png`), Strukturprüfung (Box-Größe 0, zu groß, Summe über Dateigröße).
- Dedupe: zweiter Upload gleichen Inhalts → `200`, gleiche ID, Uploader bleibt der erste. Unterschiedlicher Inhalt → `201`.
- Speicherlimit: `warning: storage_80` ab 80 %, `507` `storage_full` bei Überschreiten. Mindest-Restspeicher: `507` `device_storage_low`.
- Löschrechte: Uploader darf eigenes löschen, Gast fremdes nicht (`403`), Gastgeber alles.
- Liste: Sortierung, Cursor, ungültiger Cursor (`400`).
- Range: `206` mit korrektem `Content-Range`, ungültiger Range.

Die Testdateien sind kleine, synthetische Byte-Folgen mit den Signaturen. Echte Beispieldateien kommen mit 3.1b (GPS).

## Debug-Einstieg für Gerätetests

Vorschlag, ohne Produktionspfad:

- Ein Debug-Einstieg liegt im Source-Set `app/src/debug`. Er ist nicht im Release-Manifest. Die Build-Prüfung `checkReleaseManifest` (Vorschlag) bricht ab, wenn der Einstieg im Release-Manifest auftaucht.
- Die Debug-Aktivität startet den Server mit einem Space und einem Gastgeber-Principal. Der Gast-Token wird nur auf dem Bildschirm des Debug-Builds gezeigt, nie geloggt.
- Der Gerätetest läuft vom PC im selben WLAN mit `curl` (Authorization-Header, Host-Header). Der Start geschieht durch die Debug-Aktivität.
- Den Start über einen Intent mit Test-Principal (adb) halte ich für denkbar, aber nicht nötig. Er würde einen Testpfad mit Principal-Übergabe über Intent-Extras einführen. Ob die Nutzerin bzw. der Nutzer das will, ist offen.

## Konsequenzen

- `share:space` ist das neue Modul mit Persistenz und Dateiablage. `share:server` wird dünn.
- Room kommt ins Projekt. Der Version Catalog und KSP werden erweitert.
- Die Sperren erweitern sich: `share:space` und die Richtung Galerie → Space.
- `KtorLocalServer` und `RequestAuthorizer` ändern sich (siehe ADR 0013).
- Die Schnittstelle v1 ist mit 3.1 festgelegt. Änderungen danach sind Brüche und brauchen eine Version 2.

## Quellen

- ADR 0012 (Pflicht vor Paket 3.1, Pflicht-Tests für den Upload).
- ADR 0013 (Schutz gegen Manipulation, Quellen Q1 bis Q17).
- [CLAUDE.md](../../CLAUDE.md) (Technik: Room, KSP, Ktor CIO; Struktur; Build-Sperren; Nutzertexte in `strings.xml`).
- [KONZEPT.md](../KONZEPT.md) (Trennung von Space und Galerie, Speicher, Ende und Nachhalten).
- Repo: `gradle/libs.versions.toml` (kein Room), `share/server/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`.

## Umsetzung 3.1a (18.10.2026)

Status: Angenommen, 18.10.2026, mit den Abweichungen unten. Die Sicherheitsabschnitte stehen in [ADR 0013](0013-schutz-gegen-manipulation.md), Abschnitt „Umsetzung 3.1a“.

### Module und Abhängigkeiten

- `share:space` (Android-Library, Room über KSP) enthält Domäne, Speicher, Index, Inhaltsprüfung, Dedupe und Grenzen. Ohne Ktor.
- `share:server` hängt von `share:space` ab und ruft nur dessen öffentliche Typen auf.
- Build-Sperren: `checkSpaceIsolation` (kein Zugriff auf `:app`, `:feature:*`, `:core:media`, `:share:server`, transitiv), `checkSpaceSources` (keine Galerie- und Speichertokens im Quelltext), `checkGalleryNoSpaceDependency` (`:core:media` und `:feature:gallery` hängen nicht von `share:*` ab), `checkReleaseManifestDebugEntry` (das Release-Manifest enthält keinen Debug-Einstieg; Name statt `checkReleaseManifest`, weil die Registrierung für die Release-Variante doppelt lief und der Task so eindeutig und einmalig angelegt wird).
- Room: `spaces.db` unter `noBackupFilesDir/spaces/`. Die Tabellen `media` und `participant` (Felder laut Entwurf, ohne `storage_name`).
- `data_extraction_rules.xml`: `<device-transfer>` schließt dieselben Domänen aus wie `<cloud-backup>`.

### API v1 (Stand 3.1a)

| Methode und Pfad | Erfolg | Fehler |
|---|---|---|
| `GET /health` | `200` `{"status":"ok","apiVersion":1}` | `401`, `403` |
| `GET /api/v1/space` | `200` mit Name, Speicherlimit, Belegung, Warnung | `401`, `403` |
| `GET /api/v1/media?limit=&cursor=` | `200` mit `items` und `nextCursor` | `400` (`bad_request`, `bad_cursor`), `401`, `403` |
| `POST /api/v1/media` | `201` neu, `200` Dedupe (`duplicate: true`) | `400` (`bad_filename`, `bad_request`, `incomplete_body`), `401`, `403`, `408`, `413` (mit `limit`), `415`, `429`, `507` (`storage_full`, `device_storage_low`) |
| `GET /api/v1/media/{id}/content` | `200`, `206` (Range) | `401`, `403`, `404` |
| `DELETE /api/v1/media/{id}` | `204` | `401`, `403`, `404` |

Jede Fehlerantwort: `{"error":"<code>"}` plus Felder. Schutzheader auf allen Antworten: `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. Download zusätzlich `Content-Disposition: attachment` mit `filename` und `filename*`.

### Abweichungen von der Entscheidung oben

- Zusätzliche Codes: `service_busy` (503), `incomplete_body` (400), `request_timeout` (408). `space_closed` (410) fehlt, weil es in 3.1a keine Beendigung gibt.
- Keine Tabelle `space` in 3.1a. Name und Speicherlimit kommen aus der Einrichtung des Serverlaufs.
- Upload-Warnung: `warning` ist `"storage_80"` ab 80 % des Speicherlimits, sonst `null`.
- Liste: `uploader` ist der Anzeigename aus der Teilnehmer-Tabelle, `createdAt` ist Epoch-Millisekunden in UTC.
- Debug-Einstieg: Start und Stopp per Broadcast-Empfänger statt Aktivität, Tokens in einer Datei unter `files/debug-server/`. Der Empfänger ist im Debug-Build exportiert und mit `android.permission.DUMP` geschützt (nur Shell und System). Beschreibung in `docs/DEBUG-SERVER.md`.

### Offen

- Auto-Resume nach Netzverlust und die Stopp-Oberfläche folgen in 3.3.
- GPS-Entfernung folgt in 3.1b, vor dem ersten Upload über die Gäste-Webseite (3.5).
- Keep-Regeln für den Serverpfad (`share/server/consumer-rules.pro`: `KtorLocalServer`, `SpaceServerService$Companion`) entfernen oder prüfen, sobald 3.3 einen echten Einstieg aus der Oberfläche hat (Paket 3.3 in `docs/PLAN-M3.md`).
- Befund beim Vergleich der Release-Builds (Stand f09f220): Die Regeln halten nur die Klassenhülle. Die Routing-Pipeline (`installEmberlightModule` in `ServerPipeline.kt`) fehlt im R8-Mapping, die Ktor-Plugins (`PartialContent`, `ConditionalHeaders`) ebenfalls. Im Release ist der Server ohne Einrichtung nicht startbar, deshalb fällt das bisher nicht auf. Vor 3.3 muss die Pipeline gehalten werden, und die Build-Prüfung soll das Mapping daraufhin prüfen.
