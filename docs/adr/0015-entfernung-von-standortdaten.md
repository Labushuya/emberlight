# ADR 0015: Entfernung von Standortdaten (Meilenstein 3, Paket 3.1b)

Status: Angenommen, 19.10.2026. Umsetzung in Paket 3.1b. Die Festlegungen 1 bis 9 gelten, die Abweichungen stehen unten.

Nachtrag 19.10.2026 (gleiche Umsetzung): HEIC, HEIF und AVIF sind umgesetzt (Item-Walker). Die Sicherungskopie entfällt zugunsten von Plan/Apply. Standort-Boxen unter `udta` werden in `free` umbenannt. Prüfung und Hash laufen in einem Lesedurchgang. Die Abschnitte „HEIC, HEIF, AVIF“, „Plan und Apply“, „Kein Null Island“, „Lesedurchgänge“ und „Fehlerbehandlung“ sind neu.

Die Festlegungen 1 bis 3 und die Reihenfolge der Prüfungen stammen aus der Nutzerentscheidung vom 19.10.2026. Die Fundorte stammen aus der Recherche vom selben Tag. Was in dieser Umsetzung nicht erreicht wurde, steht unter „Abweichungen und offene Punkte“.

## Kontext

Der Space läuft auf dem Gerät des Gastgebers. Gäste laden Medien hoch (ADR 0014). KONZEPT („Schutz gegen Manipulation“) legt fest: GPS-Standortdaten werden beim Hochladen standardmäßig entfernt. Bis 3.1b speichert der Server Dateien unverändert. Das ist nur tragbar, solange keine Oberfläche den Upload erreicht (ADR 0013, Zwischenzustand 3.1a). Paket 3.5 darf den Upload erst anbieten, wenn 3.1b gemergt ist.

Dieses ADR beschreibt die Entfernung von Standortdaten, die Einwilligung für nicht sicher bereinigbare Dateien und die Prüfung nach dem Bereinigen.

## Entscheidungen

1. **Einwilligung** (Nutzerin bzw. Nutzer, 19.10.2026):
   - Lässt sich der Standort nicht sicher entfernen, antwortet der Upload mit `422` und `{"error":"location_not_removable","reason":"<code>"}`. Es wird nichts gespeichert, die Temp-Datei wird gelöscht.
   - Die Gästeseite (Paket 3.5) fragt dann: „Standort kann nicht entfernt werden. Trotzdem mit Standort hochladen?“
   - Bei Ja lädt sie erneut mit dem Header `X-Emberlight-Keep-Location: 1` hoch. Dann wird die Datei **unverändert** gespeichert, mit `locationKept = true`.
   - Der Header wirkt nur bei nicht bereinigbaren Dateien. Bereinigbare Dateien werden immer bereinigt. Der Wert ist exakt `1`. Jeder andere Wert ergibt `400` (`bad_request`).
2. **Markierung** (Nutzerin bzw. Nutzer, 19.10.2026): Medien mit `locationKept = true` liefert die Medienliste mit `"locationKept": true` aus. Die Gästeseite zeigt dann „Standort enthalten“.
3. **Eigener schlanker Scrubber** in `share:space`, ohne neue Laufzeit-Abhängigkeit:
   - Nur In-place-Überschreiben mit festen Füllbytes: `0x00` für binäre Werte, `0x20` für Text.
   - Keine Längen- bzw. Offset-Änderung, keine Neukompression. Eine geänderte Länge gilt nie als Erfolg.
   - PNG: CRC32 des geänderten Chunks wird neu berechnet.
   - Deterministisch: gleiche Eingabe, gleiche Bytes.
   - Zugriff per `RandomAccessFile` auf die Temp-Datei. Gelesen werden nur Metadatenbereiche. `mdat` und Bilddaten werden nie vollständig geladen, mit Ausnahme der Entropie-Suche im JPEG (siehe „Lesedurchgänge“). Die Prüfung nach dem Bereinigen läuft gestreamt in 64-KiB-Blöcken, ohne Speicherung.
   - Grenzen: Schachtelungstiefe 8, höchstens 10.000 Boxen, Chunks bzw. IFD-Einträge je Datei. Größere Textstücke (über 8 MiB) und Zwischenspeicher über 16 MiB gelten als `structure`. Bei Strukturfehlern wird fail closed entschieden, also „nicht bereinigbar“.
