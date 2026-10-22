# ADR 0015: Entfernung von Standortdaten (Meilenstein 3, Paket 3.1b)

Status: Angenommen, 19.10.2026. Umsetzung in Paket 3.1b. Die Festlegungen 1 bis 9 gelten, die Abweichungen stehen unten.

Nachtrag 19.10.2026 (gleiche Umsetzung): HEIC, HEIF und AVIF sind umgesetzt (Item-Walker). Die Sicherungskopie entfällt zugunsten von Plan/Apply. Standort-Boxen unter `udta` werden in `free` umbenannt. Prüfung und Hash laufen in einem Lesedurchgang. Die Abschnitte „HEIC, HEIF, AVIF“, „Plan und Apply“, „Kein Null Island“, „Lesedurchgänge“ und „Fehlerbehandlung“ sind neu.

Nachtrag 21.10.2026 (Hotfix 2, Abschnitt „Hotfix 2“ unten): Ein Honor-Kamerafoto hatte den GPS-Block in einem zweiten Exif-Block in APP10 behalten. Jeder APPn-Block mit Exif-Kennung geht jetzt durch den TIFF-Walker. Unbekannte APPn-Segmente und COM werden längenerhaltend genullt. Eine strenge Gegenprobe (`StrictLocationProbe`) läuft in der Nachkontrolle über den Rohstrom und schlägt fail closed an.

Nachtrag 21.10.2026 (Restrisiken, Abschnitt „Restrisiken nach Nachtrag 21.10.2026“ unten): Die binären Restrisiken aus Hotfix 2 sind für HEIF, MP4 und PNG geschlossen, soweit der Abschnitt sie benennt. Binärer Standort wird jetzt erkannt und längenerhaltend entfernt. Eine Teildatei nach einem Absturz im Ordner eines gelöschten Space wird beim Start entfernt (ADR 0021, Nachtrag 3.6d-2).

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
8. **Motion Photos** (JPEG mit angehängtem MP4 nach dem EOI): Das MP4 wird rekursiv mit dem MP4-Scrubber bereinigt. Unbekannte Daten hinter dem logischen Dateiende werden abgeschnitten (Abschnitt „Anhänge nach dem Dateiende“). MPF-Zusatzbilder liegen bei echten Multi-Picture-Dateien ebenfalls nach dem EOI des Hauptbilds. Sie gelten nicht als Anhängsel, sondern werden rekursiv als JPEG geprüft. **Eingebettete Thumbnails** (in TIFF, IFD1) werden genauso geprüft: Ein Anhängsel nach deren EOI liegt innerhalb einer Struktur und lässt sich nicht abschneiden. Es ergibt `trailing_data` und bleibt nie ungeprüft stehen.
9. **Prüfung danach** (fail closed): Der Detektor läuft ohne Änderung über den geplanten Stand. Ein Byte-Scan über den ganzen Datenstrom sucht nach ISO-6709-Mustern `[+-]\d{1,3}\.\d+[+-]\d{1,3}\.\d+` und nach nicht geleerten Eigenschaften `GPSLatitude`, `GPSLongitude`, `exif:GPS*`. Scan und SHA-256 laufen in **einem** Durchgang über den geplanten Stand. Findet sich noch etwas: `verification_failed`, und nichts wird geschrieben. Wurde nichts geändert, läuft nur der Scan, und der beim Streamen berechnete Hash bleibt gültig.
10. **Anhänge nach dem Dateiende** (Festlegung 19.10.2026, Befund aus echten Aufnahmen): Unbekannte Anhänge hinter dem logischen Dateiende werden beim Upload abgeschnitten. Bekannte eingebettete Teile bleiben erhalten und werden bereinigt. Details und Befund im Abschnitt „Anhänge nach dem Dateiende“.

## Anhänge nach dem Dateiende

**Befund** aus echten Aufnahmen (lokaler Lauf, 19.10.2026; nur Größenordnungen und Strukturtypen, keine Metadatenwerte):

