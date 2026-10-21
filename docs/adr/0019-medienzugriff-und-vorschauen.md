# ADR 0019: Medienzugriff und Vorschauen (Meilenstein 3, Paket 3.5a)

Status: angenommen (21.10.2026, Nutzerentscheidung 1 bis 7 aus dem Auftrag zu Paket 3.5a). Umsetzung im Branch `feature/m3-3.5a-medienansicht` (PR #36). Der Stand dieses ADR zeigt, was schon im Code ist. Offene Teile sind unten ausdrücklich markiert.

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
- **Offen:** Codec beim Indexieren per `MediaExtractor` (Feld `videoCodec`). Die Prüfung mit `canPlayType` und den Fallback „Nur Download“ in der Seite. Die stille Erneuerung der Stream-URL in der Seite.

### 3. Vorschauen (offen)

Geplant und noch nicht umgesetzt: serverseitig asynchron mit einem Worker niedrigerer Priorität. 320 px lange Kante für Bilder und Videos. Zusätzlich 1600-px-JPEG-Ansicht nur für HEIC/HEIF. JPEG q ≈ 80 ohne EXIF. Dekompressionsschutz über Maße vorab, Pixelgrenze und Ziel-Subsampling. `ImageDecoder` ab API 28 mit `setTargetSize`, darunter `BitmapFactory` mit `inSampleSize`. Video-Frame über `MediaMetadataRetriever`. Status `PENDING`, `READY`, `FAILED` im Index, Migration 6 → 7 mit Migrationstest. Ablage inhaltsadressiert unter dem Space-Speicher. Die Vorschauen zählen gegen das Speicherlimit, werden mit dem Medium gelöscht und von `recover()` aufgeräumt. Nie im MediaStore und nie im Coil-Disk-Cache.

Die Pixelgrenze liegt vorläufig bei 100 Megapixel. Das deckt 12- bis 50-Megapixel-Sensoren mit Reserve ab. Eine Decodierung über dieser Grenze kostet bei 4 Byte je Pixel mehr als 400 MB Arbeitsspeicher und ist auf Geräten mit 4 GB RAM nicht sicher. Ein Bild darüber bekommt `FAILED` und den Platzhalter.

### 4. API-Erweiterungen (umgesetzt, Nachtrag zu ADR 0014 folgt in diesem PR)

- Medienliste und `GET /api/v1/media/{id}` (Header-Auth) liefern je Eintrag `mine`, `urlsExpireInSeconds` (600) und die signierten Adressen `viewUrl`, `downloadUrl`, bei Video `streamUrl`. Die Adressen sind pro anfragendem Teilnehmer.
- `GET /api/v1/space` liefert `remainingSeconds` (`null` ohne Ende), `endsAt` (ISO 8601, UTC, `null` ohne Ende), `revision` und `limits` (`imageMaxBytes`, `videoMaxBytes`).
- `revision` steigt bei Uploads, Löschungen, Beitritt, Entfernen und Erneuern der Einladung.
- Offen: `previewUrl`, `previewState` und `videoCodec` kommen mit den Vorschauen.

### 5. Server-Last (umgesetzt)

Eigene Grenze für Medien-Streams und Downloads: vier gleichzeitig (`ServerGates.MEDIA_LIMIT`), zusätzlich zu den 16 Anfragen insgesamt. Darüber `503` mit `Retry-After`. Vorschauen zählen nicht dazu. `Cache-Control: no-store` bleibt.

### 6. Ende des Space (teilweise umgesetzt)

- **Umgesetzt:** Nachlauf nach dem Ablauf von 1 s auf 60 s (`SpaceRunner.CLOSE_GRACE_MILLIS`, siehe Nachtrag in [ADR 0017](0017-einrichtung-und-betrieb.md)). In dieser Zeit antworten authentifizierte Anfragen mit `410 space_closed`. Medien und Vorschauen gibt es nicht mehr.
- **Offen:** Die Seite mit Restzeit, Banner (10 Minuten), Dialog (1 Minute), Ende-Ansicht und Verbindungsverlust. Die Benachrichtigungen des Gastgebers. Der Hinweis im Beenden-Dialog. Alles davon liegt in den Teilen 6 und 7 des Auftrags und ist noch nicht begonnen.

### 7. Gäste-Seite (offen)

Raster, unendliches Scrollen, Filter „Meine“, Detail, Download, Zurückziehen mit Bestätigung, Speicheranzeige, Abfrage alle 10 bis 15 Sekunden bei sichtbarer Seite, i18n de und en, Barrierefreiheit. Noch nicht begonnen. Die Browser-Tests (Node mit CDP, headless) auch nicht.

## Konsequenzen

- Die Signatur ist ein Bearer-ähnliches Recht für 10 bzw. 30 Minuten. Wer die URL aus dem WLAN abgreift, kann das Medium in dieser Zeit lesen. Das ist mit dem Ziel „nur im WLAN, nur für Teilnehmer“ vereinbar. Die Entfernung eines Teilnehmers wirkt sofort.
- Ein Neustart des Servers macht alle Adressen ungültig. Die Seite muss dann neu anfragen. Das ist im Plan vorgesehen.
- Die Schlüsselverwaltung bleibt im Speicher. Eine Persistenz des Schlüssels ist nicht vorgesehen.

## Offen

- Vorschauen (Abschnitt 3) mit Migration 6 → 7 und Migrationstest.
- Codec-Erkennung und `videoCodec` (Abschnitt 2).
- Gäste-Seite, Browser-Tests, Testkatalog-Abschnitt „Medien im Browser“.
- Ende-Hinweise für Gäste und für den Gastgeber (Abschnitt 6).