4. **Reihenfolge im Upload:** Empfang mit SHA-256 des Stroms (ein Schreibvorgang, kein Lesen), dann Strukturprüfung (nur Box-Köpfe), dann **Plan** (Walker ohne Schreiben in die Datei), dann **Prüfung** des Plans, dann **Scan und Hash** über den geplanten Stand (ein Lesedurchgang), dann **Apply** (Schreiben, nur bei Erfolg), dann Dedupe, dann atomarer Rename. `processing_version = 1` wird gespeichert.
5. **Was entfernt wird:** GPS-Koordinaten und zusätzlich **Ortsnamen** (Stadt, Region, Land, Sublocation; IPTC bzw. XMP `photoshop`, `Iptc4xmpCore`, `Iptc4xmpExt`).
6. **EXIF-MakerNote** (Tag 0x927C) wird vollständig mit `0x00` überschrieben (längenerhaltend). Herstellerdaten können verborgene Angaben enthalten.
7. **EXIF-GPS-IFD:** Entry-Count auf 0, alle Eintragsbytes, der Next-IFD-Zeiger und alle ausgelagerten Wertebereiche der GPS-Einträge werden genullt. Der Zeiger `GPSInfo` (0x8825) in IFD0 bleibt. Er zeigt auf ein leeres IFD. Ergebnis der Prüfung: siehe Abschnitt „Ergebnis zu Festlegung 7“.
8. **Motion Photos** (JPEG mit angehängtem MP4 nach dem EOI): Das MP4 wird rekursiv mit dem MP4-Scrubber bereinigt. Unbekannte nicht-leere Daten nach EOI, IEND oder nach der letzten Top-Level-Box ergeben `trailing_data`. MPF-Zusatzbilder liegen bei echten Multi-Picture-Dateien ebenfalls nach dem EOI des Hauptbilds. Sie gelten nicht als Anhängsel, sondern werden rekursiv als JPEG geprüft. **Eingebettete Thumbnails** (in TIFF, IFD1) werden genauso geprüft: Anhängsel nach deren EOI ergibt `trailing_data`, es bleibt nie ungeprüft stehen.
9. **Prüfung danach** (fail closed): Der Detektor läuft ohne Änderung über den geplanten Stand. Ein Byte-Scan über den ganzen Datenstrom sucht nach ISO-6709-Mustern `[+-]\d{1,3}\.\d+[+-]\d{1,3}\.\d+` und nach nicht geleerten Eigenschaften `GPSLatitude`, `GPSLongitude`, `exif:GPS*`. Scan und SHA-256 laufen in **einem** Durchgang über den geplanten Stand. Findet sich noch etwas: `verification_failed`, und nichts wird geschrieben. Wurde nichts geändert, läuft nur der Scan, und der beim Streamen berechnete Hash bleibt gültig.

## Reason-Codes

| Code | Bedeutung |
|---|---|
| `metadata_track` | Metadaten-Track mit Standort (`gpmd`, `camm`, `mebx` mit `location`-Key) oder Handler `meta` |
| `trailing_data` | Nicht-leere, unbekannte Daten nach EOI, IEND oder der letzten Box, auch nach einem Thumbnail-EOI |
| `compressed_text` | Komprimierter Text (zTXt, iTXt) mit Standort, der nicht sicher geändert werden kann |
| `extended_xmp` | Erweitertes XMP (APP1 `http://ns.adobe.com/xmp/extension/`) mit Standort. Es wird nur geprüft |
| `free_box_location` | `free`- oder `skip`-Box mit Standort-Muster oder `gps `-Box (Pittasoft-Muster) |
| `structure` | Unlesbare oder widersprüchliche Struktur, Grenzen überschritten, unparsbares XMP mit Standortverdacht, mehrdeutige HEIF-Boxen, fehlende `infe`-Einträge |
| `unsupported` | Format ohne Walker, nicht unterstützte HEIF-Konstruktion (Methode 2 bei Exif/XMP, fremde Datei, `infe` Version 0/1), unbekannte Box mit Standort-Text |
| `verification_failed` | Nach dem Planen findet die Prüfung noch Standort |

## Fundorte und Verfahren je Format