- Kamera-JPEG: Nach dem EOI folgt ein Block von rund 500 KB ohne erkennbare Struktur. Kein MPF. Vorher `trailing_data`, also nur mit Einwilligung hochladbar.
- Kamera-MP4: Nach der letzten Top-Level-Box (`mdat`) folgen rund 480 KB ohne gültige Box-Struktur. `ContentInspector.hasValidStructure` lehnte die Datei vor dem Scrubber mit 415 ab. Das war ein Fehler: Das Video war nicht einmal mit Einwilligung hochladbar.
- Motion-Photo (Samsung-artig): Nach dem EOI folgt ein 24-Byte-Kopf mit dem Marker `MotionPhoto_Data` bei Offset 8, danach ein MP4. Am Dateiende steht ein SEF-artiger Trailer (`SEFH` bis `SEFT`, 32 Bytes). Vorher `trailing_data`.

**Festlegung:** Unbekannte Anhänge hinter dem logischen Dateiende werden beim Upload abgeschnitten (Truncate). Bekannte eingebettete Teile bleiben erhalten und werden bereinigt.

- **Logisches Ende je Format:**
  - JPEG: Ende des Hauptbilds (EOI). Dazu kommen MPF-Zusatzbilder, deren Bereiche über die MPF-Offsets bekannt sind, und ein angehängtes MP4. Das MP4 wird über seinen `ftyp` gefunden: direkt nach dem EOI (Google, Offset 4) oder hinter einem Kopf mit `MotionPhoto_Data` (Samsung-artig, MP4 beginnt bei Offset 24). Das MP4 wird rekursiv bereinigt. Danach ist alles abgeschnitten, auch ein SEF-Trailer.
  - MP4, MOV, 3GP, HEIF, AVIF: Ende der letzten gültigen Top-Level-Box. Gültig heißt: Größe mindestens 8 (oder 64-Bit ab 16, oder `size 0` bis zum Dateiende), innerhalb der Datei, Typ aus vier druckbaren ASCII-Zeichen. Folgt kein gültiger Kopf, ist der Rest Anhang.
  - PNG: Ende des `IEND`-Chunks. WebP: RIFF-Größe plus 8. GIF: Trailer `0x3B`.
- **Strukturprüfung für Anhänge (tolerant, fail closed).** Ein Kopf mit einem bekannten Top-Level-Typ (`ftyp`, `moov`, `mdat`, `free`, `skip`, `wide`, `uuid`, `meta`, `pnot`, `moof`, `mfra`, `styp`, `sidx`, `idat`, `junk`) und kaputter Größe ist eine beschädigte Kette. Die Prüfung lehnt sie mit 415 bzw. `structure` ab. Ein Kopf mit unbekanntem Typ und kaputter Größe beginnt dagegen den Anhang. Sonst ginge eine mitten in der Datei beschädigte Box samt allem Folgenden still verloren. Restrisiko: Ein Anhang, dessen Kopf zufällig einen bekannten Typ trägt, wird abgelehnt. Das ist fail closed und kein Datenverlust.
- **Nicht abschneidbar** sind Lücken innerhalb einer Struktur, also zwischen zwei bekannten Teilen. Dazu zählen ein Anhängsel nach dem EOI eines eingebetteten Thumbnails und eine Lücke zwischen MPF-Bildern. Sie bleiben `trailing_data`.
- **Plan und Apply:** Der Plan enthält die Ziel-Länge. Apply schreibt die Überschreibungen und setzt danach die Länge (`RandomAccessFile.setLength`). Hash und Nachkontrolle laufen über den Stand nach dem Kürzen. Die Nachkontrolle meldet einen Anhang, der danach noch da wäre, als `verification_failed`. Das Ergebnis ist deterministisch.
- **Einwilligung:** Bei Einwilligung mit nicht bereinigbarem Rest bleibt die Datei unverändert, also auch nicht abgeschnitten.
- **Größe:** `size_bytes` ist die Länge der gespeicherten Datei nach dem Kürzen.
- **Folge:** Herstellerextras im Anhang gehen verloren (etwa der Samsung-SEF-Index oder Bearbeitungsdaten). Bild bzw. Video bleiben vollständig. Die Nutzerin bzw. der Nutzer soll das erfahren und widersprechen können. Der Hinweis ist nicht umgesetzt (siehe „Abweichungen und offene Punkte“).
- **Tests:** `TrailingCutTest` (JPEG mit Zufallsanhang und mit GPS-JPEG als Anhang, MPF mit Anhang, Google- und Samsung-Motion mit Trailer, MP4 nach `mdat`, kaputte Kette bleibt 415, PNG, WebP, GIF, HEIF, Determinismus). `ContentInspectorTest` und `SpaceVaultTest` für die Strukturprüfung und die gespeicherte Größe. Der Polyglot-PNG-Test (Anhang hinter IEND) erwartet jetzt das abgeschnittene PNG statt `trailing_data`, weil der Anhang nicht mehr Teil der Datei bleibt.

