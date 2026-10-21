# ADR 0019: Medienzugriff und Vorschauen (Meilenstein 3, Paket 3.5a)

Status: angenommen (21.10.2026, Nutzerentscheidung 1 bis 7 aus dem Auftrag zu Paket 3.5a). Umsetzung im Branch `feature/m3-3.5a-medienansicht` (PR #36, Paket 3.5a-1 „Server-Grundlage“). Vorschauen sind in Paket 3.5a-2 umgesetzt (PR #37), die Gäste-Seite folgt als 3.5a-3. Der Stand dieses ADR zeigt, was schon im Code ist. Offene Teile sind unten ausdrücklich markiert.

## Kontext

Paket 3.5a zeigt die Medien des Space im Browser. Ein Gast öffnet die Seite, sieht Bilder und Videos und lädt einzelne Dateien herunter. Der Browser lädt Bilder, Videos und Downloads direkt von einer URL. Er kann dabei keinen `Authorization`-Header setzen. Damit der Token nicht in die URL wandert, brauchte es eine Alternative.

Grundlagen: [ADR 0013](0013-schutz-gegen-manipulation.md) (Token nur als Hash, Reihenfolge der Prüfungen, Downloads, Logging), [ADR 0014](0014-space-speicher-und-server-api.md) (API v1), [ADR 0017](0017-einrichtung-und-betrieb.md) (Ablauf des Space), [ADR 0018](0018-einladung-und-beitritt.md) (Beitritt, Entfernen).

## Entscheidungen

### 1. Signierte, kurzlebige Medien-URLs (umgesetzt)

- **Verfahren:** HMAC-SHA256. Der Schlüssel hat 256 bit Zufall. Er liegt nur im Speicher und wird je Serverlauf neu erzeugt. Ein Neustart macht alle URLs ungültig. Das ist gewollt. Die Gäste-Seite holt sich dann neue Adressen über die Header-API.
- **Signierte Nachricht:** `mediaId|zweck|participantId|exp`. Zwecke: `preview`, `view`, `download`, `stream`. Die Signatur ist an genau eine Route gebunden. Eine Signatur für `view` gilt nicht für `content`.
- **Gültigkeit:** 10 Minuten für `preview`, `view`, `download`. 30 Minuten für `stream` (Video).
- **Prüfung in dieser Reihenfolge:** Signatur mit `MessageDigest.isEqual`, dann Ablauf, dann Teilnehmer noch aktiv. Ein entfernter Teilnehmer verliert den Zugriff sofort, weil die Prüfung den Live-Stand der Zugangsschlüssel liest. Ob das Medium vorhanden ist, prüft die Route (404).
- **Transport:** Query-Parameter `pid`, `exp`, `sig`. Die Prüfung gilt nur für `GET` der Medienrouten und nur, wenn solche Parameter vorhanden sind. Sonst gilt weiter der Header. Host-, Netz- und Drosselungsprüfung laufen davor. Die Parameter wirken auf keiner anderen Route.
- **Fehlercodes:** `403` mit `url_invalid` (Signatur, Format), `url_expired` (Ablauf), `participant_inactive` (Teilnehmer entfernt). Der Header-Fall bleibt `401`. Die Seite erneuert bei `401` und `403` still.
- **Antwort-Header auf Medien:** `Referrer-Policy: no-referrer`, `Cross-Origin-Resource-Policy: same-origin`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`.
- **Logging:** Das Protokoll nennt das Route-Template. Query und Signatur stehen nie im Log. Der Test `logLinesNeverContainTheQueryOrTheSignature` prüft das.
- **Abweichung zu 1 in der Begründung:** Die Signatur steht in der Query. Sie landet damit im Browserverlauf und könnte in Proxy-Logs stehen. Das ist vertretbar, weil die URL nach 10 bzw. 30 Minuten wertlos ist, nur für einen Teilnehmer gilt und nur im WLAN gilt. Ein Token in der URL wäre dagegen dauerhaft.

Begründung der Ausnahme von der Regel „Token nie in URL“: siehe Nachtrag in [ADR 0013](0013-schutz-gegen-manipulation.md).

### 2. Video (teilweise umgesetzt)

- **Umgesetzt:** Stream-Route `/api/v1/media/{id}/stream` mit 30-Minuten-Signatur, Range über das Plugin `PartialContent`. Mehrere Bereiche im `Range`-Header ergeben `416` mit `Content-Range: bytes */<Größe>`. Der Server liefert kein `multipart/byteranges`. Nur Videos (`video/*`) sind über den Stream-Pfad erreichbar, sonst `404`.
- **Codec (umgesetzt in 3.5a-2):** beim Indexieren per `MediaExtractor` (Feld `videoCodec`), für Bestand im Worker.
- **Offen (3.5a-3):** Die Prüfung mit `canPlayType` und den Fallback „Nur Download“ in der Seite. Die stille Erneuerung der Stream-URL in der Seite.

### 3. Vorschauen (umgesetzt in 3.5a-2, Abweichungen unten)

Umgesetzt in 3.5a-2: serverseitig asynchron mit einem Worker niedrigerer Priorität. 320 px lange Kante für Bilder und Videos. Zusätzlich 1600-px-JPEG-Ansicht nur für HEIC/HEIF. JPEG q ≈ 80 ohne EXIF. Dekompressionsschutz über Maße vorab, Pixelgrenze und Ziel-Subsampling. `ImageDecoder` ab API 28 mit `setTargetSize`, darunter `BitmapFactory` mit `inSampleSize`. Video-Frame über `MediaMetadataRetriever`. Status `PENDING`, `READY`, `FAILED` im Index, Migration 6 → 7 mit Migrationstest. Ablage inhaltsadressiert unter dem Space-Speicher. Die Vorschauen zählen gegen das Speicherlimit, werden mit dem Medium gelöscht und von `recover()` aufgeräumt. Nie im MediaStore und nie im Coil-Disk-Cache.

Die Pixelgrenze liegt vorläufig bei 100 Megapixel. Das deckt 12- bis 50-Megapixel-Sensoren mit Reserve ab. Eine Decodierung über dieser Grenze kostet bei 4 Byte je Pixel mehr als 400 MB Arbeitsspeicher und ist auf Geräten mit 4 GB RAM nicht sicher. Ein Bild darüber bekommt `FAILED` und den Platzhalter.

### 4. API-Erweiterungen (umgesetzt, Nachtrag zu ADR 0014 folgt in diesem PR)

- Medienliste und `GET /api/v1/media/{id}` (Header-Auth) liefern je Eintrag `mine`, `urlsExpireInSeconds` (600) und die signierten Adressen `viewUrl`, `downloadUrl`, bei Video `streamUrl`. Die Adressen sind pro anfragendem Teilnehmer.
- `GET /api/v1/space` liefert `remainingSeconds` (`null` ohne Ende), `endsAt` (ISO 8601, UTC, `null` ohne Ende), `revision` und `limits` (`imageMaxBytes`, `videoMaxBytes`).
- `revision` steigt bei Uploads, Löschungen, Beitritt, Entfernen und Erneuern der Einladung.
- Die Felder `previewUrl`, `previewState`, `videoCodec` und `viewState` waren mit Paket 3.5a-1 als Platzhalter immer `null`. Seit 3.5a-2 sind sie gefüllt (Abschnitt „Umsetzung 3.5a-2“). Der Vertrag steht im Abschnitt „Schnittstelle für 3.5a-2 und 3.5a-3“.

### 5. Server-Last (umgesetzt)

Eigene Grenze für Medien-Streams und Downloads: vier gleichzeitig (`ServerGates.MEDIA_LIMIT`), zusätzlich zu den 16 Anfragen insgesamt. Darüber `503` mit `Retry-After`. Vorschauen zählen nicht dazu. `Cache-Control: no-store` bleibt.

### 6. Ende des Space (teilweise umgesetzt)

- **Umgesetzt:** Nachlauf nach dem Ablauf von 1 s auf 60 s (`SpaceRunner.CLOSE_GRACE_MILLIS`, siehe Nachtrag in [ADR 0017](0017-einrichtung-und-betrieb.md)). In dieser Zeit antworten authentifizierte Anfragen mit `410 space_closed`. Medien und Vorschauen gibt es nicht mehr.
- **Umgesetzt in 3.5a-3:** Die Seite mit Restzeit, Banner (10 Minuten), Dialog (1 Minute), Ende-Ansicht und Verbindungsverlust (Nachtrag unten). Die Benachrichtigungen des Gastgebers und der Hinweis im Beenden-Dialog (Nachtrag 3.5a-3 in [ADR 0017](0017-einrichtung-und-betrieb.md)).

### 7. Gäste-Seite (umgesetzt in 3.5a-3)

Raster, unendliches Scrollen, Filter „Meine“, Detail, Download, Zurückziehen mit Bestätigung, Speicheranzeige, Abfrage alle 10 bis 15 Sekunden bei sichtbarer Seite, i18n de und en, Barrierefreiheit. Umsetzung und Abweichungen im Nachtrag 3.5a-3 unten.

## Schnittstelle für 3.5a-2 und 3.5a-3

Festgeschrieben am 21.10.2026. Paket 3.5a-2 (Vorschauen, Codec, HEIC-Ansicht) füllt diese Felder und Routen. Paket 3.5a-3 (Gäste-Seite) entwickelt dagegen. Änderungen am Vertrag brauchen einen Nachtrag zu diesem ADR.

**Felder je Eintrag** in der Medienliste und in `GET /api/v1/media/{id}` (zusätzlich zu den Feldern aus Abschnitt 4):

- `previewUrl`: string oder `null`. Signierte Adresse für die Vorschau mit Zweck `preview`, Gültigkeit 10 Minuten, Route `/api/v1/media/{id}/preview`. `null`, solange keine Vorschau bereit ist.
- `previewState`: `"pending"`, `"ready"`, `"failed"` oder `null`. `null` heißt: Der Server unterstützt Vorschauen für diesen Eintrag noch nicht (in 3.5a-1 für alle Einträge).
- `videoCodec`: string oder `null`, z. B. `"avc1"` oder `"hvc1"`. `null` bei Bildern und bei nicht erkanntem Codec.
- `viewState`: `"pending"`, `"ready"`, `"failed"` oder `null`. Nur bei HEIC/HEIF gesetzt. Bei HEIC/HEIF liefert `viewUrl` die 1600-px-JPEG-Ansicht, sobald `viewState` `"ready"` ist. Bis dahin ist `viewUrl` `null`. Bei `"failed"` bleibt `viewUrl` `null`. Bei allen anderen Typen ist `viewState` `null` und `viewUrl` zeigt das Original.

**Routen:**

- `GET /api/v1/media/{id}/preview`: JPEG mit 320 px langer Kante, für Bilder und Videos. Zugriff per Header oder per signierter Adresse mit Zweck `preview`. Gleiche Antwort-Header wie bei anderen Medienantworten (ADR 0019, Abschnitt 1).
- `GET /api/v1/media/{id}/view`: bestehende Route. Bei HEIC/HEIF liefert sie die 1600-px-JPEG-Ansicht. Zugriff wie oben.
- Antwortcodes für beide Routen: `200` bei Erfolg. `404` mit `preview_unavailable`, wenn der Zustand `pending` oder `failed` ist. `404` mit `not_found`, wenn das Medium nicht existiert.

**Stand 3.5a-2:** Die Felder sind gefüllt, die Routen `/preview` und `/view` (HEIC/HEIF) gibt es. Der Test `itemsCarryThePreviewFieldsAndStayPendingUntilAWorkerRendersThem` (in `SignedMediaApiTest`) und `PreviewApiTest` prüfen das. Vor 3.5a-2 waren die Felder `null`.

**Folgepakete:** Die Gäste-Seite behandelt `null` bei `previewUrl` und `viewUrl` als „Vorschau folgt“ bzw. „Original laden“ und nie als Fehler. Die Seite erneuert Adressen still bei `401` und `403` (Abschnitt 1).

## Umsetzung 3.5a-2 (21.10.2026): Entscheidungen und Abweichungen

- **Ort.** Renderer, Worker und Index liegen in `share:space` (Paket `preview`). `share:server` ruft nur die Methoden des Vault auf. Die Isolationsprüfungen bleiben grün.
- **Worker.** Eine Warteschlange (FIFO) und ein Faden mit niedriger Priorität (`PreviewWorkers`). Der Upload reiht nach dem Commit ein und wartet nicht. Der Commit-Lock hält nur die Schreibzugriffe auf den Index. Das Rendern läuft ohne Lock.
- **Wiederaufnahme.** `recover()` setzt Medien ohne Vorschau-Zustand (Bestand vor 3.5a-2) auf `PENDING` und reiht sie ein. `READY` ohne Datei wird wieder `PENDING`. Vorschauen ohne Medium werden gelöscht. Dateien liegen unter `previews/<sha256>-<Kante>.jpg`. Jede Datei gehört zu genau einem Medium, weil der Hash des Originals im Space eindeutig ist (Dedupe).
- **Zustände.** `PENDING`, `READY`, `FAILED` im Index, `null` für „noch nicht eingereiht“. Ein Fehler, ein Timeout (30 s) oder ein Ergebnis über dem Speicherlimit ergibt `FAILED`. Es gibt keinen Wiederholungsversuch in der Schleife. `FAILED` bleibt auch nach einem Neustart.
- **Speicher.** Vorschauen zählen mit ihrer Größe (`preview_bytes`, `view_bytes`) gegen das Speicherlimit und den Mindest-Restspeicher. Passt eine Vorschau nicht, wird sie `FAILED`. Das Medium bleibt unberührt.
- **Codec.** Beim Upload per `MediaExtractor`, nur aus dem Kopf der Datei. Wird kein Codec erkannt, speichert der Index einen leeren Wert. Die Schnittstelle zeigt ihn als `null`. So wird nicht endlos neu geprüft. Die Zuordnung MIME zu Vierzeichen-Code steht in `PreviewRules.codecOf`.
- **Dekodierung.** Ab API 28 `ImageDecoder` mit Zielgröße (Subsampling). Darunter `BitmapFactory` mit `inSampleSize` und Ausrichtung nach EXIF. Das Ergebnis wird auf Weiß verflacht und ohne EXIF als JPEG (Qualität 80) geschrieben. HEIC/HEIF brauchen API 28. AVIF wird ab API 31 versucht. Das ist eine Annahme, am Gerät nicht geprüft. Darunter ergibt beides `FAILED` ohne Dekodierversuch.
- **Video-Frame.** Ab 10 s Länge bei 1 s, sonst bei 10 % der Dauer. Ohne Dauer der erste Frame. Eine Drehung wird nachgeholt, nur wenn der Frame noch im Rohformat vorliegt (erkannt am Seitenverhältnis). Das Verhalten der Plattform ist am Gerät zu prüfen.
- **Pixelgrenze.** 100 Megapixel, vor dem Dekodieren geprüft (Kopfdaten bzw. Bounds). Darüber `FAILED`.
- **Gate.** Vorschauen und die HEIC-Ansicht (`/view` bei HEIC/HEIF) laufen nicht durch das Medien-Gate. Das Original (Download, Ansicht bei anderen Typen, Stream) bleibt dahinter.
- **Gastgeber-App.** Die Medienliste im laufenden Space zeigt heute Textzeilen ohne Bilder. Vorschaubilder dort wären eine UI-Änderung mit Goldens. Sie sind für 3.5c vorgemerkt.
- **Nicht in 3.5a-2:** `canPlayType`-Prüfung und Fallback „Nur Download“ sowie die Gäste-Seite (3.5a-3).

## Konsequenzen

- Die Signatur ist ein Bearer-ähnliches Recht für 10 bzw. 30 Minuten. Wer die URL aus dem WLAN abgreift, kann das Medium in dieser Zeit lesen. Das ist mit dem Ziel „nur im WLAN, nur für Teilnehmer“ vereinbar. Die Entfernung eines Teilnehmers wirkt sofort.
- Ein Neustart des Servers macht alle Adressen ungültig. Die Seite muss dann neu anfragen. Das ist im Plan vorgesehen.
- Die Schlüsselverwaltung bleibt im Speicher. Eine Persistenz des Schlüssels ist nicht vorgesehen.

## Nachtrag 3.5a-3: Gäste-Seite, Ende-Hinweise, Browser-Tests (21.10.2026)

Der Vertrag oben bleibt unverändert. Dieser Nachtrag hält fest, wie die Seite ihn nutzt und wo die Umsetzung davon abweicht.

**Aufbau der Seite.** Drei Skripte ohne Build (`guest-core.js` mit dem gemeinsamen Kern, `guest-space.js` mit Raster, Detail, Abfrage und Ende, `guest.js` mit Beitritt und Einstieg). Einstieg ist `index.html`. Alle Texte per `textContent`, Attribute nur aus geprüften Werten. Medien-Adressen erkennt die Seite nur als relativen Pfad der Form `/api/v1/media/{id}/{route}?pid=…&exp=…&sig=…` erkennen; sonst gelten sie als fehlend.

**Vorschau und Platzhalter.** Ein Bild zeigt `previewUrl`, sobald `previewState` `ready` ist. Sonst zeigt die Seite bei Bildern (außer HEIC) das Original `viewUrl` mit `loading="lazy"`. Bei Video und HEIC bleibt es beim Platzhalter. Mit den Vorschauen aus 3.5a-2 zeigt die Seite Fotos über `previewUrl`, Videos und HEIC bleiben beim Platzhalter, solange der Renderer kein Bild liefert.

**Erneuerung der Adressen.** Die Seite rechnet die Gültigkeit lokal ab dem Abruf (`urlsExpireInSeconds`, Video 30 Minuten). Kurz vor Ablauf holt sie den Eintrag über `GET /api/v1/media/{id}` neu. Die Video-Adresse wird still getauscht, die Wiedergabe setzt an `currentTime` fort. Schlägt die Wiedergabe fehl, versucht die Seite es einmal mit neuer Adresse und zeigt danach „Nur Download“. Der Browser meldet bei einem HTTP-Fehler am Medienelement nur „nicht unterstützt“. Darum wird ein Fehler immer zuerst als Erneuerung behandelt, nicht sofort als fehlendes Format.

**Codec.** Ist `videoCodec` `null`, versucht die Seite die Wiedergabe. Bei `avc1` oder `hvc1` prüft sie `canPlayType` mit einer festen Codec-Angabe. Unbekannte Werte gelten als nicht abspielbar.

**Abweichungen.**
- Die Ende-Uhrzeit: Die API meldet das Ende nicht gesondert (410 trägt nur `space_closed`). Bei Ablauf zeigt die Seite das geplante Ende. Beendet der Gastgeber früher, zeigt sie den Zeitpunkt, zu dem sie das Ende bemerkt hat.
- Das Anschauen der Medien über die Ansicht (`viewUrl` für Bilder) zählt gegen die Gate-Grenze der Medienrouten (vier gleichzeitig, ADR 0019, Abschnitt 5). Bei vielen Bildern im Raster kann der Server mit `503` antworten, die Seite zeigt dann den Platzhalter. 3.5a-2 mit echten Vorschauen beseitigt das.
- Der Dialog „Der Space endet in einer Minute“ verlangt die Bestätigung über den Knopf. Esc schließt ihn nicht, weil die Warnung eine Bestätigung braucht.
- Die stille Erneuerung nach 30 Minuten prüfen die Browser-Tests nicht, das dauert zu lange. Sie ist im Testkatalog als Entwickler-Prüfung vorgesehen (Punkt 6.6).

**Browser-Tests.** Node 22 oder neuer, ohne npm-Abhängigkeiten. Node steuert Chrome headless per CDP. Chrome startet mit `--headless=new`, `--remote-debugging-port=0` und einem eigenen Profil im Temp-Ordner. Der JVM-Test `GuestBrowserTest` startet den echten Server auf 127.0.0.1 (CIO, feste Host-Prüfung), legt Space, Gäste und Medien über die Schnittstelle an und übergibt Port, Einladung und Sitzung an Node. Braucht ein Fall eine Änderung am Server (Ende, Abschalten), meldet Node es über stderr (`EMBERLIGHT-CONTROL <name>`). Der JVM-Test führt die Aktion aus und antwortet auf stdin mit `ok`. Probleme der Seite (JavaScript-Fehler, CSP-Verstöße) sind Testfehler. Fehlen Node oder Chrome, werden die Tests übersprungen. Mit `CI=true` schlagen sie fehl. CI richtet Node 24 über die Composite-Action `browser-test-tools` ein. Das Video-Fixture `clip.mp4` entstand einmalig in Chrome über `MediaRecorder` (Skript `fixtures/make-clip.mjs`), weil auf dem Entwicklungsrechner kein ffmpeg liegt.

**Vorschauen in den Browser-Tests.** Die Galerie-Szenarien laufen mit Vorschauen aus 3.5a-2. Ein Foto bekommt eine Vorschau (`previewState` `ready`, Adresse `…/preview?…`), ein Video ohne Frame bleibt `failed` und zeigt den Platzhalter. Die Erwartungen leitet der Test aus der API ab. Der Vorschau-Renderer der Tests ist die JDK (`ImageIO`), weil der Android-Dekodierer unter Robolectric nicht zuverlässig läuft. Die Vorschau mit dem Android-Dekodierer am Gerät prüft der Testkatalog (6.16 bis 6.20).

**Offen.** Die Gerätetests (Handy und PC) stehen im Testkatalog.

## Offen

- Gerätetest der Vorschauen (HEIC ab API 28, Videobild und Drehung, Speicherzählung), Testkatalog 6.16 bis 6.20.
- Vorschau in der Gastgeber-App (Medienliste im laufenden Space, heute nur Text), vorgemerkt für 3.5c.

## Nachtrag 3.5b: Upload im Browser (21.10.2026)

Die Gäste-Seite lädt Medien hoch. Umsetzung in `guest/guest-upload.js` (Oberfläche in `index.html`, Stile in `guest.css`). Die Signaturen stehen in `guest/formats.json`. Der Upload nutzt die API aus [ADR 0014](0014-space-speicher-und-server-api.md) (Abschnitt „Upload“) und die Standort-Rückfrage aus [ADR 0015](0015-entfernung-von-standortdaten.md).

**Bedienung.** Knopf „Dateien auswählen“ (`input type=file multiple accept="image/*,video/*"`). Am PC zusätzlich Ablage per Drag and Drop auf die Ablagezone, der Knopf bleibt die Tastatur-Alternative. Mehrfachauswahl wird zur Warteschlange. Je Gast laufen höchstens zwei Uploads gleichzeitig. Je Datei: Zustand, Fortschritt in Prozent und Balken (`upload.onprogress`), Knopf „Abbrechen“ (`xhr.abort`). Oben der Gesamtfortschritt. Die Live-Region meldet nur Abschluss, Fehler, Stopp und Rückfragen, gedrosselt über `core.announce`. Solange Uploads laufen, steht der Hinweis „Bitte die Seite offen lassen, bis alle Uploads fertig sind.“ da.

**Vorabprüfung im Browser.** Vor dem Senden prüft die Seite jede Datei:
1. Format: die ersten 32 Bytes (`file.slice(0, 32)`) gegen `formats.json`. Die Auswertung entspricht `ContentInspector`.
2. Größe: gegen `imageMaxBytes` bzw. `videoMaxBytes` aus `limits`, je nach erkanntem Format.
3. Speicher: gegen `storageLimitBytes − usedBytes`. Dabei zählt die Summe der wartenden und laufenden Dateien mit.

Abgelehnte Dateien werden nicht gesendet und in der Liste mit dem Grund angezeigt. Lassen sich die Regeln nicht laden, bleibt die Auswahl gesperrt (fail closed).

**Gleichlauf der Signaturen.** `formats.json` ist die Liste für die Seite, `ContentInspector` die für den Server. `GuestFormatsParityTest` (JVM) prüft beide an `src/test/fixtures/guest-formats.json`: `ContentInspector` liefert das erwartete Ergebnis, und die Auswertung von `formats.json` stimmt damit überein. Der Browser-Test `formats.test.mjs` prüft die Seite an denselben Fällen. Weicht eine Liste ab, schlägt ein Test fehl.

**Antworten des Servers und Verhalten der Seite.**

| Antwort | Verhalten |
|---|---|
| `201` | Fertig. Bei `locationKept` „Fertig, Standort enthalten“ (Kennzeichen im Raster) |
| `200` mit `duplicate: true` | „Bereits vorhanden“, Knopf „Zum Eintrag“ springt zum Eintrag. Kein Fehler |
| `422 location_not_removable` | Rückfrage pro Datei mit „Mit Standort hochladen“ bzw. „Nicht hochladen“. Ja: erneut mit `X-Emberlight-Keep-Location: 1`. Nein: nichts gesendet |
| `429`, `503` mit `Retry-After` | Warten, ohne Meldung, dann erneut |
| Netzfehler, `408`, `5xx` | Warten 1 s, 3 s, 10 s, danach Fehler „Keine Verbindung …“ |
| `507` | Warteschlange angehalten, Hinweis, Knopf „Fortsetzen“. Laufende Uploads enden, neue starten nicht |
| `410` | Ende-Ansicht mit `endedAt` (ADR 0014), alle Uploads gestoppt |
| `400`, `401`, `403`, `413`, `415` | Fehler je Datei mit verständlichem Text, keine Wiederholung |

**Auslegungen und Abweichungen.**
- „Höchstens 3 Versuche“ im Auftrag: gelesen als erster Versuch plus drei Wiederholungen mit den Wartezeiten 1 s, 3 s und 10 s.
- Warten bei `429` und `503` mit `Retry-After`: Standard 5 s, höchstens 60 s je Warten, höchstens zehn Wartezeiten. Die Begrenzung auf zehn ist eine Festlegung dieses Nachtrags, der Auftrag nennt keine Grenze.
- Passt eine Datei bei der Vorabprüfung nicht mehr in den freien Speicher, wird nur diese Datei abgelehnt. Die übrigen laufen weiter.
- Beim Verlassen des Space (Knopf „Verlassen“, Sitzung abgelaufen, Ende) bricht die Seite alle Uploads ab (`cancelAll`).
- Die Seite meldet Hintergrund-Tabs nur über den Hinweis. Die Pausierung durch den Browser ist nicht abgefangen.
- Das Inline-Stylen der Breiten geschieht nur über `element.style`, weil die CSP keine Inline-Attribute erlaubt (ADR 0018).

**Browser-Tests.** `GuestBrowserTest` mit vier neuen Szenarien: `upload.test.mjs` (acht Fälle: Fortschritt, zwei parallel, Dublette, Vorab-Ablehnung, Standort-Rückfrage, Abbrechen, 429, Ablage am PC), `upload-storage.test.mjs` (507), `upload-closed.test.mjs` (410 mit `endedAt`), `formats.test.mjs` (Gleichlauf). Die Steuerung über den Steuerkanal: `hold-uploads` und `release-uploads` belegen die Upload-Plätze des Gastes (429), `fill-storage` füllt den Speicher, `close-space` markiert das Ende. Die Fixtures sind echte JPEGs (`BrowserScene.photo`, Zufallsbilder mit festem Seed) und ein MP4 mit `gpmd`-Track (`MediaFixtures.mp4WithGpmdTrack`, Antwort `422`). Die Szenarien dauern zusammen rund 100 Sekunden.

**Offen.** Gerätetests am Handy und am PC (Testkatalog, Abschnitt 7). Netzabbruch mitten im Upload (WLAN aus) ist nicht automatisiert.