Quellenstand laut Recherche vom 19.10.2026, siehe Quellen. [V] = Quelle gelesen, [S] = Sekundärquelle, [Verm.] = Vermutung. Die Tests bestätigen die Verfahren, soweit sie synthetisch prüfbar sind.

| Format | Fundorte | Verfahren | Stand |
|---|---|---|---|
| JPEG | APP1 `Exif`, APP1 XMP, APP1 Extended XMP, APP13 IPTC (8BIM 0x0404), APP13 XMP (0x0424), APP2 MPF, Thumbnail (IFD1), Daten nach EOI | Segment-Walker. TIFF-Walker auf `Exif`. XMP-Scrubber. IPTC-IIM Ortsfelder 2:90, 2:92, 2:95, 2:100, 2:101 mit `0x20`. MPF-Zusatzbilder rekursiv. Motion-Photo-MP4 nach EOI | umgesetzt [V] |
| PNG | `eXIf`, `tEXt` und `iTXt` mit „Raw profile type exif“, `iTXt` `XML:com.adobe.xmp`, `zTXt` und komprimiertes `iTXt`, Daten nach IEND | Chunk-Walker mit CRC neu. Hex-Text bleibt gleich lang, geänderte Bytes werden als `0` in den Hex-Text geschrieben. Komprimierte Texte werden mit Größenlimit geprüft, nicht geändert [V, W3C PNG-3] | umgesetzt |
| WebP | RIFF-Chunks `EXIF` (mit oder ohne `Exif\0\0`) und `XMP ` (alle Vorkommen) | RIFF-Walker. RIFF-Größe bleibt gleich [V, Google RIFF] | umgesetzt |
| GIF | Application Extension `XMP DataXMP` | Nur das XMP bis `<?xpacket end ... ?>` wird mit `0x20` überschrieben, nie `0x00`. Magic-Trailer und Blockstruktur bleiben [S, Adobe XMP Part 3] | umgesetzt |
| MP4, MOV, 3GP | `udta` (auch in `trak`): `©xyz`, `loci`. `meta` mit `keys` und `ilst` (`com.apple.quicktime.location.*` und `©xyz`). XMP-`uuid`. `free`/`skip`. Metadaten-Tracks | Box-Walker, 64-bit-Größen, `size 0`. Text bleibt samt Länge und Sprache, nur der Wert wird mit `0x20` überschrieben. `©xyz` und `loci` werden geleert und in `free` umbenannt (siehe „Kein Null Island“). `keys` und Indizes bleiben [V, ExifTool QuickTime] | umgesetzt |
| HEIC, HEIF, AVIF | Exif-Items (`Exif`), XMP-Items (`mime` mit `application/rdf+xml`), alle Vorkommen auch in Thumbnails und Hilfsbildern. `moov` auf Top-Level (Sequenzen) | Item-Walker, siehe Abschnitt „HEIC, HEIF, AVIF“. Exif-Items wie TIFF, XMP-Items wie XMP. Mehrere Extents logisch zusammengesetzt. Konstruktionsmethode 2 bei Exif/XMP: `unsupported` [Verm. für die Feldgrößen, siehe Quellen] | umgesetzt, CI-Gegenprobe mit ExifTool offen bis CI-Lauf |

### HEIC, HEIF, AVIF

