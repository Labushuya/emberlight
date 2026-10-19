# ADR 0013: Schutz gegen Manipulation (Meilenstein 3, Paket 3.1)

Status: Angenommen, 18.10.2026. Umsetzung in Paket 3.1a, siehe Abschnitt „Umsetzung 3.1a“. Dort stehen die Entscheidungen zu Engine (CIO) und Grenzwerten (bestätigt am 18.10.2026) sowie die Abweichungen vom Entwurf.

Quellen sind mit [Q1] bis [Q24] markiert, die Liste steht am Ende. Was nicht belegt werden konnte, ist als „Vermutung“ gekennzeichnet. Android-API-Fakten sind am lokalen SDK geprüft (Abschnitt „Android-API-Fakten“).

## Kontext

Der Space läuft auf dem Gerät des Gastgebers. Gäste laden Medien hoch und herunter, über einen lokalen Ktor-Server im vorhandenen WLAN (ADR 0012, [KONZEPT.md](../KONZEPT.md), Abschnitt Spaces). Der Server muss abwehren, dass Fremde, manipulierte Dateien oder fremde Webseiten Schaden anrichten. Die Entscheidungen hier gelten für Paket 3.1 und die Schnittstelle, die 3.4 und 3.5 nutzen.

Dieses ADR beschreibt die Abwehr. Die Schnittstelle selbst (Routen, Statuscodes) steht in [ADR 0014](0014-space-speicher-und-server-api.md).

## Bedrohungsmodell

- Teilnehmer sind authentifiziert, aber nicht vertrauenswürdig. Ein Teilnehmer darf also Dateien hochladen, die nicht das sind, was sie vorgeben. Er darf keine fremden Medien zerstören, außer über die erlaubten Wege.
- Fremde im selben WLAN sind nicht authentifiziert. Sie dürfen nichts außer `401` bzw. `403` bekommen.
- Bösartige Webseiten im Browser eines Teilnehmers: DNS-Rebinding und Cross-Origin-Anfragen.
- Andere Apps auf dem Gastgeber-Gerät werden durch die App-Sandbox ausgeschlossen. Das Gerät selbst gilt als vertrauenswürdig.
- Root und physischer Zugriff liegen außerhalb des Rahmens.

## Netz

### Bindung und Subnetz

- Der Server bindet an die IPv4-Adresse der aktiven WLAN-Schnittstelle, nicht an `0.0.0.0`. Ktor CIO nimmt den Host als Parameter (`HttpServerSettings(host = …)`, CIO-Engine, [Q1]). Heute steht `BIND_HOST = "0.0.0.0"` in `KtorLocalServer.kt`. Das ändert 3.1a.
- Zusätzlich lehnt der Server Anfragen ab, deren Absender-Adresse nicht im Subnetz der WLAN-Schnittstelle liegt (Präfixlänge aus der `LinkAddress`).
- Hotspot und fremdes WLAN kommen später. Der eigene Hotspot (LocalOnlyHotspot) gehört laut [CLAUDE.md](../../CLAUDE.md) zu Meilenstein 4.

### WLAN-Schnittstelle unter Android

Befund:

- Der Connectivity-Leitfaden zeigt `requestNetwork()` bzw. `registerNetworkCallback()` mit einer `NetworkRequest`, die `TRANSPORT_WIFI` enthält. Die Callbacks `onLost()` („indicates that the device has lost connection to the network“) und `onCapabilitiesChanged()` sind dort beschrieben [Q7].
- Die Referenzseiten für `ConnectivityManager.NetworkCallback`, `LinkProperties` und `NetworkCapabilities` konnten nicht gelesen werden (nur Navigation). Ebenso ließ sich der AOSP-Quelltext nicht abrufen. Die Fakten sind inzwischen am lokalen SDK geprüft (siehe „Android-API-Fakten“).
- **Geprüft am lokalen SDK** (Platform `android-37.0`, 18.10.2026): `onLinkPropertiesChanged(Network, LinkProperties)` existiert in `ConnectivityManager.NetworkCallback` [Q18]. `LinkProperties.getLinkAddresses()` liefert `List<LinkAddress>`, `LinkAddress` hat `getAddress()` und `getPrefixLength()` [Q18]. `registerNetworkCallback(NetworkRequest, NetworkCallback)` trägt `@RequiresPermission(ACCESS_NETWORK_STATE)` [Q19]. Das Manifest von `share:server` deklariert `ACCESS_NETWORK_STATE` nicht. Es enthält `CHANGE_NETWORK_STATE` und `ACCESS_LOCAL_NETWORK` ([Repo: `share/server/src/main/AndroidManifest.xml`]).
- **Folge**: `ACCESS_NETWORK_STATE` ist als Manifest-Aufgabe für 3.1a vorzusehen (Umfang 3.1a, ADR 0014).

Vorschlag:

- Ein `NetworkCallback` mit `TRANSPORT_WIFI` liefert die aktive WLAN-Schnittstelle. Daraus IPv4-Adresse und Präfixlänge (`Inet4Address`).
- **Entschieden (18.10.2026), Paket 3.1a**: Bei `onLost` des WLAN-Netzes oder bei Wechsel der WLAN-Schnittstelle bzw. ihrer IPv4-Adresse (auch ein DHCP-Wechsel im selben WLAN) stoppt der Server die Annahme. Das ist der sichere Standard. Gäste verlieren die Verbindung, der Gastgeber bekommt eine Benachrichtigung. Der Server startet nicht selbst neu.
- **Entschieden (18.10.2026), Paket 3.3**: Automatisches Fortsetzen, wenn dasselbe WLAN mit derselben IPv4-Adresse zurückkommt. Sonst Hinweis an den Gastgeber. Die URL und der QR-Code ändern sich dann, der Gastgeber startet bewusst neu. Die Zugangsschlüssel bleiben in beiden Fällen gültig.

### Host-Header-Prüfung (DNS-Rebinding)

- Erlaubt ist genau `Host: <eigene IPv4>:<Port>`. Jeder andere Wert ergibt `403` ohne Body.
- Die IP wird nach dem Start aus der Schnittstelle gelesen, der Port aus dem Engine-Ergebnis.

### Cross-Origin

- Keine CORS-Header. Ein Browser lässt damit keine fremden Webseiten die Antworten lesen.
- Einfache Cross-Origin-Anfragen ohne `Authorization` scheitern an der Zugriffsprüfung (`401`).
- Preflight (`OPTIONS`) bekommt keine Erlaubnis. Er scheitert ebenfalls.

### Reihenfolge der Prüfungen

Die Zugriffsprüfung läuft in dieser Reihenfolge, bevor eine Route oder ein Plugin antwortet:

1. Protokoll (Eingang, Route-Template, ohne Header und Token)
2. Host-Prüfung
3. Netzprüfung (Absender im Subnetz)
4. Authorization (siehe unten)

Befund: Heute hängt die Authorization-Prüfung an der Phase `ApplicationCallPipeline.Call` (Interceptor in `KtorLocalServer.kt`). `PartialContent` ist vorher installiert. Ktor führt die Phasen in der Reihenfolge `Setup`, `Monitoring`, `Plugins`, `Validators`, `Call`, `Fallback` aus [Q4]. Ein Plugin in `Plugins` könnte also vor der Prüfung antworten. Vorschlag: Host-, Netz- und Authorization-Prüfung in `Setup` verlegen. Der Test „unautorisiert liefert 401, auch wenn ein Plugin antworten würde“ (ADR 0012) prüft das.

## CIO-Grenzen (Ktor 3.6.0)

Die Recherche gilt dem Quelltext der Artefakte `ktor-server-cio-jvm`, `ktor-http-cio-jvm`, `ktor-server-core-jvm` und `ktor-server-netty-jvm` in Version 3.6.0 [Q1 bis Q4].

