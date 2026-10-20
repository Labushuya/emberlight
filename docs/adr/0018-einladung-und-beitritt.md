# ADR 0018: Einladung und Beitritt (Meilenstein 3, Paket 3.4)

Status: angenommen mit Umsetzung in Paket 3.4 (20.10.2026). Die Entscheidungen 1 bis 15 der Nutzerin bzw. des Nutzers gelten „gemäß Empfehlung, änderbar bis zum Merge“. Abweichungen von diesen Entscheidungen stehen unten ausdrücklich. Die Umsetzung ist im Branch `feature/m3-3.4-einladung-beitritt` (PR #35, Basis #32).

## Kontext

Paket 3.4 bringt den Beitritt über die Gäste-Webseite. Ein Gastgeber zeigt den QR-Code des laufenden Space, Gäste öffnen ihn im Browser, wählen einen Anzeigenamen und bekommen einen persönlichen Zugangsschlüssel. Vorher war der Server ohne Teilnehmer nicht erreichbar (ADR 0017, `DenyAll`). Der Gastgeber braucht außerdem Werkzeuge: Teilnehmer entfernen, die Einladung erneuern, die Adresse nach einem Portwechsel neu zeigen.

Grundlagen: [ADR 0012](0012-lokaler-server.md) (Zugriffsschutz vor jeder Route, Logging ohne Tokens), [ADR 0013](0013-schutz-gegen-manipulation.md) (Token nur als Hash, Reihenfolge der Prüfungen, Netz), [ADR 0014](0014-space-speicher-und-server-api.md) (API v1, Fehlerformat, lingering close), [ADR 0016](0016-space-verwaltung.md) (Space-Einstellungen), [ADR 0017](0017-einrichtung-und-betrieb.md) (Betrieb, Neustart nach Prozess-Tod, Pausen).

## Entscheidungen

1. **Beitritt.** QR-Code und Link: `http://<ip>:<port>/#i=<einladung>`. Das Einladungsgeheimnis hat 256 bit und steht base64url-kodiert im Fragment. Gespeichert wird nur sein SHA-256 (`space.invite_hash`). `POST /api/v1/join` nimmt nur `Content-Type: application/json` an. Geheimnis und Anzeigename stehen im JSON-Körper, nie in URL oder Header. Die Antwort `201` enthält `participantId`, `displayName`, `role`, `token` (256 bit, base64url) und `spaceId`. Der Token erscheint nur in dieser Antwort. Gespeichert wird sein SHA-256.
2. **Erneuern.** „Einladung erneuern“ ersetzt den Hash, alte QR-Codes sind sofort ungültig. Beigetretene Teilnehmer bleiben mit ihrem Token.
3. **`GET /api/v1/me`** liefert Kennung, Anzeigename, Rolle und `spaceId`. **`DELETE /api/v1/me`** meldet den Gast ab (`204`). Der Gastgeber darf sich nicht selbst entfernen (`403`).
4. **Browser merkt sich den Gast.** `localStorage` unter dem Schlüssel `emberlight.guest:<origin>:<spaceId>`. Der Wert trägt Token, Kennung, Name und Space-ID. Beim Laden prüft die Seite zuerst die gespeicherte Sitzung mit `GET /me`. Gültig, dann wird ein Fragment `#i=` ignoriert. Antwort 401 oder 410 löscht den Eintrag lokal. Das Geheimnis wird sofort per `history.replaceState` aus der Adresse entfernt und lebt nur in einer Variablen der Seite.
5. **Entfernter Gast.** Der Token ist sofort widerrufen. Laufende Uploads dieses Teilnehmers brechen ab (`ActiveUploads`, die Teildatei räumt die Vault). Seine Uploads bleiben, angezeigt als „(entfernt)“ (`uploaderRemoved`). Der Entfernen-Dialog hat das Häkchen „Auch alle Uploads von <Name> löschen“. Mit dem Häkchen verschwinden die Medien samt Datei.
6. **Grenzen.** Höchstens 50 aktive Teilnehmer, der Gastgeber mitgezählt (`space_full`, `409`). Namen: NFC, Leerzeichen-Kategorien auf ein Leerzeichen, Ränder trimmen, interne Folgen reduzieren, 1 bis 24 Codepunkte. Erlaubt sind Buchstaben, Ziffern (Nd), Marks, das Leerzeichen und `- _ . '`. Verboten sind alle übrigen Zeichen, also Cc, Cf (Bidi, ZWJ, ZWSP), Zl, Zp, Co, Cn und Satzzeichen wie `< > / =`. Der Eindeutigkeitsschlüssel ist NFKC und danach `lowercase(Locale.ROOT)`. Der Index `participant(space_id, name_key)` ist eindeutig.
7. **Name vergeben.** `409 name_taken` mit `suggestion` (Beispiel: „Anna 2“ bei „Anna“ oder „ANNA“). Die Seite setzt den Vorschlag in das Feld. Der Gastgeber kann alte Einträge entfernen. Entfernte Teilnehmer bekommen den Schlüssel `#revoked:<id>` und geben den Namen frei.
8. **Fester Port pro Space.** `space.port` wird beim ersten Start gewählt und bei jedem Start wiederverwendet (`ServerSetup.port`). Ist er belegt, wählt der Server einen freien Port. Das Feld `RunState.Running.addressChanged` zeigt dann die Meldung „Neue Adresse. Gäste brauchen den neuen QR-Code.“. Siehe Nachtrag zu ADR 0017.
9. **Gastgeber-Teilnehmer.** `role = HOST`, Anzeigename „Gastgeber“ in der Datenbank, die Oberfläche übersetzt über die Rolle. Der Token wird bei jedem Start zufällig gewürfelt und nur im Speicher der Sitzung gehalten. In der Datenbank steht sein Hash. App-Uploads (Paket 3.5) laufen mit `uploader_id` des Gastgebers.
10. **Autorisierung.** Reihenfolge: Host-Header (`403`), Netz (`403`), Drosselung (nur `join`, `429`), Authorization (`401`, entfällt für `join` und für die Gäste-Seite), Space-Zustand (`410 space_closed`), Rolle (`403 forbidden` in der Route), Route. Der Vergleich läuft in konstanter Zeit über alle Einträge. Die Liste liegt als Snapshot in einem `AtomicReference` (`LiveAuthorizer`), Änderungen bauen ihn neu.
11. **Rechte.** GUEST: Liste, Download, Upload, eigene Uploads zurückziehen, `GET` und `DELETE` auf `/me`. HOST zusätzlich: Teilnehmer auflisten und entfernen, Einladung erneuern, jedes Medium löschen.
12. **Fehlercodes** (ADR 0014): `invite_invalid` (`403`), `name_invalid` (`422`, mit `reason`: `empty`, `too_long`, `forbidden_character`), `name_taken` (`409`, mit `suggestion`), `space_full` (`409`), `rate_limited` (`429`, mit `Retry-After`).
13. **Drosselung.** Gezählt werden Fehlversuche mit falscher Einladung (`403`). Je Absender höchstens 10 in 10 Minuten, insgesamt höchstens 60 in 10 Minuten. Die Uhr ist injizierbar (`JoinLimiter`). Erfolgreiche Beitritte zählen nicht.
14. **Lingering close** (Pflicht vor 3.5, umgesetzt). Jede frühe Ablehnung (401, 403, 410, 413, 415, 429, 503) antwortet mit `Connection: close`, danach wird der Körper bis `DRAIN_MAX_BYTES` oder `DRAIN_TIMEOUT_MS` verworfen und der Kanal geschlossen. Siehe Messung unten.
15. **Gäste-Webseite W1.** Statische Dateien aus den App-Assets (`share/server/src/main/assets/guest/`): `index.html`, `guest.css`, `guest.js`, `i18n/de.json`, `i18n/en.json`. Kein Inline-Skript, kein Inline-Stil, keine externe Quelle. Jede Antwort trägt die CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer` und `X-Content-Type-Options: nosniff`. Ausgabe nur per `textContent` oder `value`. Die Sprache folgt `navigator.language`, Deutsch sonst Englisch. Der Hinweis (d) steht wörtlich in beiden Sprachen.
16. **Datenmodell, Migration 4 → 5.** `participant`: `space_id`, `name_key`, `created_at`, `revoked_at`, Index `(space_id, name_key)` eindeutig. `space`: `invite_hash`, `invite_created_at`, `invite_rotated_at`, `port`. Alle Defaults stehen in `@ColumnInfo(defaultValue)` und in der Migration gleich. Bestehende Teilnehmer stammen nur aus dem Debug-Einstieg und bekommen den Platzhalter-Schlüssel `#legacy:<id>`.
17. **QR-Bibliothek.** ZXing core 3.5.4, Apache-2.0. Lizenz: [LICENSE von zxing/zxing](https://github.com/zxing/zxing/blob/master/LICENSE). Im Version Catalog als `zxing-core`, nur im Feature-Modul `feature:space`. Die Größe der APK steht unten.

## Abweichungen und Präzisierungen

- **Fragment statt Token (ADR 0013).** Das Fragment trägt das Einladungsgeheimnis, nicht den persönlichen Token. Der Token kommt erst mit der Beitrittsantwort. Das folgt der Entscheidung 1. ADR 0013 und 0014 bekommen einen Nachtrag.
- **Ablauf nach der Authentifizierung (ADR 0017).** Bisher antwortete ein abgelaufener Space jeder Anfrage mit `410`, auch ohne Schlüssel. Nach Entscheidung 10 kommt die Authentifizierung vor dem Ablauf. Ohne gültigen Schlüssel gibt es also `401`, mit gültigem `410`. Der Test `SpaceClosureSocketTest` ist angepasst.
- **Keine Space-Daten vor dem Beitritt.** Name und Farbe des Space sieht ein Gast erst nach dem Beitritt. Begründung: Die Zugriffsprüfung gilt vor jeder Route (ADR 0012). Eine ungeschützte Route, die den Namen nennt, wäre eine neue Ausnahme. Die Gäste-Seite zeigt vor dem Beitritt nur den Text „Dem Space beitreten“. Die Farbe und das Titelbild der Seite folgen in 3.5 oder später. Das ist offen, siehe unten.
- **Browser-Eintrag mit Space-ID im Schlüssel.** Die Space-ID kennt der Browser erst nach dem Beitritt. Deshalb ist die Space-ID Teil des Schlüssels, und der Eintrag wird über die Origin gefunden. Beim Laden wird jeder passende Eintrag mit `GET /me` geprüft. Stimmt die Space-ID nicht, löscht die Seite den Eintrag.
- **Prozess-Neustart erzeugt eine neue Einladung.** Das Einladungsgeheimnis liegt nur im Speicher des Prozesses (`InviteMemory`). In der Datenbank steht sein Hash. Pausen und Wiederaufnahmen im selben Prozess behalten den QR-Code. Nach einem Prozess-Tod ist das Geheimnis weg, der Server erzeugt eine neue Einladung, und der QR-Code ändert sich. Die Gäste bleiben angemeldet. Das ist die Folge von „gespeichert nur als Hash“ und wird im Testkatalog geprüft.
- **Testfall Escaping.** Die Namensregeln verbieten `<`, `>`, `=` und `/`. Der Beispielname `<img src=x onerror=alert(1)>` wird deshalb mit `422` abgelehnt und nicht gespeichert. Der Test prüft das (`markupInANameIsRefusedAndNothingIsStored`). Die Ausgabe-Sicherheit prüft der statische Test, der `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` und `eval` in der Seite ausschließt.
- **Drosselung zählt Fehlversuche.** Entscheidung 13 lässt offen, ob erfolgreiche Beitritte zählen. Sie zählen nicht, sonst würde ein voller Space mit 50 Gästen die Obergrenze selbst sperren.
- **Keep-Regel für den Serverpfad (Abweichung von ADR 0017).** Seit dem Umbau der Routen entfernte R8 im Release-Build die Klasse `KtorLocalServer`, und `checkReleaseServerPipeline` brach ab. Der Basisstand f52fb6a war grün. Die Ursache im R8-Verhalten ist nicht vollständig geklärt. Die Option `-whyareyounotkeeping` kennt die verwendete R8-Version nicht. Umgesetzt ist eine enge Keep-Regel nur für `KtorLocalServer` (`public *`) in `share/server/consumer-rules.pro`. Die Prüfung ist danach grün. ADR 0017 hatte Keep-Regeln für den Serverpfad ausgeschlossen. Offen: Ob der übrige Serverpfad im Release vollständig erhalten bleibt. Die Prüfung deckt nur die genannten Klassen ab.
- **Verwerfen bis 64 MiB statt 1 MiB.** Siehe Messung. Die harte Grenze ist die Zeit von 2 s. Die Bytegrenze liegt über dem Upload-Limit der Tests. Dieser Punkt steht auch im Nachtrag zu ADR 0014.

## Messung: lingering close und der Widerspruch aus ADR 0014

Gemessen auf dem Windows-PC (Loopback), nicht auf einem Gerät. Test: `LingeringCloseSocketTest` (64 MiB Körper, Antwort vor dem Ende des Körpers).

| Variante | Ergebnis |
|---|---|
| Verwerfen bis 1 MiB, dann Kanal schließen | Statuszeile fehlt (RST). Der Client sieht keine Antwort. |
| Verwerfen bis 64 MiB oder 2 s, dann Kanal schließen (umgesetzt) | Statuszeile kommt an. 401 und 413 sind geprüft. |
| Ohne Verwerfen (`drainMaxBytes = 0`) | Statuszeile fehlt. Die Gegenprobe belegt, dass das Verwerfen nötig ist. |

Der Widerspruch aus ADR 0014 („64 MiB in 0,14 s gelesen“ und „Stau ab ca. 1 MiB“) löst sich so auf: Die 0,14 s sind der Durchsatz des Loopback, wenn der Server liest. Der „Stau“ entsteht, wenn der Server bei der Bytegrenze aufhört zu lesen. Der Client blockiert dann beim Senden, und beim Schließen mit ungelesenen Bytes sendet Windows einen RST. Dabei geht die Antwort verloren. Die Bytegrenze bestimmt also, wann der Stau eintritt. Die Zeitgrenze von 2 s begrenzt die Dauer des Verwerfens.

Folge für Paket 3.5: Die Gäste-Seite prüft die Größe vor dem Senden. Auf dem Weg im WLAN sind 2 s Verwerfen nicht immer genug für große Körper. Ein Upload, der ohnehin abgelehnt würde, soll gar nicht erst gesendet werden.

## Umsetzung

- **`share:space`**: `participants/` mit `ParticipantName` (Regeln, Schlüssel, Vorschlag), `Secrets` (Erzeugung, Hash, Vergleich), `ParticipantRoster` (Beitritt, Erneuern, Entfernen, Gastgeber). `SpaceVault` bündelt die Schreibvorgänge unter dem Commit-Lock. Migration `MIGRATION_4_5`.
- **`share:server`**: `LiveAuthorizer`, `JoinLimiter`, `ActiveUploads`, `SpaceAccess` (Sitzung, `ActiveSpaces` für die Oberfläche), `GuestSite` (Dateien und CSP), `ServerPipeline` (Reihenfolge, `refuseEarly`, `drainRequestBody`), `SpaceRoutes` (Beitritt, `/me`, Gastgeber-Routen, Upload mit Abbruch). Der feste Port wirkt im `SpaceRunner` (`setup.port`, `savePort`, `addressChanged`).
- **`feature:space`**: `InviteQr` (ZXing, Bitmap, Ruhezone), `KeepScreenBrightWhileShown`, `SpaceHostActions` (Sitzung oder Vault), `SpaceHostSections` (Einladung, Teilnehmer, Dialoge), ViewModel und Bildschirm.
- **Tests**: Namensregeln, Geheimnisse, Teilnehmer und Vault (`ParticipantRosterTest`), Migration 4 → 5 (`SpacesDatabaseMigrationToVersionFiveTest`), Drosselung, Zugriffsprüfung, Beitritts-API (`JoinApiTest`), lingering close über Sockets (`LingeringCloseSocketTest`), Gäste-Seite (`GuestSiteTest`), Port (`SpaceRunnerTest`), QR (`InviteQrTest`), Goldens je Klasse (`SpaceHost*ScreenshotTest`).

## Testkatalog (Auszug, vollständig in der PR-Beschreibung)

Siehe die Liste in der PR-Beschreibung von #35. Sie enthält die Schritte für das Gerät und den Browser am PC.

## Offene Punkte

- Titelbild und Farbe auf der Gäste-Seite vor dem Beitritt (siehe Abweichungen). Das Titelbild folgt mit einer Route, die nur nach dem Beitritt erreichbar ist.
- Die Gäste-Seite ist nur durch die statische Prüfung und die Server-Tests abgedeckt. Ein Test im Browser fehlt. Die Datei `guest.js` ist syntaktisch geprüft, aber nicht ausgeführt.
- Gerätetest mit dem Testkatalog. Nicht Teil dieses ADR.
- Der Gastgeber-Upload aus der App (3.5, System-Fotoauswahl, Kopie mit Standort-Entfernung) ist in `PLAN-M3.md` eingeplant.

## Quellen

- [ZXing](https://github.com/zxing/zxing) (Lizenz: [LICENSE](https://github.com/zxing/zxing/blob/master/LICENSE), Apache-2.0).
- ADR 0012, 0013, 0014, 0016, 0017 im Repo.