- `meta` ist eine FullBox mit Version 0. Der `hdlr` muss den Typ `pict` haben. Sonst `unsupported`. Ohne `hdlr` oder mit mehreren `hdlr`, `iinf`, `iloc` oder `idat`: `structure` (mehrdeutig).
- `iinf` (Zähler 16 Bit bei Version 0, sonst 32 Bit) mit `infe` in den Versionen 2 und 3. Versionen 0 und 1 kennen keinen Typ und sind `unsupported`. Ein Item in `iloc` ohne `infe`-Eintrag und doppelte IDs sind `structure`.
- `iloc` in den Versionen 0 bis 2. Größen für Offset, Länge, Basis-Offset und Index: 0, 4 oder 8 Bytes, sonst `structure`. Länge 0 ist `unsupported`. `data_reference_index` ungleich 0 (fremde Datei) ist `unsupported`. Unbekannte Version: `unsupported`.
- Konstruktionsmethode 0: Offset ab Dateianfang (Basis-Offset plus Extent-Offset). Methode 1: Offset ab Beginn des Nutzdatenbereichs von `idat`. Methode 2 ist bei Exif- und XMP-Items `unsupported`. Die Summe der Extents ist durch 16 MiB begrenzt.
- **Mehrere Extents** werden über [`ExtentStore`] als ein logisches Item gelesen und beschrieben. Die Länge bleibt, die Lücken zwischen den Extents bleiben unberührt (Test mit rückwärts liegenden Extents und Lücken).
- **Exif-Item:** Die ersten vier Bytes sind der BE-Offset des TIFF-Headers, gezählt ab dem Ende dieser vier Bytes (digiKam `dmetadata_libheif.cpp`). Üblich ist der Offset 6 mit `Exif\0\0` davor. Steht nach dem Offset ein `Exif\0\0`-Präfix, beginnt das TIFF danach. Dann TIFF-Walker wie bei JPEG (GPS-IFD leeren, MakerNote nullen, Thumbnail rekursiv).
- **XMP-Item:** XMP-Scrubber über das ganze Item.
- **Alle** Exif- und XMP-Items werden erfasst, auch die von Thumbnails und Hilfsbildern. Die Zuordnung über `iref` ist nicht nötig.
- **Sequenzen** (`moov` auf Top-Level, zum Beispiel Bursts): wie bei MP4 geprüft. ADR 0013 erlaubt Sequenzmarken nicht, die Prüfung bleibt trotzdem.
- Andere Item-Typen werden nicht ausgewertet. Die Prüfung nach dem Planen (Byte-Scan) deckt Standort-Text darin ab. Binäre Standortdaten außerhalb von Exif und XMP werden nicht erkannt. Das ist eine Restrisiko-Zeile.

## Kein Null Island

Ein geleerter Standort darf nicht als Position (0/0) gelesen werden. Deshalb:

- **Kinder von `udta`** mit Standort (`©xyz`, `loci`) werden nach dem Überschreiben zusätzlich in den Box-Typ **`free`** umbenannt. Das ist längenerhaltend (4 Bytes). `free` wird von Lesern ignoriert (ISO/IEC 14496-12 §8.1.2). Dieselbe Umbenennung gilt für `udta` unter `moov` und `trak`.
- **`ilst`-Items** werden nicht umbenannt, weil die `keys`-Indizes bestehen bleiben müssen. Die Werte werden mit Leerzeichen (`0x20`) statt Nullen überschrieben. Ein solcher Wert ist kein gültiger ISO-6709-Wert, aus dem ExifTool eine Position ableiten könnte. Nachweis: CI-Gegenprobe mit ExifTool (siehe „Prüfung und Gegenprobe“).
- Die Ausnahme „Nullkoordinaten im geleerten `loci` zählen nicht als Standort“ ist entfallen. Die Gegenprobe meldet keine `Lat=0 Lon=0`-Zeilen mehr.

## Plan und Apply (ersetzt die Sicherungskopie)

- Der Walker läuft zuerst gegen einen **Plan** im Speicher (`PlanStore`). Die Datei bleibt unverändert. Lesen liefert den geplanten Stand, damit Prüfung und Scan dieselben Bytes sehen wie nach dem Schreiben.
- Die Summe der geplanten Bytes ist durch 16 MiB begrenzt. Darüber gilt die Struktur als `structure`. So entsteht kein zusätzlicher Speicher in Abhängigkeit von der Dateigröße, auch nicht bei einem 2-GiB-Video.
- **Apply** schreibt die Bereiche nur, wenn Prüfung und Scan bestanden. Bei einem Fehler davor bleibt die Datei bit-identisch.
- **Einwilligung** und nicht bereinigbar: Es wird nichts geschrieben. Die Datei bleibt unverändert, `locationKept = true`. Der Hash ist der des Streams.
- Die Sicherungskopie und ihre Wiederherstellung entfallen ersatzlos. Im Space- und im Temp-Verzeichnis entsteht keine zweite Datei (Test `SpaceVaultTest`).
- Tests: `PlanApplyTest.planChangesAreNotWrittenWhenALaterCheckRefusesTheFile` (bereinigbarer EXIF-Standort plus Anhängsel nach IEND: kein einziger Schreibvorgang), `HeifScrubberTest` (`assertNotRemovable` prüft die Bytes).

## Lesedurchgänge pro Upload