## Reason-Codes

| Code | Bedeutung |
|---|---|
| `metadata_track` | Metadaten-Track mit Standort (`gpmd`, `camm`, `mebx` mit `location`-Key) oder Handler `meta` |
| `trailing_data` | Lücke innerhalb einer Struktur (zwischen zwei bekannten Teilen, nach dem EOI eines eingebetteten Thumbnails), die sich nicht abschneiden lässt. Anhänge hinter dem logischen Dateiende werden abgeschnitten und lösen diesen Code nicht aus |
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
- **Apply** schreibt die Bereiche nur, wenn Prüfung und Scan bestanden. Bei einem Fehler davor bleibt die Datei bit-identisch. Plan und Prüfung laufen im Fehlerfang (Strukturfehler gelten als `structure`). Das Schreiben läuft außerhalb davon, damit ein Fehler dort nie als Strukturfehler mit halb geschriebener Datei endet. Zum Schreiben gehört auch das Kürzen (siehe „Anhänge nach dem Dateiende“).
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
| Strenge Gegenprobe (Rohstrom, Hotfix 2) | 1 | Liest den Plan einmal im Fensterdurchlauf: TIFF-Header und Textmuster zugleich |
| Scan und Hash (ein Durchgang) | 1 | Nur bei Änderung wird der Hash hier berechnet; sonst wird der Stream-Hash übernommen |
| Mit Einwilligung, nicht bereinigbar | 0 zusätzlich | Scan und Hash entfallen, Datei bleibt unverändert |

**Ergebnis:** Pro Upload gibt es seit Hotfix 2 **einen** zusätzlichen Lesedurchgang für die strenge Gegenprobe. Gemessen (`PlanApplyTest`): Bei JPEG sind es **vier** (Plan, Prüfung, Gegenprobe, Scan), bei PNG und MP4 **zwei** (Gegenprobe, Scan). Für HEIC, HEIF, AVIF, WebP, GIF, MOV und 3GP liest der Code nach Lektüre nur Metadaten; die Gegenprobe liest jeweils einmal den ganzen Strom. Eine Verkürzung ist möglich, indem Gegenprobe und Scan zusammengelegt werden. Das ist offen und nicht umgesetzt.

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