| Grenze | CIO 3.6.0 | Fundstelle |
|---|---|---|
| Gleichzeitige Verbindungen | **Keine Obergrenze.** Die Accept-Schleife nimmt Verbindungen ohne Zähler an. | `backend/HttpServer.kt` |
| Idle-Timeout | Konfigurierbar, Standard 45 s. Wirkt nur im Writer-Loop, also beim Warten auf die nächste Antwort. | `CIOApplicationEngine.kt`, `backend/ServerPipeline.kt` |
| Header-Zeile | Maximal 8192 Byte pro Zeile (`HTTP_LINE_LIMIT`). | `ktor-http-cio`, `HttpParser.kt` |
| Header-Gesamtgröße und Anzahl | **Keine Grenze.** Die Schleife in `parseHeaders` liest, bis eine Leerzeile kommt. | `HttpParser.kt` |
| Request-Lese-Timeout (Kopf und Body) | **Keins.** `parseRequest` läuft ohne Timeout. | `backend/ServerPipeline.kt` |
| Chunk-Größenzeile | Maximal 128 Zeichen. | `ChunkedTransferEncoding.kt` |
| Body-Limit | **Keins.** `formFieldLimit` gilt nur für Multipart. | Spike-Befund (ADR 0012) |
| Antwort-Schreib-Timeout | Nicht konfigurierbar. | `CIOApplicationEngine.Configuration` (nur zwei Felder) |

Folgerung: Die Pflicht „explizite CIO-Grenzen“ (ADR 0012) lässt sich mit CIO allein nicht erfüllen. Die Grenzen für Kopf und Verbindungen wirken, bevor unser Code läuft. Eigene Grenzen können nur auf der Ebene der Anfrage greifen, also für Body-Lesen und Parallelität.

Alternative: Ktor Netty 3.6.0 bietet die fehlenden Grenzen. `maxHeaderSize`, `maxInitialLineLength`, `maxChunkSize`, `requestReadTimeoutSeconds` (Standard 0, also aus), `responseWriteTimeoutSeconds` (Standard 10) und `runningLimit` (Standard 32) sind konfigurierbar [Q3]. Netty weicht von CLAUDE.md ab, das CIO vorgibt. Die Abweichung ist nach CLAUDE.md begründbar, braucht aber eine Messung (APK-Größe, Lauf auf Android 8 und auf dem Testgerät).

**Status der Engine-Entscheidung: offen, Messung siehe „Messung Engine“ unten.** Der Entwurf legt sich nicht fest.

### Startwerte (vorgeschlagen, nicht bestätigt; gelten für CIO und Netty)

Die Werte bleiben „vorgeschlagen“, bis die Nutzerin bzw. der Nutzer sie bestätigt. Erst dann steht „Startwerte, bestätigt am …“ im Dokument.

| Grenze | Startwert (vorgeschlagen) | Begründung |
|---|---|---|
| `connectionIdleTimeoutSeconds` (CIO) | 15 s | Standard 45 s ist für Gäste mit Browser lang genug, verlängert aber die Zeit, in der Verbindungen offen bleiben. Vorschlag. |
| Gleichzeitige Anfragen gesamt | 16 | Entspricht der Größenordnung von Netty `runningLimit` (32, halbiert). Eigener Zähler (Semaphore) über alle Routen. Vorschlag. |
| Gleichzeitige Uploads gesamt | 4 | Schreibvorgänge auf dem Gerät sind die teuersten Operationen. Vorschlag. |
| Gleichzeitige Uploads pro Teilnehmer | 2 | Verhindert, dass ein Teilnehmer alle Plätze belegt. Vorschlag. |
| Leerlauf beim Lesen des Bodys | 30 s ohne Daten, dann Abbruch | Eigener Timeout pro Lesevorgang. Umsetzbar, weil der Body in unserem Code gelesen wird. Vorschlag. |
| Gesamtdauer eines Uploads | 30 min | Deckt 2 GiB bei schwacher Verbindung. Vorschlag, zu messen. |
| Header-Zeile | 8192 Byte | Fest in CIO (belegt). Für Netty nicht geprüft (Vermutung: gleicher Standard). |
| Chunk-Größenzeile | 128 Zeichen | Fest in CIO. |
| Lese-Timeout Kopf (nur Netty) | 10 s | Schließt langsame Kopfübertragungen. Nur mit Netty wirksam. |

## Authentifizierung

Umsetzung in 3.4, die Schnittstelle steht schon in 3.1.

- Bearer-Token nur im Header `Authorization: Bearer <token>`. Der Token ist 256 bit Zufall.
- In der Datenbank steht nur der SHA-256-Hash des Tokens. Der Vergleich geschieht über `MessageDigest.isEqual` auf die Hashes. Die Java-Dokumentation sagt, dass die Laufzeit nur von der Länge des ersten Arguments abhängt, nicht vom Inhalt [Q16]. Bei gleich langen Hashes ist das ein konstanter Vergleich.
- Ein SHA-256 ohne Salt und ohne Schlüsselableitung reicht, weil der Token selbst 256 bit Zufall ist. Ein Angreifer kann ihn nicht erraten.
- Mehrere `Authorization`-Header ergeben `401`, der erste Wert wird nicht genommen.
- `/health` bleibt hinter der Zugriffsprüfung (wie ADR 0012). Die Gäste-Webseite schickt den Token als Header mit, auch für den Health-Check (Entscheidung 18.10.2026, ADR 0014).
- `RequestAuthorizer` liefert einen `Principal` (participantId, Rolle `HOST` oder `GUEST`) statt `Boolean`. Der bisherige Typ `isAuthorized(String?): Boolean` ist in 3.1a zu ersetzen. `DenyAll` bleibt der Standard.
- Gäste-Webseite (3.5): Der Token steht im URL-Fragment des QR-Links (`…#<token>`). Ein Fragment wird nicht an den Server gesendet. Laut Referrer-Policy wird es beim Referer entfernt [Q14, §8.4]. Laut RFC 9110 ist das Fragment nicht Teil der http-Definition [Q15, §4.2.5]. Die Seite liest den Token mit JavaScript und setzt ihn als Header. Im Server-Log und im Referer taucht er nicht auf. Lokal bleibt er im Verlauf des Browsers. Das Verhalten wird in 3.5 auf Geräten geprüft.

### Warum keine CSRF-Angriffsfläche

- Der Server setzt keine Cookies. Der Browser schickt also keine Anmeldedaten automatisch mit.
- Der Token liegt im Header `Authorization`. Ein fremdes Formular kann diesen Header nicht setzen.
- Fremde Skripte können die Antwort nicht lesen (keine CORS-Header), und Preflight scheitert.
- Eine Anfrage ohne `Authorization` wird abgewiesen.

## Uploads

- Roher Request-Body, eine Datei pro Anfrage, `Content-Type: application/octet-stream`. Kein Multipart, siehe ADR 0012.
- Hartes Byte-Limit beim Streamen. Der Server zählt die gelesenen Bytes selbst, unabhängig von `Content-Length`. Bei Überschreiten `413`. Die Teildatei wird gelöscht.
- Client-Dateiname nur als bereinigte, längenbegrenzte Metadatei (Details in ADR 0014). Nie als Pfad.
- Ablauf:
  1. Temporäre Datei im Space-Verzeichnis anlegen (Zufallsname).
  2. Beim Streamen Byte-Limit zählen und SHA-256 berechnen.
  3. Inhaltsprüfung (siehe unten) am Anfang der Datei.
  4. Dedupe-Prüfung.
  5. Atomar umbenennen (`Files.move` mit `ATOMIC_MOVE`), Eintrag in den Index schreiben.
- Bei Fehler oder Abbruch (Verbindungsabbruch, Timeout, Limit, Inhalt unzulässig) wird die Teildatei gelöscht.
- Speicherlimit und Mindest-Restspeicher werden vor dem Schreiben geprüft (siehe Grenzen).

### Dateigrenzen

Die Startwerte sind vorgeschlagen (siehe oben).

| Grenze | Startwert (vorgeschlagen) | Begründung |
|---|---|---|
| Bild | 50 MiB | Fotos vom Handy sind meist deutlich kleiner (Vermutung, zu messen). |
| Video | 2 GiB | Der Spike nutzte 2 GiB (ADR 0012). Videos wachsen mit Bitrate und Länge, also ist die Grenze großzügig. Vermutung, zu messen. |
| Speicherlimit des Spaces | Parameter (Standard 4 GiB, Vorschlag) | Gesetzt vom Gastgeber in 3.2. In 3.1 ist es ein Parameter. Ab 80 % eine Warnung in der API-Antwort. Ab 100 % `507`. |
| Mindest-Restspeicher des Geräts | 2 GiB (Vorschlag) | Der Restspeicher wird per `StatFs` auf dem Space-Verzeichnis gemessen. Unterschreitung `507` mit eigenem Code. |
| Parallele Uploads | siehe Startwerte | Oben. |

## Inhaltsprüfung