Gemessen mit einem zählenden Speicher (`PlanApplyTest.readPassesPerUploadMatchTheDocumentedCount`). Ein Durchgang entspricht der Dateigröße.

| Schritt | Lesedurchgänge | Anmerkung |
|---|---|---|
| Empfang (Stream), SHA-256 beim Schreiben | 0 | Der Hash entsteht im Schreibweg |
| Strukturprüfung (Box-Kette) | 0 vollständig | Liest nur Box-Köpfe (bis 16 Bytes je Box) |
| Plan (Walker) | 0 vollständig, außer JPEG | MP4, PNG, HEIC und die anderen Walker lesen nur Metadaten |
| Plan (JPEG) | 1 | Die Suche nach dem nächsten Marker streamt die Entropiedaten |
| Prüfung des Plans (Detektor) | 0 vollständig, außer JPEG | JPEG streamt die Entropiedaten erneut |
| Scan und Hash (ein Durchgang) | 1 | Nur bei Änderung wird der Hash hier berechnet; sonst wird der Stream-Hash übernommen |
| Mit Einwilligung, nicht bereinigbar | 0 zusätzlich | Scan und Hash entfallen, Datei bleibt unverändert |

**Ergebnis:** Pro Upload gibt es **einen** vollständigen Lesedurchgang (Scan mit Hash). Gemessen ist das für MP4 und PNG. Für HEIC, HEIF, AVIF, WebP, GIF, MOV und 3GP liest der Code nach Lektüre nur Metadaten, das ist aber nicht eigens gemessen. Bei JPEG sind es **drei**, weil die Entropiedaten im Plan und in der Prüfung gestreamt werden (gemessen). Eine Verkürzung auf zwei ist möglich, indem der Plan die Entropiegrenzen merkt. Das ist offen und nicht umgesetzt.

## XMP (gemeinsam)

Toleranter Byte-Scanner über UTF-8. Namespaces werden über die URI aufgelöst, nicht über das Präfix:
- `http://ns.adobe.com/exif/1.0/`: alle `GPS*`-Eigenschaften
- `http://ns.adobe.com/photoshop/1.0/`: City, State, Country
- `http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/`: Location, CountryCode
- `http://iptc.org/std/Iptc4xmpExt/2008-02-29/`: LocationCreated, LocationShown

Elemente werden samt Tags, Attribute als `name="wert"` durch Leerzeichen gleicher Länge ersetzt. Das XML bleibt wohlgeformt. Das wird im Test mit einem XML-Parser geprüft. Unlesbares XML mit Standortverdacht ist `structure`.

### TIFF/IFD (gemeinsam)

Gemeinsamer Walker für alle EXIF-Vorkommen (II und MM, Bounds, Schleifenschutz über besuchte Offsets). Er prüft IFD0, das Exif-IFD mit MakerNote, das GPS-IFD und das IFD1-Thumbnail. Das Thumbnail ist ein eigenes JPEG und wird rekursiv geprüft, einschließlich Anhängsel nach dem EOI [V, ExifTool GPS, EXIF].

## Determinismus und Dedupe

Die Füllbytes sind fest (`0x00`, `0x20`), die Längen bleiben. Zwei Dateien, die sich nur im Standort unterscheiden, werden nach dem Bereinigen gleich. Der Test prüft das am Hash (`imagesThatDifferOnlyInTheLocationShareTheSameBytesAfterScrubbing`). Dedupe läuft über den Hash der verarbeiteten Bytes. Medien vor 3.1b behalten ihren Hash (`processing_version = 0`).

## Index und API

- Room-Version 1 → 2 mit `location_kept` (Boolean, Standard `false`) und `processing_version` (Int, Standard `0`). Die Migration ist additiv. Der Test `SpacesDatabaseMigrationTest` prüft sie an einer von Hand angelegten Version-1-Datenbank.
- `POST /api/v1/media`: `422` (`location_not_removable`, mit `reason`), Einwilligungs-Header wie oben, `201` und `200` enthalten `locationKept`.
- Medienliste: `locationKept` je Eintrag.
- Details: ADR 0014, Abschnitt „API v1“.

## Fehlerbehandlung