- **Herstellerspezifische Trailer** (zum Beispiel Samsung SEF) werden abgeschnitten. Herstellerextras im Anhang gehen dabei verloren. Bild und Video bleiben vollständig (Abschnitt „Anhänge nach dem Dateiende“).
- **Echte iPhone-Dateien** sind ungeprüft. Die HEIC-Walker sind nur mit synthetischen Dateien und der ExifTool-Gegenprobe in der CI belegt. Der Gerätetest mit echten Aufnahmen fehlt.
- **Echte Kamera-Aufnahmen** sind lokal in Stichproben geprüft (Abschnitt „Anhänge nach dem Dateiende“), nicht auf dem Gerät über den Upload.
- **MakerNote** wird geleert. Herstellerdaten, die für die Bildwiedergabe nötig wären, gehen dabei verloren.
- **Unbekannte Strukturen** werden abgelehnt, nicht durchgelassen. Das kann gültige, aber ungewöhnliche Dateien betreffen. Sie landen dann ebenfalls bei der Einwilligung.
- **Ein Standort im Bild selbst** (etwa ein Straßenschild) bleibt. Dieses ADR behandelt nur Metadaten.
- **Binäre Standortdaten außerhalb von Exif und XMP** in HEIF-Items (andere Item-Typen) werden nicht ausgewertet. Der Byte-Scan erkennt nur Text-Muster.
- **Konstruktionsmethode 2** bei Bild-Items (nicht Exif/XMP) wird nicht ausgewertet. Auch dort greift der Byte-Scan nur für Text.
- **`free`-Umbenennung**: Leser, die `free` nicht ignorieren, könnten die geleerten Werte anders deuten. Das widerspricht ISO 14496-12. Die Gegenprobe deckt ExifTool ab.

## Abweichungen und offene Punkte

1. **Quellen nicht erneut gelesen.** Die Fundorte stammen aus der Recherche vom 19.10.2026. Die Feldgrößen von `iloc` und `infe` sind aus der Kenntnis der Spezifikation übernommen [Verm.], nicht aus einer erneuten Lektüre von ISO/IEC 14496-12 und 23008-12. Die Tests prüfen die Verfahren nur auf synthetischen Dateien, die nach diesem Stand gebaut sind.
2. **Echte Testdateien** mit Standort (Ordner außerhalb des Repos) wurden nur lokal und nur lesend verwendet. Ergebnis: Befund im Abschnitt „Anhänge nach dem Dateiende“. Keine Dateien und keine Metadatenwerte im Repo.
3. **Ein Fehler im Scrubber bricht nie mit einer Ausnahme ab**, sondern wird als `structure` gemeldet, wenn es ein Laufzeitfehler der Struktur ist. Errors und I/O-Fehler laufen weiter. Fehlschläge in den Tests müssen deshalb als Fehler der Umsetzung gelesen werden.
4. **JPEG mit drei Lesedurchgängen.** Eine Verkürzung auf zwei ist möglich, indem der Plan die Entropiegrenzen an die Prüfung weitergibt. Nicht umgesetzt.
5. **ExifTool-Nachweis für die `ilst`-Werte mit Leerzeichen**: Die CI-Gegenprobe muss zeigen, dass ExifTool dort keine Position ableitet. Ohne grünen CI-Lauf ist das offen.
6. **Gerätetest** mit echten Fotos und Videos (Paket 3.1b, Testkatalog in PLAN-M3.md) steht aus.
7. **Hinweis an die Nutzerin bzw. den Nutzer** über abgeschnittene Anhänge ist nicht umgesetzt. Die Gästeseite (Paket 3.5) existiert noch nicht. Die Einwilligung wirkt nur bei nicht bereinigbaren Dateien. Für einen Widerspruch gegen das Abschneiden gibt es deshalb noch keinen Weg. Das ist eine offene Produktentscheidung.
8. **XMP-Verweise** (`GCamera:MicroVideoOffset`, `Container:Directory`) werden nicht ausgewertet. Das angehängte MP4 wird über seinen `ftyp` gefunden, mit Suche nur direkt nach dem EOI bzw. hinter dem Marker `MotionPhoto_Data`.
9. **Gegenprobe mit ffmpeg-MP4 plus Anhang** (Fall `mp4-anhang` in `ExternalToolCrossCheckTest`) ist lokal nicht ausgeführt. ExifTool und ffmpeg fehlen lokal, und die CI-Läufe sind gesperrt. Der Nachweis steht aus, bis die CI wieder läuft. Die Logik ist durch `TrailingCutTest` und `SpaceVaultTest` mit synthetischen MP4 abgedeckt.

## Hotfix 2 (21.10.2026): APPn, unbekannte Segmente, strenge Gegenprobe