Die Erkennung geschieht nach dem Inhalt der ersten Bytes. Endung und `Content-Type` des Clients zählen nicht.

### Signaturen

| Format | Signatur | Quelle |
|---|---|---|
| JPEG | `FF D8 FF` am Anfang | libmagic `jpeg`, `0 belong&0xffffff00 0xffd8ff00` [Q8] |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | libmagic `images`, `0 string \x89PNG\x0d\x0a\x1a\x0a` [Q8] |
| GIF | `GIF8` am Anfang (`GIF87a`/`GIF89a`) | libmagic `images`, `0 string GIF8` [Q8] |
| WebP | `RIFF` bei Offset 0, `WEBP` bei Offset 8 | Google WebP RIFF-Container [Q10] |
| HEIC/HEIF | Box `ftyp` bei Offset 4, Hauptmarke `heic`, `heix`, `mif1` | libmagic `animation` [Q8], Nokia HEIF [Q12] |
| AVIF | `ftyp` bei Offset 4, Hauptmarke `avif` | libmagic `animation` [Q8], MP4-Registry `avif` [Q9] |
| MP4 | `ftyp` bei Offset 4, Hauptmarken `isom`, `iso2`, `mp41`, `mp42`, `avc1` | libmagic [Q8], MP4-Registry [Q9] |
| 3GP | `ftyp` bei Offset 4, Marken `3gp4`, `3gp5`, `3gp6`, `3ge6`, `3gg6` u. a. | libmagic `animation`, `3gp…`-Einträge [Q8] |
| MOV | `ftyp` mit Marke `qt  ` (zwei Leerzeichen) | libmagic [Q8], MP4-Registry [Q9] |
| MOV ohne `ftyp` | Erste Box bei Offset 4 ist `moov`, `mdat` oder `wide` | libmagic `animation`: `moov`, `mdat`, `wide` mit „Apple QuickTime“ [Q8] |

Hinweise:

- HEIC-Sequenzen (`msf1`, `hevc`, `hevx`) und AVIF-Sequenzen (`avis`) sind nicht erlaubt. Vorschlag.
- MOV ohne `ftyp`: libmagic erkennt `moov`, `mdat` und `wide` an Offset 4 als QuickTime-Dateien. Vorschlag: zulassen, aber nur, wenn die erste Box einer dieser Typen ist und die Box-Größen plausibel sind. Sonst `415`.
- `free` ist in libmagic auskommentiert. Eine Datei, die mit `free` beginnt, wird nicht als MOV akzeptiert.
- Die Apple-Spezifikation (QuickTime File Format) konnte nicht gelesen werden. Die Aussagen zu `ftyp` und MOV ohne `ftyp` stützen sich deshalb auf libmagic und die MP4-Registry.

### Strukturprüfung ohne Volldekodierung

- Erste Box muss `ftyp` sein (für HEIC, AVIF, MP4, 3GP, MOV mit `ftyp`). Vorschlag. Die Position `ftyp` bei Offset 4 ist durch libmagic belegt. Dass `ftyp` die erste Box sein muss, ist ein Vorschlag, der Spezifikationstext ist nicht abgerufen.
- Größen aller Top-Level-Boxen plausibel: Größe ≥ 8, Summe ≤ Dateigröße, `size == 1` nur mit 64-Bit-Länge, `size == 0` nur für die letzte Box.
- Die Kette der Top-Level-Boxen wird bis zum Dateiende durchlaufen. Dabei wird nur gelesen, nichts dekodiert.
- Für JPEG: `FF D8 FF` am Anfang reicht für 3.1. Eine Prüfung auf `FF D9` am Ende entfällt. Vermutung: Kameras hängen Daten nach dem Dateiende an, das ist nicht belegt und auch kein Sicherheitsproblem, solange die Auslieferung nach dem erkannten Typ geschieht.

### Keine Volldekodierung in 3.1

Begründung: Dekodierung ist aufwendig, anfällig und kann Speicher sprengen. Die Gefahr für den Gast liegt eher beim Ausliefern als beim Speichern. Das wird durch `nosniff`, korrekten `Content-Type` und den Download als Anhang abgefangen (ADR 0014). Eine Volldekodierung gehört nicht in 3.1.

Hinweis für 3.5: Vorschaubilder nur mit begrenzter Zielgröße dekodieren (Dekompressionsbomben). Die Begrenzung legt die Ziel-Kantenlänge fest, vor dem Dekodieren. Die maximale Pixelzahl steht in 3.5 fest.

### Gespeicherter MIME-Typ

Der MIME-Typ, den der Server speichert und ausliefert, stammt aus der Erkennung. Der Client-`Content-Type` wird nie übernommen.

### Polyglot

Eine Datei, die zugleich gültiges Bild und etwas anderes ist, wird als Bild gespeichert. Das ist kein Problem, solange die Auslieferung mit dem erkannten Typ, `nosniff` und `Content-Disposition: attachment` geschieht. Test in 3.1a: Datei mit falscher Endung, Datei mit angehängtem HTML nach gültigem PNG (Ausgabe bleibt `image/png`).

## Dedupe

- Pro Space. Der Schlüssel ist der SHA-256 der gespeicherten Bytes.
- Ein zweiter Upload desselben Inhalts liefert die vorhandene Medien-ID mit dem Hinweis „bereits vorhanden“ (`200` statt `201`). Der Uploader bleibt der erste.
- Ein Teilnehmer erfährt damit, dass ein identischer Inhalt schon existiert. Alle Medien sind im Space für alle Teilnehmer sichtbar, deshalb ist das kein zusätzlicher Verlust.
- Nach der GPS-Entfernung (3.1b) wird über die verarbeiteten Bytes gehasht. Das muss deterministisch sein: Dieselbe Eingabe führt zu denselben Bytes und damit zum selben Hash.
- In 3.1a ist es unkritisch, weil die Bytes unverändert gespeichert werden. Vor 3.1b gespeicherte Medien behalten ihren Hash.

## IDs

- Medien-IDs sind zufällig, 128 bit, kodiert als Base64url ohne Padding (22 Zeichen). Vorschlag.
- Die ID ist nicht sequenziell und nicht der Hash.
- Anfragen enthalten nur IDs, nie Pfade.
- Kein Überschreiben: Eine vorhandene ID wird nie neu belegt. Eine Kollision wird abgelehnt und neu gewürfelt.

## Löschen

- Der Uploader darf eigene Medien löschen.
- Der Gastgeber darf beliebige Medien löschen.
- Löschen entfernt Datei und Indexeintrag. Die Reihenfolge ist: Index-Eintrag markieren, Datei löschen, Eintrag entfernen. Ein Absturz dazwischen lässt eine markierte Datei zurück, die beim Start bereinigt wird. Vorschlag.

## Speicher

### Ablage

- Unter `noBackupFilesDir/spaces/<spaceId>/`, nicht unter `filesDir`. Das präzisiert ADR 0012 („unterhalb des app-internen Speichers“). ADR 0012 wird dadurch nicht geändert.
- Dateien in `noBackupFilesDir` sind von Auto Backup und von Geräte-zu-Geräte-Übertragung ausgeschlossen. Das steht in der Android-Dokumentation: Auto Backup schließt Dateien aus den Verzeichnissen `getCacheDir()`, `getCodeCacheDir()` und `getNoBackupFilesDir()` aus. Sie lassen sich über XML-Regeln nicht einschließen [Q6].
- Im Repo ist `android:allowBackup="false"` gesetzt, zusätzlich `data_extraction_rules.xml` mit `<cloud-backup>` (alle Domänen ausgeschlossen). Die Datei hat **keinen `<device-transfer>`-Abschnitt**. Laut Android-Dokumentation ist dann die Geräte-zu-Geräte-Übertragung voll aktiv, außer für `no-backup` und `cache` [Q6]. Außerdem ist die Geräte-zu-Geräte-Übertragung bei `allowBackup="false"` laut Dokumentation auf manchen Herstellern nicht abgeschaltet [Q6].
- Folgerung: Der Space-Speicher in `noBackupFilesDir` ist ausgeschlossen, auch ohne Regel. Der Rest der App-Daten (z. B. Datenbank in `databases/`) ist nicht durch `<device-transfer>` geschützt. Vorschlag: Abschnitt `<device-transfer>` mit denselben Ausschlüssen wie `<cloud-backup>` in 3.1a ergänzen.