- Im Scrubber werden nur `Unremovable` und `RuntimeException` als `structure` gewertet. Dazu gehören unerwartete Zugriffe auf defekte Strukturen.
- `Error`s wie `OutOfMemoryError` und `StackOverflowError` werden nicht verschluckt. Ebenso I/O-Fehler (`IOException`), damit der Server sie meldet. Der Vault löscht die Temp-Datei und wirft weiter.
- Tests: `PlanApplyTest` (`outOfMemoryErrorIsNeverSwallowedAsStructure`, `stackOverflowErrorIsNeverSwallowedAsStructure`, `ioErrorPropagatesSoTheServerReportsIt`, `unexpectedRuntimeFailureInTheFormatIsStructure`).

## Ergebnis zu Festlegung 7

Die Tests prüfen mit ExifInterface (`getLatLong()`, Robolectric) und metadata-extractor (`GpsDirectory`, `getGeoLocation()`) an einer Datei mit vollständigem GPS-IFD und Thumbnail. Nach dem Bereinigen melden beide keinen Standort. Der Zeiger `GPSInfo` in IFD0 bleibt, und das leere GPS-IFD reicht. Eine zusätzliche Behandlung des Zeigers war deshalb nicht nötig. Das gilt für die getesteten Lesewege, nicht für alle denkbaren Leser.

Für HEIF liest ExifInterface (Robolectric) das synthetische Item nicht. metadata-extractor liest es. Die HEIF-Tests nutzen deshalb metadata-extractor als unabhängigen Leser.

## Prüfung und Gegenprobe

- **Eigener Detektor**: derselbe Walker läuft ohne Änderung über den geplanten Stand. Das deckt Strukturfehler nicht auf, die der Walker selbst übersieht. Dafür ist der Byte-Scan da.
- **Byte-Scan mit Hash**: streamend über die ganze Datei, 64-KiB-Fenster mit 256 Bytes Überlappung. Derselbe Durchgang berechnet den SHA-256 der verarbeiteten Bytes, sofern etwas geändert wurde.
- **Unabhängige Leser**: ExifInterface und metadata-extractor in den JVM-Tests (`LocationScrubberTest`, `HeifScrubberTest`).
- **Gegenprobe mit Werkzeugen** (`ExternalToolCrossCheckTest`): nur in der CI. Der Runner installiert ExifTool, ffmpeg, `heif-enc` (mit dem x265-Plugin) und `avifenc` per apt (Pakete für noble geprüft: `libimage-exiftool-perl`, `ffmpeg`, `libheif-examples` 1.17.6, `libheif-plugin-x265` 1.17.6, `libavif-bin` 1.0.4). Lokal fehlen sie, dann wird der Test übersprungen. Mit `CI=true` schlägt der Test bei fehlenden Werkzeugen fehl.
  - Ablauf je Fall (MP4, MOV, JPEG, PNG, WebP, HEIC, AVIF): ExifTool bestätigt den Standort vorher. Der Scrubber bereinigt. ExifTool findet danach keinen Standort-Wert und keine Null-Koordinaten mehr.
  - HEIC entsteht aus einem synthetischen PNG per `heif-enc`, danach schreibt ExifTool EXIF-GPS hinein. AVIF entsteht per `avifenc`, danach schreibt ExifTool XMP-GPS hinein.
  - MP4 und MOV entstehen per ffmpeg mit `-metadata location=…` und dem Apple-Key.
- **Ergebnis der CI-Gegenprobe**: siehe Abschnitt „CI-Nachweis“ (wird nach dem ersten grünen Lauf ergänzt).
- **Robustheit**: abgeschnittene und zufällig veränderte Eingaben (fest seeded) dürfen nie Standort durchlassen und nie eine Ausnahme aus dem Scrubber werfen. Unerwartete Laufzeitfehler im Walker gelten als `structure`.

## Restrisiken