Anlass: Ein echtes Honor-Kamerafoto trug einen zweiten Exif-Block in APP10 mit GPS-IFD. Der Scrubber behandelte nur APP1, APP2 und APP13. Die Gegenprobe mit metadata-extractor meldete fälschlich „kein Standort“, weil es binäre GPS-Felder in APP10 nicht liest.

Festlegungen:

1. **Exif in jedem APPn.** Ein APPn-Segment (`0xE0` bis `0xEF`) mit `Exif\0\0` am Anfang geht durch den TIFF-Walker, unabhängig vom Marker. Im TIFF-Walker zählt jedes GPS-IFD.
2. **Unbekannte Segmente genullt.** Alles außer APP0 JFIF/JFXX, APP1 Exif/XMP/ExtXMP, APP2 ICC/MPF, APP13 Photoshop und APP14 Adobe wird längenerhaltend mit `0x00` überschrieben. Marker und Länge bleiben. Dasselbe gilt für COM (`0xFE`). Decoder ignorieren diese Inhalte; der Inhalt ist unbekannt und kann Standort tragen.
3. **Photoshop-IRB.** Die Ressourcen 0x0422 und 0x0423 (Exif) gehen durch den TIFF-Walker. Andere IRB-Ressourcen werden genullt, außer IPTC (0x0404) und XMP (0x0424), die wie bisher behandelt werden.
4. **Strenge Gegenprobe** (`StrictLocationProbe`, Hauptcode, unabhängig von den Walkern). In der Nachkontrolle nach dem Plan, vor dem Apply, fail closed:
   - sucht im Rohstrom nach jedem TIFF-Header (`II*\0`, `MM\0*`), auch in eingebetteten Strukturen, und liest dessen IFD0; zu einem GPS-Zeiger wird das GPS-IFD gelesen; jedes Byte ungleich null darin ist ein Befund (`gps_ifd_nonzero`); ein nicht lesbares GPS-IFD ist ein Befund (`gps_ifd_unreadable`);
   - Textmuster: ISO-6709-Koordinaten und GPS-Eigenschaften mit Ziffern im Wert (`location_text`);
   - die TIFF-Suche gilt nur für Formate mit zusammenhängendem TIFF (JPEG, PNG, WebP, GIF). In HEIF liegt ein Exif-Item oft in verstreuten Extents; dort würde ein Rohparsen falsch lesen. Dort gilt nur die Textsuche.
   - Ein Kandidat, der kein TIFF ist (zufällige Bytes in komprimierten Daten), wird nicht gewertet.
5. **PNG.** Andere `Raw profile type …` (iptc, app1 und ähnliche) werden unkomprimiert in `tEXt` und `iTXt` durch Ersetzen der Hex-Ziffern mit `0` genullt. Die Länge bleibt gleich. Komprimierte Varianten bleiben bei Standortfund `compressed_text`.
6. **WebP.** Unbekannte Chunks werden genullt (außer `VP8 `, `VP8L`, `VP8X`, `ALPH`, `ANIM`, `ICCP`). `ANMF` wird rekursiv durchlaufen, damit EXIF und XMP in Animationsbildern erfasst werden.

Verifikation: `JpegSegmentScrubTest` (Honor-Aufbau, unbekannte Segmente, ICC bleibt, APP10-Exif, IRB, COM, Gegenprobe), `ContainerGapScrubTest` (PNG-Raw-Profile, WebP-ANMF), `LocationScrubberTest` und `HeifScrubberTest` unverändert grün. Am echten Honor-Foto (lokal, nur lesend, Kopie im Hotfix-Temp-Ordner): Gegenprobe vorher `gps_ifd_nonzero`, nachher kein Befund; metadata-extractor meldet keinen Standort; ImageIO dekodiert die bereinigte Kopie.

**Restrisiko und offene Punkte (Hotfix 2):**