### Pfad-Prüfung

- Jeder Zielpfad wird kanonisiert und muss unterhalb der Space-Wurzel liegen (`toRealPath`, Vergleich mit der Wurzel).
- Symlinks werden abgewiesen, auf jeder Ebene (`Files.isSymbolicLink` bzw. `NOFOLLOW_LINKS`). Vorschlag, Standardbibliothek.
- Die Space-ID ist eine zufällige ID, kein Pfadbestandteil aus dem Client.

## Grenzen (Zusammenfassung)

- Dateigröße: 50 MiB Bild, 2 GiB Video (oben).
- Speicherlimit des Spaces: Parameter, ab 80 % Warnung, ab 100 % `507`.
- Mindest-Restspeicher: 2 GiB (Vorschlag).
- Parallel: 4 gesamt, 2 pro Teilnehmer. Der Server antwortet bei Überschreiten mit `429`.
- Anfragen gesamt: 16.

## Downloads

- Nur aus dem Space-Speicher, nur über die Medien-ID. Kein Pfad in der Anfrage.
- Antwort: `X-Content-Type-Options: nosniff`, `Content-Type` aus der Erkennung, `Content-Disposition: attachment` mit `filename` und `filename*` nach RFC 6266 (`filename*` in UTF-8 nach RFC 5987). RFC 6266 empfiehlt beides, wenn möglich [Q13, Anhang D]. Der Dateiname ist bereinigt (ADR 0014).
- Range-Anfragen für Videos über das Plugin `PartialContent`. Hinweis für 3.5: Die Range-Antwort muss hinter der Zugriffsprüfung liegen (siehe Reihenfolge).

## Logging

- Route-Templates statt roher URIs (z. B. `/api/v1/media/{id}/content`). Konkrete IDs gehören nicht ins Protokoll.
- Keine Tokens, keine Header, keine Hashes der Tokens.
- IP-Adressen nur, wenn der Server über den Debug-Einstieg gestartet wurde (Flag `debugLogging`, das nur der Debug-Einstieg setzt). Das betrifft die Test-Builds `debug` und `benchmark`. Im Release-Build wird nie eine IP-Adresse protokolliert. Dass der benchmark-Build IP-Adressen protokolliert, ist damit bewusst erlaubt (Nachtrag 19.10.2026, Gerätetest 3.1a).
- Umsetzung in 3.1a: `requestLog` protokolliert das Route-Template, nie den rohen Pfad (`ServerPipeline.kt`).

## Integrität

- Inhaltsadressierte Speicherung erlaubt eine Prüfung: Der gespeicherte Hash muss zur Datei passen.
- Beim Import in die Galerie (3.7) wird der Hash erneut geprüft. Nur bei Übereinstimmung wird importiert.

## GPS-Entfernung (3.1b, nur Analyse)

Standard nach KONZEPT: GPS-Daten werden beim Upload entfernt. Dieser Abschnitt ist nur Analyse. Die Umsetzung (Scrubber, Einwilligung, Reason-Codes, Fundorte je Format) steht in [ADR 0015](0015-entfernung-von-standortdaten.md).

### Was ExifInterface 1.4.2 schreiben kann

- `saveAttributes()` unterstützt nur JPEG, PNG und WebP. Bei anderen Formaten wirft die Methode `IOException` [Q5, `isSupportedFormatForSavingAttributes`, `saveAttributes`].
- HEIC und HEIF sind nur lesbar (Parsing über `getHeifAttributes`). AVIF ist nur lesbar, und zwar erst ab API 31 [Q5, Kommentar zu `getHeifAttributes`].
- AVIF fehlt außerdem in der Liste von `isSupportedMimeType` [Q5].
- MP4 und MOV unterstützt ExifInterface nicht.
- `saveAttributes()` schreibt die ganze Datei neu über eine temporäre Datei. Das ist nicht längenerhaltend. Der Befund gilt für JPEG, PNG und WebP ebenso.
- Aus den Release-Notes: 1.4.0 bis 1.4.2 nennen kein Entfernen von Attributen [Q5].

Folgerung: ExifInterface eignet sich nicht für ein längenerhaltendes Überschreiben. Ein eigener Schreibweg ist nötig.

### GPS in den Formaten

- **JPEG, PNG, WebP**: GPS steckt im EXIF-Segment. Die GPS-IFD wird über den Eintrag `GPSInfo` (0x8825) in IFD0 erreicht [Q11, EXIF-Tabelle]. Die GPS-Tags selbst: `GPSLatitudeRef` 0x0001, `GPSLatitude` 0x0002, `GPSLongitude` 0x0004 [Q11, GPS-Tabelle]. Längenerhaltend: Werte der GPS-Einträge mit Nullen überschreiben. Die Struktur bleibt gleich. Vorschlag, im Spike zu prüfen.
- **XMP** kann ebenfalls Standortdaten enthalten. Vermutung, nicht belegt. In 3.1b prüfen.
- **HEIC und AVIF**: Der EXIF-Teil liegt in einem Item des `meta`-Containers. Den genauen Aufbau (Item-Typ `Exif`, Verweise über `iloc`, Offset-Kopf) konnte ich nicht aus dem Quelltext belegen. Die Nokia-Beschreibung erwähnt Exif-Items nur allgemein [Q12]. **Vermutung**. Vor der Umsetzung gegen ISO/IEC 23008-12 bzw. eine Referenzbibliothek prüfen.
- **MP4 und MOV**: Die Tags für Standort (ExifTool):
  - `©xyz` in `ilst` (Meta-Gruppe ItemList) mit Typ `GPSCoordinates` [Q11, QuickTime]
  - `com.apple.quicktime.location.ISO6709` in `keys` (Meta-Gruppe Keys) mit Typ `GPSCoordinates` [Q11, QuickTime]
  - `©xyz` in `udta` (Gruppe UserData) [Q11, QuickTime, UserData-Tabelle]
  - Aufbau der Boxen (ISO 6709 als Zeichenkette, Länge der Box) ist nicht gegen die Apple-Spezifikation geprüft. Vorschlag: längenerhaltend den Textwert überschreiben (Leerzeichen oder Nullbytes), die Boxen bleiben unverändert.

### Nicht unterstützte oder nicht sicher bereinigbare Dateien

Vorschlag: **ablehnen** mit `422` und einem klaren Code (`location_not_removable`). Begründung: Ein stillschweigender Upload mit Ortsdaten verletzt den Standard aus KONZEPT. Ein Hinweis ohne Einwilligung hat keinen Schutzwert. Eine Einwilligung wird pro Upload über eine ausdrückliche Option gesetzt (KONZEPT: „pro Upload oder in den Einstellungen abschaltbar“). Die Option ist für Gäste nicht standardmäßig gesetzt. Die Umsetzung der Option folgt in 3.3 bzw. 3.5.

### Zwischenzustand 3.1a

Bis 3.1b werden Ortsdaten **nicht** entfernt. Das widerspricht dem Standard in KONZEPT. Offene Entscheidung (siehe unten).

## Android-API-Fakten

Prüfung am lokalen SDK, 18.10.2026. Quellen: `H:\DEV\tools\android-sdk\platforms\android-37.0\android.jar` (Signaturen per `javap`), `data\annotations.zip` (Permission-Annotationen), `data\api-versions.xml` (Einführungsversion). Lokale Quelltexte (`sources`) liegen im SDK nicht vor. Die Aussagen gelten für die Stubs, nicht für das Laufzeitverhalten auf einem Gerät.