- **Herstellerspezifische Trailer** (zum Beispiel Samsung SEF) landen bei der Einwilligung, nicht bei der automatischen Bereinigung.
- **Echte iPhone-Dateien** sind ungeprüft. Die HEIC-Walker sind nur mit synthetischen Dateien und der ExifTool-Gegenprobe in der CI belegt. Der Gerätetest mit echten Aufnahmen fehlt.
- **Echte Honor-Aufnahmen** folgen im Gerätetest.
- **MakerNote** wird geleert. Herstellerdaten, die für die Bildwiedergabe nötig wären, gehen dabei verloren.
- **Unbekannte Strukturen** werden abgelehnt, nicht durchgelassen. Das kann gültige, aber ungewöhnliche Dateien betreffen. Sie landen dann ebenfalls bei der Einwilligung.
- **Ein Standort im Bild selbst** (etwa ein Straßenschild) bleibt. Dieses ADR behandelt nur Metadaten.
- **Binäre Standortdaten außerhalb von Exif und XMP** in HEIF-Items (andere Item-Typen) werden nicht ausgewertet. Der Byte-Scan erkennt nur Text-Muster.
- **Konstruktionsmethode 2** bei Bild-Items (nicht Exif/XMP) wird nicht ausgewertet. Auch dort greift der Byte-Scan nur für Text.
- **`free`-Umbenennung**: Leser, die `free` nicht ignorieren, könnten die geleerten Werte anders deuten. Das widerspricht ISO 14496-12. Die Gegenprobe deckt ExifTool ab.

## Abweichungen und offene Punkte

1. **Quellen nicht erneut gelesen.** Die Fundorte stammen aus der Recherche vom 19.10.2026. Die Feldgrößen von `iloc` und `infe` sind aus der Kenntnis der Spezifikation übernommen [Verm.], nicht aus einer erneuten Lektüre von ISO/IEC 14496-12 und 23008-12. Die Tests prüfen die Verfahren nur auf synthetischen Dateien, die nach diesem Stand gebaut sind.
2. **Echte Testdateien** mit Standort (Ordner außerhalb des Repos) sind in diesem Paket nicht verwendet worden.
3. **Ein Fehler im Scrubber bricht nie mit einer Ausnahme ab**, sondern wird als `structure` gemeldet, wenn es ein Laufzeitfehler der Struktur ist. Errors und I/O-Fehler laufen weiter. Fehlschläge in den Tests müssen deshalb als Fehler der Umsetzung gelesen werden.
4. **JPEG mit drei Lesedurchgängen.** Eine Verkürzung auf zwei ist möglich, indem der Plan die Entropiegrenzen an die Prüfung weitergibt. Nicht umgesetzt.
5. **ExifTool-Nachweis für die `ilst`-Werte mit Leerzeichen**: Die CI-Gegenprobe muss zeigen, dass ExifTool dort keine Position ableitet. Ohne grünen CI-Lauf ist das offen.
6. **Gerätetest** mit echten Fotos und Videos (Paket 3.1b, Testkatalog in PLAN-M3.md) steht aus.

## Quellen

Quellenstand laut Recherche vom 19.10.2026, nicht in dieser Umsetzung erneut gelesen:

- ExifTool, Tag-Tabellen: TagNames GPS, EXIF, QuickTime, XMP, https://exiftool.org/TagNames/
- W3C, PNG Specification, Third Edition (PNG-3): https://www.w3.org/TR/png-3/
- Google, WebP RIFF-Container: https://developers.google.com/speed/webp/docs/riff_container
- digiKam, `dmetadata_libheif.cpp` (Exif-Item-Offset, Lesen von HEIF-Metadaten)
- ISO/IEC 14496-12 (ISO-BMFF, `free`, `udta`) und ISO/IEC 23008-12 (HEIF, `meta`, `iinf`, `iloc`): Feldgrößen aus der Kenntnis übernommen, nicht erneut gelesen [Verm.]
- drewnoakes, metadata-extractor (Apache-2.0): https://github.com/drewnoakes/metadata-extractor
- Adobe, XMP Specification Part 3 (Speicherung in Dateien, GIF-Extension): https://developer.adobe.com/xmp/docs/XMPSpecifications/
- Skia, MPF-Lesen (Quelltext aus der Recherche, nicht erneut geprüft): Motion-Photo- und Multi-Picture-Struktur
- AndroidX Media3, `Mp4LocationData`: Standort in MP4-Metadaten
- CIPA DC-007, Multi-Picture Format (MPF)
- Ubuntu noble (24.04) Paketindex, geprüft am 19.10.2026: `libheif-examples` 1.17.6, `libheif-plugin-x265` 1.17.6, `libavif-bin` 1.0.4

Lizenz der Test-Abhängigkeit metadata-extractor: Apache-2.0. Sie ist nur `testImplementation`, keine Laufzeitabhängigkeit.