- Hex-kodierte TIFFs in PNG-Text mit unbekanntem Schlüsselwort (nicht `Raw profile type`) erkennt die strenge Gegenprobe nicht; sie greift nur bei Textmustern.
- HEIF-Items mit unbekanntem Typ und MP4-Boxen unter `udta` oder `meta`, die unbekannt sind, werden nicht genullt. Enthalten sie Standort als Text, lehnt die Gegenprobe ab (fail closed). Binär codierte Standortwerte in unbekannten Boxen erkennt sie nicht.
- Eine TIFF-Struktur in komprimierten Bilddaten kann zufällig wie ein gültiges IFD0 mit GPS-Zeiger aussehen. Die Wahrscheinlichkeit ist sehr gering, aber nicht null.
- Verschachtelte Strukturen in unbekannten Segmenten werden nicht rekursiv geprüft, weil sie genullt werden.

## Restrisiken nach Nachtrag 21.10.2026

Der Abschnitt „Restrisiko und offene Punkte (Hotfix 2)“ oben gilt bis hierher. Seine Punkte 1 (Hex-TIFF in PNG-Text mit fremdem Schlüsselwort) und 2 (HEIF-Items und MP4-Boxen mit binärem Standort) sind mit diesem Nachtrag umgesetzt. Die Punkte 3 (TIFF-Zufallstreffer in komprimierten Bilddaten) und 4 (verschachtelte Strukturen in genullten Segmenten) bleiben unverändert offen. Die Restrisiken 1 bis 4 aus dem Fix-Paket werden hier je Format benannt.

**Erkennung in Bereichen ohne bekannte Medien.** Die Gegenprobe und die Walker suchen binären Standort in Bereichen, die nicht als Bild, Video oder Audio bekannt sind: in unbekannten HEIF-Items, unbekannten Boxen und unbekannten Metadaten-Tracks, im PNG-Text. Dort gilt ein TIFF-Kopf (`II*\0`, `MM\0*`) mit einem GPS-IFD, dessen Einträge ungleich null sind, als Befund (`StrictLocationProbe.containsBinaryLocation`). Die Nutzdaten bekannter Bild-, Video- und Audio-Items und `mdat` werden nicht nach TIFF-Kopf abgesucht. Das vermeidet Fehlalarme in komprimierten Daten. Ein Textmuster (ISO 6709) und ein Befund in einem Hex-Profil bleiben Teil der bisherigen Textsuche.

**Entfernung je Format:**

- **HEIF.** Ein Item, das weder Exif, XMP noch ein bekannter Bildtyp ist (`hvc1`, `av01`, `grid`, `iden`, `iovl`, `tmap` und weitere), wird bei Befund mit Nullen über alle Extents geleert (`heif_item_location`). Die Länge bleibt. Ist das Item größer als der Speicherpuffer, gilt `location_not_removable`. Methode 2 (Item-Offset) bei einem solchen Item gilt als `unsupported`.
- **MP4.** Metadaten-Tracks mit GPS-Befund werden über `stsz`, `stsc` und `stco`/`co64` gelesen und ihre Samples mit 0x00 geleert (`location_track`):
  - `gpmd` mit dem GPMF-Schlüssel `GPS5` oder `GPS9`,
  - `camm` mit einem Record, dessen Typ (Little-Endian nach zwei Nullbytes) 5 oder 6 ist,
  - `djmd` (DJI) immer, weil die Felder nicht sicher lesbar sind.
  Die Sample-Tabellen werden nur für solche Tracks gelesen, bis zu 1 000 000 Einträge (`ScrubLimits.MAX_TRACK_SAMPLES`). Lange Video-Tracks mit vielen Samples bleiben damit unberührt. Ein `gpmd`- oder `camm`-Track ohne Befund bleibt unverändert. Fehlt eine Sample-Tabelle, gilt `metadata_track` wie bisher. Unbekannte Boxen mit TIFF-Befund werden geleert (`location_box_binary`). Mediendaten (`mdat`) werden außerhalb der Samples solcher Tracks nicht gelesen. Videostreams sind nie betroffen.