| Nr. | Aussage | Ergebnis | Fundstelle |
|---|---|---|---|
| 1 | `registerNetworkCallback(NetworkRequest, NetworkCallback)` braucht `ACCESS_NETWORK_STATE` | **Ja.** `@RequiresPermission` (`androidx.annotation`). Gilt auch für die Überladungen mit `Handler` und `PendingIntent`. | [Q19] `android/net/annotations.xml` |
| 1 | `getActiveNetwork()`, `getLinkProperties(Network)`, `getNetworkCapabilities(Network)` brauchen eine Permission | **Ja**, jeweils `ACCESS_NETWORK_STATE`. | [Q19] `android/net/annotations.xml` |
| 1 | `requestNetwork(...)` braucht eine Permission | Keine Annotation im SDK. Der Entwurf nutzt `registerNetworkCallback`, daher ohne Bedeutung. | [Q19] (kein Eintrag) |
| 2 | `ConnectivityManager.NetworkCallback.onLinkPropertiesChanged(Network, LinkProperties)` | **Ja**, öffentlich. Die Klasse `NetworkCallback` ist ab API 21 eingeführt. | [Q18] `javap`; [Q20] `ConnectivityManager$NetworkCallback since="21"` |
| 3 | `LinkProperties.getLinkAddresses()` liefert `List<LinkAddress>` | **Ja.** Klasse ab API 21. | [Q18] `javap`; [Q20] `LinkProperties since="21"` |
| 3 | `LinkAddress.getAddress()` (`InetAddress`) und `getPrefixLength()` (`int`) | **Ja.** Klasse ab API 21. | [Q18] `javap`; [Q20] `LinkAddress since="21"` |
| 4 | `Context.getNoBackupFilesDir()` | **Ja**, abstrakt, ab API 21. | [Q18] `javap android.content.Context`; [Q20] `getNoBackupFilesDir()Ljava/io/File;` since="21" |
| 5 | `ContextImpl.getDatabasePath` akzeptiert absolute Pfade | **Nicht prüfbar** am SDK: Implementierungsdetail, lokale Quelltexte fehlen. Im 3.1a-Robolectric-Test zu verifizieren (ADR 0014, Room-Pfad). | — |

Folgerungen:

- Für den Netz-Abschnitt braucht 3.1a `ACCESS_NETWORK_STATE` im Manifest von `share:server`. `CHANGE_NETWORK_STATE` reicht dafür nicht aus (Annotation nennt nur `ACCESS_NETWORK_STATE`). Im gebauten Manifest der App ist die Berechtigung bereits vorhanden, eingetragen von `androidx.media3:media3-exoplayer` (Merge-Report der Messung, siehe „Messung Engine“). Die Deklaration in `share:server` bleibt trotzdem nötig, damit das Modul auch ohne Media3 korrekt ist.
- `onLinkPropertiesChanged` ist der richtige Weg, um Adressänderungen zu erkennen (Netzverlust-Regel, siehe Netz-Abschnitt).

## Messung Engine (18.10.2026)

Status: **Messung abgeschlossen, Entscheidung nicht getroffen.** Die Engine-Wahl (CIO oder Netty) trifft die Nutzerin bzw. der Nutzer. Dieser Abschnitt enthält keine Empfehlung im Sinne einer Entscheidung.

### Methode

- Umgebung: Windows 10, Temurin JDK 17.0.19, Kotlin 2.4.21, Ktor 3.6.0 (CIO bzw. Netty), Netty 4.2.17.Final (transitiv über Ktor). Loopback `127.0.0.1`, Server und Client in getrennten JVMs. Zeitmessung mit `System.nanoTime()` auf Clientseite.
- Je Variante ein Lauf (n = 1). Ergebnisse sind Richtwerte, keine statistischen Aussagen.
- Ablauf: Kontrolle (5 × `GET /ok`). Fälle a, b, c gleichzeitig auf eigenen Verbindungen, je bis 120 s. Fall d (2 000 Verbindungen, Probe `GET /ok` alle 10 s über 120 s). Fälle e und f nacheinander. Zusatzfälle g (Body-Pause) und e2 (Header-Gesamtgröße unter dem Zeilenlimit).
- Fall d wurde in einem zweiten Lauf mit Speicherprobe wiederholt (alle 5 s, Server-JVM). Heap wird als „belegt“ ohne erzwungene GC gemessen, das ist eine obere Schranke. Die Latenzen im Abschnitt d unterscheiden sich am Anfang: Der erste Aufruf nach dem Serverstart dauert 48 bis 78 ms (Kaltstart), danach 1,7 bis 2,7 ms.
- Varianten: CIO-Standard. CIO mit `connectionIdleTimeoutSeconds = 15`. CIO mit Idle 15 s und Lese-Timeout pro Chunk (`withTimeoutOrNull(15 s)` um `readAvailable`). Netty-Standard. Netty mit `requestReadTimeoutSeconds = 15`, `runningLimit = 16`, `maxHeaderSize = 8192`, `maxInitialLineLength = 4096` (Option-Namen aus dem Quelltext 4.2.17 bzw. Ktor 3.6.0, [Q22], [Q23]).

### Gemessen

| Fall | CIO-Standard | CIO, Idle 15 s | CIO, Idle 15 s + Lese-Timeout pro Chunk | Netty-Standard | Netty mit Grenzen |
|---|---|---|---|---|---|
| a Verbindung ohne Daten | Schluss bei 45,0 s (EOF) | Schluss bei 15,0 s | Schluss bei 15,0 s | offen bis 120 s | offen bis 120 s |
| b Header, 1 Byte alle 5 s | Schluss bei 45,0 s (52 Byte gesendet) | Schluss bei 15,0 s (46 Byte) | Schluss bei 15,0 s (46 Byte) | offen bis 120 s (67 Byte) | offen bis 120 s (67 Byte) |
| c Body, 1 KiB alle 5 s, `Content-Length: 10485760` | offen bis 120 s (24 684 Byte) | offen bis 120 s | offen bis 120 s | offen bis 120 s | offen bis 120 s |
| d 2 000 stumme Verbindungen, Schluss durch Server | alle 2 000 bei 45 s | alle 2 000 bei 15 s | alle 2 000 bei 15 s | 0 von 2 000 bis 120 s | 0 von 2 000 bis 120 s |
| d Latenz `GET /ok` (warmer Server) | 1,7 bis 2,1 ms (t = 10 bis 40 s, Verbindungen offen) | 2,1 bis 2,2 ms (t = 10 bis 20 s) | 2,2 ms (t = 10 bis 20 s) | 1,9 bis 2,5 ms (t = 10 bis 110 s, alle offen) | 1,8 bis 2,7 ms (t = 10 bis 110 s, alle offen) |
| d Heap belegt, ohne GC (MB) | 13 → 74 (10 s) → 80 | 14 → 73 (15 s) → 79 | 13 → 69 (10 s) → 74 | 13 → 34 (15 s) → 40 (120 s) | 14 → 31 (10 s) → 55 (25 s) → 61 (120 s) |
| d Threads | 8 → 89 bis 90 (Last) → 24 nach Schluss | 8 → 90 → 24 nach Schluss | 8 → 90 → 24 nach Schluss | 8 → 22 (10 s) → 32 (120 s) | 8 → 22 (10 s) → 32 (120 s) |
| e 200 Header mit je 8192 Byte Wert (Zeile 8201 Byte) | 400 (HTTP/1.0) | 400 | 400 | 400 (HTTP/1.1) | 400 |
| e2 200 Header mit je 8009 Byte (Zeilen unter 8192, gesamt 1 601 800 Byte) | 200 OK | 200 OK | 200 OK | 400 | 400 |
| e2 1 000 Header mit je 1009 Byte (gesamt 1 009 000 Byte) | 200 OK | 200 OK | 200 OK | 400 | 400 |
| f Request-Zeile mit 64 KiB Pfad | 400 (HTTP/1.0) | 400 | 400 | 400 (HTTP/1.0) | 400 |
| g Body-Pause 40 s nach 1 KiB | offen bis 40 s, keine Antwort | offen bis 40 s | Antwort (Route 408) bei 15,1 s, Schluss bei 30,1 s | offen bis 40 s | erste Antwort-Bytes bei 15,1 s, Schluss bei 15,1 s (Status nicht protokolliert) |
| Kontrolle `GET /ok` ohne Angriff | Erstaufruf 60 ms, danach 1,3 bis 1,7 ms | 61 ms, danach 1,4 bis 1,8 ms | 60 ms, danach 1,3 bis 1,5 ms | 115 ms, danach 1,4 bis 1,8 ms | 106 ms, danach 1,5 bis 1,9 ms |

Hinweise zur Tabelle: Die Zeilen d „Latenz“, d „Heap“ und d „Threads“ stammen aus einem zweiten Lauf nur für Fall d (Speicherprobe). Kontrolle, a bis c, e und f stammen aus dem ersten Lauf, g und e2 aus einem Zusatzlauf. Bei a bis c wurden Antwort-Bytes nicht protokolliert, die Angabe „offen“ meint nur fehlenden Schluss.

### Build-Kosten Netty (Vergleich mit main, CIO)

Messung mit `:app:assembleRelease` (unsigniert, beide Seiten identisch gebaut). Das Netty-Build ist die Variante ohne `netty-codec-native-quic`, mit den unten genannten Build-Regeln.