- **PNG.** `tEXt` und unkomprimiertes `iTXt` mit unbekanntem Schlüsselwort: Liegt ein TIFF mit GPS-Befund darin, roh oder als Hex-Profil, wird der ganze Textwert mit Nullen geleert (`text_location_binary`). Die CRC wird neu berechnet, die Länge bleibt. `zTXt` und komprimiertes `iTXt` mit einem solchen Befund sind `compressed_text` (nicht entfernbar, Einwilligung möglich).

**Grund `location_not_removable`.** Wird ein Befund gefunden, den der Walker nicht längenerhaltend entfernen kann, lehnt der Server mit 422 und `error = location_not_removable` ab. Der Detailgrund steht im Feld `reason`. Ohne Einwilligung („Standort entfernen“) wird der Upload nicht gespeichert. Die Gäste-Seite zeigt dafür die bestehende Rückfrage (`locationDialogText`, de und en, `textContent`). Mit Einwilligung bleibt die Datei unverändert und gilt als „Standort enthalten“.

**Verfahren.** Plan und Apply bleiben wie beschrieben: Die Datei wird erst nach bestandener Prüfung geschrieben. Die Prüfung läuft erneut über den geplanten Stand und findet nach dem Leeren keinen Befund mehr. Eine wiederholte Bereinigung ist ohne Änderung (idempotent).

**Restrisiko verbleibend (nach diesem Nachtrag):**

1. **HEIF.** Bildtypen werden nicht nach TIFF-Befund abgesucht. Ein Standort in einem Item, das eine Bildtyp-Kennung trägt, bleibt damit ungeprüft (Abwägung gegen Fehlalarme in Bilddaten). Ein unbekanntes Item mit Konstruktionsmethode 2 wird abgelehnt, nicht geleert.
2. **MP4.** Ein Metadaten-Track ohne Sample-Tabelle bleibt `metadata_track` (kein Befund möglich, Einwilligung nötig). Ein GPS-Befund in einer anderen Form als GPMF `GPS5`/`GPS9` oder camm Typ 5/6 bleibt unentdeckt. Der camm-Befund sucht den Record-Kopf an jeder Stelle, ohne Längen je Typ zu kennen. Das kann einen Fehlalarm geben, der nur den Metadaten-Track leert. Ein GPS-Befund in `mdat` außerhalb der Samples bekannter Tracks wird nicht gelesen. Überschneiden sich Metadaten-Samples mit Video-Samples, wird das nicht geprüft.
3. **PNG.** Hex-TIFF in unkomprimiertem Text mit unbekanntem Schlüsselwort ist erfasst. In komprimiertem Text bleibt ein Befund bei „compressed_text“ (Ablehnung statt Bereinigung), das ist Absicht.
4. **Teildatei nach Absturz.** Die Bereinigung läuft beim Start und nach jeder Löschung. Bis zum nächsten Start der App bleibt eine Teildatei im Ordner eines gelöschten Space liegen. Dort ist es ein Restrisiko, dass sie Standort enthält, falls der Absturz genau in der Upload-Phase lag.

Geschlossen sind damit: der binäre Standort in HEIF-Items unbekannter Art, in MP4-Metadaten-Tracks (GPMF, camm, djmd) und in unbekannten Boxen, der Hex-TIFF-Befund in PNG-Text mit fremdem Schlüsselwort sowie die Teildatei nach einem Absturz (Start). Gerätetests mit echten Aufnahmen stehen aus (Testkatalog 9.8 bis 9.12).

Verifikation: `BinaryLocationScrubTest` mit synthetischen Dateien (`BinaryLocationFixtures`, im Testcode erzeugt, keine Nutzerdateien). Jeder Fall prüft Befund vorher, Bereinigung, unveränderte Länge, gültige CRC und Wiederholung ohne Befund. Gegenproben mit zufälligen Bytes und TIFF-Kopf in Bild-Items und komprimierten PNG-Daten zeigen keinen Fehlalarm. Ein Video-Sample mit dem Text `GPS5` bleibt unverändert.

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