- APK main (CIO): 3 652 833 Byte (Wert reproduziert).
- APK Netty: 3 816 317 Byte. Delta +163 484 Byte (+4,5 %).
- Ohne Ausschluss von `netty-codec-native-quic` bricht `mergeReleaseJavaResource` ab. Die fünf plattformspezifischen Jars enthalten dieselben Pfade (`META-INF/license/LICENSE.webbit.txt`, `META-INF/native-image/...`).
- Mit dem Ausschluss bricht `mergeReleaseJavaResource` wegen `META-INF/INDEX.LIST` in 18 bis 23 Netty-Jars ab. Die Konfiguration schließt zusätzlich `META-INF/io.netty.versions.properties` und `META-INF/native-image/**` aus. Diese beiden Ausschlüsse wurden nicht einzeln auf Notwendigkeit geprüft.
- R8 bricht ohne Regeln mit 45 fehlenden Klassen ab (TLS-Provider tcnative, Conscrypt, BouncyCastle, Log4j, `jdk.jfr`, `javax.naming`). Die von R8 erzeugte Datei `missing_rules.txt` enthält 45 `-dontwarn`-Zeilen. Mit diesen Zeilen in `share/server/consumer-rules.pro` baut die Konfiguration. Eine Warnung zu `reactor.blockhound` bleibt (nicht fatal).
- Native Bibliotheken: Die APK enthält keine Netty-`.so`-Dateien. `lib/` enthält nur `libandroidx.graphics.path.so` und `libdatastore_shared_counter.so` in vier ABIs. Ein Vergleich von `lib/` mit main wurde nicht separat gemessen.
- Manifest-Berechtigungen: Netty bringt keine. Die Netty-Jars enthalten kein `AndroidManifest.xml`. Eine Vergleichsliste der main-APK wurde nicht separat erhoben. `ACCESS_NETWORK_STATE` steht bereits im gemergten Manifest der App, eingetragen von `androidx.media3:media3-exoplayer` (Merge-Report der Netty-Variante).
- Lint: `:share:server:lintRelease` und `:share:server:checkServerIsolation` sind grün. `allWarningsAsErrors` (Kotlin) meldet nichts. Lint für die gesamte App wurde nicht ausgeführt, außer `lintVitalRelease` im Release-Build.
- Tests: Die 29 bestehenden Server-Unit-Tests (`KtorLocalServerTest` 10, `ServerLocksTest` 6, `SpaceServerServiceTest` 13) laufen mit Netty grün.
- Gerätelauf: nicht erfolgt (kein Gerät angeschlossen). Die Messung oben gilt für JVM und Build, nicht für Android-Laufzeit.

### Deutung (nicht gemessen oder nur indirekt belegt)

- **CIO, Verbindungsschutz**: Der Idle-Timeout schließt stumme Verbindungen (a, d) und Header-Tropfen (b) am Ende des Timeouts. Die Schwelle bei Tropfen gilt also für die Zeit bis zum vollständigen Header, nicht pro Byte. Die Ursache liegt vermutlich im Schreibschleifen-Timer, der vom Verbindungsbeginn läuft. Quelle: [Q24], Messung b.
- **CIO, Körperschutz**: Ein Körper, der mit Pausen unter dem Timeout tröpfelt (c), bleibt offen. Ein Lese-Timeout pro Chunk wirkt nur bei echten Pausen (g). Für c braucht es eine eigene Mindestrate über ein Zeitfenster (Vorschlag, nicht gemessen).
- **Netty, Schutz vor stillen Verbindungen**: `requestReadTimeoutSeconds` schließt Pausen innerhalb eines Körpers (g). Stille Verbindungen vor dem ersten Byte (a, d) schließt es nicht. Das Serverprotokoll zeigt 2 001 `ReadTimeoutException`, die bis zum Pipeline-Ende laufen und nicht behandelt werden. Im Quelltext leitet `NettyHttp1Handler` die Ausnahme ohne aktive Anfrage nur weiter (`fireExceptionCaught`) [Q23]. Die Ursache folgt daraus, ist aber nicht im Debugger verifiziert.
- **Header-Gesamtgröße**: CIO begrenzt sie nicht (e2: 200 OK bei 1,6 MB). Netty begrenzt sie über `maxHeaderSize` (Standard 8192, e2: 400). Die Werte der Variante „mit Grenzen“ entsprechen den Netty-Standardwerten [Q22]. Die Einstellungen ändern in dieser Version also nichts.
- **Zeilenlimit**: Beide Engines lehnen Zeilen über 8192 Byte (CIO) bzw. eine Request-Zeile über 4096 Byte (Netty) mit 400 ab (e, f). Der Statuscode 400 ist bei allen Varianten gleich, eine 431 oder 414 kam nicht vor.
- **Speicher und Threads**: CIO hat während der Last mehr Threads (bis 90) und gibt sie nach dem Schluss zurück (24). Netty wächst im Heap langsam weiter (bis 40 MB bzw. 61 MB), die Threads bleiben bei 32. Die Werte sind ohne GC-Erzwingung, also nicht belastbar für den Gerätespeicher. Der stärkere Heap-Anstieg bei Netty mit Grenzen (31 auf 55 MB bis 25 s) dürfte zum Teil aus den 2 001 Warnungen mit Stack-Trace stammen. Das ist nicht isoliert geprüft.
- **Latenz**: In allen Varianten blieb `GET /ok` bei 1,7 bis 2,7 ms, auch mit 2 000 offenen Verbindungen. Das gilt für Loopback mit n = 1 und sagt nichts über WLAN aus.
- **Build-Aufwand**: Netty kostet +163 KB APK und drei Build-Eingriffe (Ausschluss QUIC, Packaging-Ausschlüsse, 45 `-dontwarn`-Zeilen). Die Regeln hängen an Netty 4.2.17. Bei Ktor-Updates müssen sie erneut geprüft werden.
- **Android-Einschränkungen von Netty** (aus Quellen, nicht am Gerät geprüft):
  - Netty 4.2 setzt Java 8 voraus [Q21]. Für minSdk 26 ist das unkritisch.
  - `java.lang.invoke.VarHandle` ist erst ab API 33 verfügbar [Q20]. Netty 4.2.17 nutzt es in `PlatformDependent`, `VarHandleFactory` und weiteren Klassen. Der Lade- und Initialisierungspfad fängt Fehler mit `catch (Throwable)` ab und fällt auf andere Pfade zurück [Q22]. Ob der Fallback auf API 26 bis 32 sauber läuft, ist nicht geprüft.
  - `java.lang.invoke.MethodHandle` ist ab API 26 verfügbar [Q20], also für minSdk 26 passend. Netty nutzt es in `PlatformDependent0` [Q22].
  - `sun.misc.Unsafe` ist optional. Netty schaltet über `io.netty.noUnsafe` ab [Q22]. Die Android-Erkennung läuft über den VM-Namen „Dalvik“ [Q22].
  - Native Transporte (epoll, kqueue) enthält die APK nicht, Netty nutzt damit den NIO-Transport. Die Migrationsnotiz nennt glibc ab 2.17 für die nativen Transporte [Q21]. Ob der NIO-Pfad auf ART ohne Einschränkung läuft, ist nicht am Gerät geprüft.
  - Eine öffentliche Android-Freigabe von Ktor oder Netty für die Server-Engine habe ich nicht gefunden. Die Suche ergab nur anekdotische Meldungen ohne Quellen.
- **Bewertung**: Keine der Varianten erfüllt die Schutzgrenzen allein. CIO fehlen die Gesamtgröße der Header und eine Mindestrate für den Körper. Netty fehlt mit den Standardoptionen der Schutz vor stillen Verbindungen. Die Entscheidung bleibt offen. Der Gerätetest steht aus.

## Offene Entscheidungen

1. **CIO reicht für die Pflicht-Grenzen nicht (offen, Messung siehe „Messung Engine“).** Kopf-Gesamtgröße und Lese-Timeouts fehlen in CIO 3.6.0 [Q1, Q2]. Angreifer im WLAN können Speicher belegen, bevor unser Code läuft. Optionen:
   - **A**: CIO bleibt. Eigene Grenzen auf Anfrage-Ebene. Das Restrisiko (Kopf-Flut, stehende Verbindungen) wird in ADR 0012 und im Testkatalog festgehalten.
   - **B**: Wechsel auf Netty 3.6.0 (`maxHeaderSize`, `requestReadTimeoutSeconds`). Abweichung von CLAUDE.md, Begründung hier. Vorher messen: APK-Größe, Lauf auf Android 8 und auf dem Testgerät.
   - Frühere Vorab-Einschätzung des Entwurfs: B, vorbehaltlich der Messung. Die Messung liegt unten vor. Die Entscheidung trifft die Nutzerin bzw. der Nutzer, sie ist **nicht** getroffen.
2. **Zwischenzustand ohne GPS-Entfernung (entschieden, 18.10.2026)**: 3.1a und 3.1b bleiben getrennt. 3.1b muss vor dem ersten für Nutzer erreichbaren Upload (Paket 3.5) gemergt sein. Bis dahin ist der Server aus keiner Oberfläche erreichbar, deshalb widerspricht der Zwischenzustand KONZEPT nicht. Der Zwischenzustand selbst bleibt ein Risiko, solange 3.1b fehlt. Er ist nur im Entwicklungsstand ohne Oberfläche tragbar.
3. **„Stopp in der App“ (entschieden, 18.10.2026)**: Voraussetzung von Paket 3.3 (Start aus der Oberfläche), nicht von 3.1. Die Präzisierung steht als Nachtrag in ADR 0012.
4. **HEIC/AVIF-GPS**: Längenerhaltendes Überschreiben braucht einen eigenen Parser. Im Spike prüfen, sonst ablehnen (siehe GPS).
5. **ACCESS_NETWORK_STATE** (geklärt, 18.10.2026): `registerNetworkCallback` trägt die Annotation `RequiresPermission(ACCESS_NETWORK_STATE)` [Q19]. Das Manifest deklariert sie nicht. Manifest-Aufgabe für 3.1a (ADR 0014, Umfang 3.1a).
6. **Netzverlust (entschieden, 18.10.2026)**: 3.1a stoppt, 3.3 setzt fort, wenn dasselbe WLAN mit derselben IP zurückkommt (siehe Netz-Abschnitt).
7. **Grenzwerte (offen)**: Alle Startwerte sind vorgeschlagen. Bestätigung durch die Nutzerin bzw. den Nutzer steht aus.

## Konsequenzen

- Ein Server, der nur im WLAN lauscht, nur Medien mit IDs ausliefert und nach Inhalt prüft, ist das Ziel von 3.1a.
- Die Zugriffsprüfung läuft in `Setup`, nicht in `Call`. Das ändert `KtorLocalServer`. Die Tests aus ADR 0012 bleiben gültig.
- Die Schnittstelle `RequestAuthorizer` wird ausgetauscht. 3.4 baut auf dem `Principal` auf.
- Die GPS-Entfernung ist in 3.1b ein eigenes Risiko mit Spike.

## Quellen

- [Q1] Ktor 3.6.0 CIO, Quelltext aus `ktor-server-cio-jvm-3.6.0-sources.jar` (Maven Central): `CIOApplicationEngine.kt` (Configuration), `backend/HttpServer.kt`, `backend/ServerPipeline.kt`. https://repo1.maven.org/maven2/io/ktor/ktor-server-cio-jvm/3.6.0/
- [Q2] Ktor 3.6.0 `ktor-http-cio-jvm-3.6.0-sources.jar`: `HttpParser.kt` (`HTTP_LINE_LIMIT = 8192`, `parseHeaders`), `ChunkedTransferEncoding.kt` (`MAX_CHUNK_SIZE_LENGTH = 128`). https://repo1.maven.org/maven2/io/ktor/ktor-http-cio-jvm/3.6.0/
- [Q3] Ktor 3.6.0 Netty, `ktor-server-netty-jvm-3.6.0-sources.jar`: `NettyApplicationEngine.kt` (`runningLimit`, `responseWriteTimeoutSeconds`, `requestReadTimeoutSeconds`, `maxInitialLineLength`, `maxHeaderSize`, `maxChunkSize`).
- [Q4] Ktor 3.6.0 Core, `ktor-server-core-jvm-3.6.0-sources.jar`: `ApplicationCallPipeline.kt` (Phasen `Setup`, `Monitoring`, `Plugins`, `Validators`, `Call`, `Fallback`).
- [Q5] ExifInterface 1.4.2, Quelltext `exifinterface-1.4.2-sources.jar`: `ExifInterface.java` (`isSupportedMimeType`, `saveAttributes`, `isSupportedFormatForSavingAttributes`, `getHeifAttributes`). https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.4.2/ . Release-Notes: https://developer.android.com/jetpack/androidx/releases/exifinterface
- [Q6] Android, Auto Backup: https://developer.android.com/identity/data/autobackup (Abschnitte zu `allowBackup`, `<device-transfer>`, „Files that are backed up“).
- [Q7] Android, Connectivity-Leitfaden: https://developer.android.com/training/monitoring-device-state/connectivity-monitoring
- [Q8] libmagic (file/file), Magdir `animation`, `images`, `jpeg`: https://github.com/file/file/tree/master/magic/Magdir
- [Q9] MP4-Registry, Brands: https://mp4ra.org/registered-types/brands
- [Q10] Google, WebP RIFF-Container: https://developers.google.com/speed/webp/docs/riff_container
- [Q11] ExifTool, Tag-Tabellen: https://exiftool.org/TagNames/QuickTime.html, https://exiftool.org/TagNames/GPS.html, https://exiftool.org/TagNames/EXIF.html
- [Q12] Nokia, HEIF Technical Overview: https://nokiatech.github.io/heif/technical.html
- [Q13] RFC 6266 (Content-Disposition): https://www.rfc-editor.org/rfc/rfc6266
- [Q14] W3C, Referrer Policy, §8.4: https://www.w3.org/TR/referrer-policy/
- [Q15] RFC 9110 (HTTP), §4.2.5: https://www.rfc-editor.org/rfc/rfc9110
- [Q16] Java SE 21, `MessageDigest.isEqual`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html
- [Q17] Repo: `share/server/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `share/server/src/main/kotlin/.../KtorLocalServer.kt`, `RequestAuthorizer.kt`.
- [Q18] Lokales Android SDK, Platform `android-37.0`: `platforms\android-37.0\android.jar`, Ausgabe von `javap` für `android.net.ConnectivityManager`, `ConnectivityManager$NetworkCallback`, `android.net.LinkProperties`, `android.net.LinkAddress`, `android.content.Context`.
- [Q19] Lokales Android SDK: `platforms\android-37.0\data\annotations.zip`, Dateien `android/net/annotations.xml` (Permission-Annotationen).
- [Q20] Lokales Android SDK: `platforms\android-37.0\data\api-versions.xml` (Einführungsversion je Klasse und Methode). Enthält unter anderem `java/lang/invoke/MethodHandle` ab API 26 und `java/lang/invoke/VarHandle` ab API 33.
- [Q21] Netty, Migrationsleitfaden 4.2: https://netty.io/wiki/netty-4.2-migration-guide.html (Java 8 als Mindestversion, glibc 2.17 für native Transporte, io_uring ab Java 9).
- [Q22] Netty 4.2.17.Final, Quelltext-Jars (Maven Central): `netty-common` (`PlatformDependent.java`, `PlatformDependent0.java`, `VarHandleFactory.java`), `netty-codec-http` (`HttpObjectDecoder.java`: `DEFAULT_MAX_HEADER_SIZE = 8192`, `DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096`). https://repo1.maven.org/maven2/io/netty/
- [Q23] Ktor 3.6.0 Netty, Quelltext-Jar `ktor-server-netty-jvm-3.6.0-sources.jar`: `NettyChannelInitializer.kt` (Einhängen des `ReadTimeoutHandler`), `http1/NettyHttp1Handler.kt` (Behandlung von `ReadTimeoutException`), `NettyApplicationEngine.kt` (Standardwerte der Optionen).
- [Q24] Ktor 3.6.0 CIO, Quelltext-Jar `ktor-server-cio-jvm-3.6.0-sources.jar`: `backend/HttpServer.kt` und `backend/ServerPipeline.kt` (Timer `connectionIdleTimeoutSeconds`).

Nicht abrufbar (als Vermutung gekennzeichnet): Die Android-Referenzseiten zu `ConnectivityManager.NetworkCallback`, `LinkProperties`, `NetworkCapabilities` und `Context.getNoBackupFilesDir` wurden nicht gelesen. Die Fakten sind stattdessen über das lokale SDK belegt (Q18 bis Q20). Der AOSP-Quelltext ließ sich nicht laden und liegt auch im SDK nicht vor. Die Apple-QuickTime-Spezifikation ließ sich nicht als Text auswerten.

## Umsetzung 3.1a (18.10.2026)

Dieser Abschnitt hält fest, was sich bei der Umsetzung ergeben hat. Wo er einem älteren Satz oben widerspricht, gilt er.

### Entscheidung Engine: CIO bleibt

- **Begründung**, gestützt auf die Messung vom 18.10.2026 (Abschnitt „Messung Engine“): CIO schließt stumme und langsame Header-Verbindungen mit gesetztem Idle-Timeout von 15 s (Fälle a, b, d). Netty mit den genannten Grenzen hat in der Messung nicht geschlossen. Netty braucht drei Build-Eingriffe (QUIC-Ausschluss, Packaging-Ausschlüsse, 45 `-dontwarn`-Zeilen), und sein Android-Fallback ist ungeprüft. CLAUDE.md nennt CIO.
- **Körper-Schutz unabhängig von der Engine**: Der Körper wird in einer eigenen Schleife gelesen (`BodyChunkSource`). Leerlauf, Mindestrate und Gesamtdauer prüft `TransferGuard` mit einer injizierbaren Uhr.
- **Restrisiken, ausdrücklich**:
  - CIO begrenzt die Header-Gesamtgröße nicht. Gemessen werden 1 601 800 Byte Header mit `200` beantwortet. Der Test `totalHeaderSizeIsNotLimitedByCioDocumentedRisk` hält das Verhalten fest.
  - CIO begrenzt die Anzahl gleichzeitiger Verbindungen nicht. Im LAN ist das ein Verfügbarkeitsrisiko ohne Datenzugriff. Mildernd wirken der Idle-Timeout für stumme Verbindungen (15 s) und die schnelle Ablehnung durch die Zugriffskette vor jeder Route.
  - **Wiedervorlage beim TLS-Meilenstein.**

### Startwerte (bestätigt am 18.10.2026)

| Grenze | Wert | Umsetzung |
|---|---|---|
| `connectionIdleTimeoutSeconds` | 15 s | `KtorLocalServer`, Test `silentConnectionIsClosedByIdleTimeout` |
| Body-Leerlauf (kein Byte) | 30 s, dann Abbruch | `TransferPolicy.idleNanos` |
| Mindestrate | nach 30 s Anlauf im gleitenden 60-s-Fenster mindestens 16 KiB/s | `TransferPolicy`, `TransferGuard` |
| Upload-Gesamtdauer | höchstens 60 min | `TransferPolicy.maxDurationNanos` (ersetzt 30 min aus dem Entwurf) |
| Anfragen gleichzeitig | 16, darüber `503 service_busy` mit `Retry-After: 5` | `ServerGates` |
| Uploads gleichzeitig | 4 gesamt, 2 je Teilnehmer, darüber `429 too_many_uploads` mit `Retry-After: 5` | `ServerGates` |
| Leerlauf, Mindestrate, Gesamtdauer | `408 request_timeout` mit `reason` (`idle`, `too_slow`, `too_long`). Teildatei immer gelöscht | `BodyChunkSource`, `SpaceRoutes` |

Grenzwerte, bestätigt am 18.10.2026: Bild 50 MiB, Video 2 GiB, Standard-Speicherlimit eines Space 4 GiB mit Warnung ab 80 %, Mindest-Restspeicher des Geräts 2 GiB.

### Abweichungen von Entwurf und Auftrag

1. **Host- und Netzablehnung** (`403`) liefern das einheitliche JSON `{"error":"forbidden_host"}` bzw. `forbidden_network`, nicht den leeren Körper aus dem Entwurf. Grund: einheitliches Fehlerformat (ADR 0014, Auftrag 3.1a). Der Code verrät nur, welche Prüfung griff.
2. **Inhaltsadressierte Ablage**: Die Datei heißt `<SHA-256 in Hex>`, nicht Zufallsname. Grund: Integritätsprüfung (Abschnitt „Integrität“) und Auftrag („inhaltsadressierte Ablage“). Der Name entsteht auf dem Server, nie aus dem Client. Die Spalte `storage_name` entfällt, weil der Hash sie ersetzt.
3. **GIF**: Die Signatur ist `GIF87a` bzw. `GIF89a`, strenger als das im Entwurf genannte `GIF8`.
4. **Content-Length kleiner als der gesendete Körper**: CIO liest per HTTP-Rahmung genau Content-Length Bytes. Der Server speichert deshalb nie mehr als angekündigt. Der Socket-Test prüft das (Antwort `201`, `size` gleich Content-Length). Der Vault-Test prüft unabhängig davon das Abschneiden am Byte-Limit, auch wenn der angekündigte Wert klein ist. Der Entwurf erwartet dafür `413`. Mit CIO ist das nicht erreichbar, ohne über die HTTP-Rahmung hinauszulesen.
5. **Körper endet vor Content-Length**: Der Leser wirft `IncompleteBodyException`. Die Teildatei wird gelöscht, die Antwort ist `400 incomplete_body`. Ohne diese Prüfung hätte ein abgebrochener Upload als vollständige, abgeschnittene Datei gegolten. Der Socket-Test „Verbindungsabbruch mitten im Upload“ deckt das ab.
6. **Zusätzliche Codes** gegenüber ADR 0014: `service_busy` (503), `incomplete_body` (400), `request_timeout` (408). `space_closed` (410) ist in 3.1a nicht umgesetzt, weil es in 3.1a keine Beendigung des Space gibt. Er kommt mit 3.6.
7. **Keine Tabelle `space`** in 3.1a. Name und Speicherlimit des Space kommen aus der Einrichtung des Serverlaufs (`ServerSetup`). Die Tabelle folgt mit 3.2, wenn die Oberfläche sie braucht.
8. **Room-Pfad** (offene Frage aus ADR 0014): Der absolute Dateiname im Room-Builder ist unter Robolectric auf Windows fehlerhaft. Der Pfad wurde an das Cache-Verzeichnis angehängt (`FileNotFoundException`). Umsetzung: eine Kontext-Hülle, die `getDatabasePath` auf `noBackupFilesDir/spaces/` umlenkt. `SpacesDatabaseTest` prüft den Ort.
9. **Mindest-Restspeicher** über `File.getUsableSpace()`, hinter der Abstraktion `StorageProbe`. Das ist gleichwertig zu StatFs und ohne Android-Kontext prüfbar.
10. **Gegenrichtung der Galerie-Sperre**: `checkGalleryNoSpaceDependency` gilt für `:core:media` und `:feature:gallery`. `:app` ist ausgenommen, weil die App den Server einbindet.
11. **`RequestAuthorizer`** liefert `Principal?` statt `Boolean`. `TokenAuthorizer` vergleicht SHA-256-Hashes über `MessageDigest.isEqual` und prüft alle Einträge. Die Tabelle `participant` ist angelegt. Die Verwaltung der Zugangsschlüssel folgt in 3.4.
12. **WLAN-Adresse**: `ConnectivityWifiNetworkProvider` liest die Schnittstelle über `allNetworks`. Die Methode ist seit API 31 veraltet. Die Unterdrückung ist im Code begründet, weil das WLAN auch dann gefunden werden muss, wenn es nicht das Standardnetz ist.
13. **Protokoll**: Route-Templates, nie rohe Pfade. IP-Adressen nur mit `debugLogging`, das nur der Debug-Einstieg setzt. Standardmäßig protokolliert der Server nichts (ADR 0012).
14. **Netzwechsel** stoppt den Server und gibt die Sperren frei. Eine Benachrichtigung nennt den Grund. Der Dienst startet nicht selbst neu, das Fortsetzen folgt mit 3.3.

### Prüfungen zur Umsetzung

- Unit- und Robolectric-Tests in `share:space` und `share:server` (Anzahl und Zuordnung im PR #26).
- Drei Symlink-Tests in `SpaceStorageTest` werden lokal unter Windows übersprungen, weil das Recht zur Symlink-Anlage fehlt. Auf dem CI-Runner (Linux) laufen sie.
- Der Gerätetest mit dem Debug-Einstieg (docs/DEBUG-SERVER.md) folgt separat.
