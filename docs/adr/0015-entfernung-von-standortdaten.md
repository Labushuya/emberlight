# ADR 0015: Entfernung von Standortdaten (Meilenstein 3, Paket 3.1b)

Status: Entwurf, Umsetzung in Paket 3.1b (19.10.2026). Wird nach grüner CI auf „Angenommen, <Datum>“ gesetzt.

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
   - Zugriff per `RandomAccessFile` auf die Temp-Datei. Gelesen werden nur Metadatenbereiche. `mdat` und Bilddaten werden nie vollständig geladen. Das Suchen nach dem nächsten Marker im JPEG und die Prüfung nach dem Bereinigen laufen gestreamt in 64-KiB-Blöcken, ohne Speicherung.
   - Grenzen: Schachtelungstiefe 8, höchstens 10.000 Boxen, Chunks bzw. IFD-Einträge je Datei. Größere Textstücke (über 8 MiB) und Zwischenspeicher über 16 MiB gelten als `structure`. Bei Strukturfehlern wird fail closed entschieden, also „nicht bereinigbar“.
4. **Reihenfolge im Upload:** Empfang, dann Strukturprüfung (wie bisher), dann **Bereinigen** (oder `422`, oder mit Einwilligung unverändert), dann SHA-256 über die **verarbeiteten** Bytes, dann Dedupe, dann atomarer Rename. `processing_version = 1` wird gespeichert.
5. **Was entfernt wird:** GPS-Koordinaten und zusätzlich **Ortsnamen** (Stadt, Region, Land, Sublocation; IPTC bzw. XMP `photoshop`, `Iptc4xmpCore`, `Iptc4xmpExt`).
6. **EXIF-MakerNote** (Tag 0x927C) wird vollständig mit `0x00` überschrieben (längenerhaltend). Herstellerdaten können verborgene Angaben enthalten.
7. **EXIF-GPS-IFD:** Entry-Count auf 0, alle Eintragsbytes, der Next-IFD-Zeiger und alle ausgelagerten Wertebereiche der GPS-Einträge werden genullt. Der Zeiger `GPSInfo` (0x8825) in IFD0 bleibt. Er zeigt auf ein leeres IFD. Ergebnis der Prüfung: siehe Abschnitt „Ergebnis zu Festlegung 7“.
8. **Motion Photos** (JPEG mit angehängtem MP4 nach dem EOI): Das MP4 wird rekursiv mit dem MP4-Scrubber bereinigt. Unbekannte nicht-leere Daten nach EOI, IEND oder nach der letzten Top-Level-Box ergeben `trailing_data`. MPF-Zusatzbilder liegen bei echten Multi-Picture-Dateien ebenfalls nach dem EOI des Hauptbilds. Sie gelten nicht als Anhängsel, sondern werden rekursiv als JPEG geprüft.
9. **Prüfung danach** (fail closed): Der Detektor läuft ohne Änderung erneut über die Datei. Ein Byte-Scan über den ganzen Datenstrom sucht nach ISO-6709-Mustern `[+-]\d{1,3}\.\d+[+-]\d{1,3}\.\d+` und nach nicht geleerten Eigenschaften `GPSLatitude`, `GPSLongitude`, `exif:GPS*`. Findet sich noch etwas: `verification_failed`.

## Reason-Codes

| Code | Bedeutung |
|---|---|
| `metadata_track` | Metadaten-Track mit Standort (`gpmd`, `camm`, `mebx` mit `location`-Key) oder Handler `meta` |
| `trailing_data` | Nicht-leere, unbekannte Daten nach EOI, IEND oder der letzten Box |
| `compressed_text` | Komprimierter Text (zTXt, iTXt) mit Standort, der nicht sicher geändert werden kann |
| `extended_xmp` | Erweitertes XMP (APP1 `http://ns.adobe.com/xmp/extension/`) mit Standort. Es wird nur geprüft |
| `free_box_location` | `free`- oder `skip`-Box mit Standort-Muster oder `gps `-Box (Pittasoft-Muster) |
| `structure` | Unlesbare oder widersprüchliche Struktur, Grenzen überschritten, unparsbares XMP mit Standortverdacht |
| `unsupported` | Format ohne eigenen Walker (HEIC, HEIF, AVIF; `meta` auf Top-Level; unbekannte Box mit Standort-Text) |
| `verification_failed` | Nach dem Bereinigen findet die Prüfung noch Standort |

## Fundorte und Verfahren je Format

Quellenstand laut Recherche vom 19.10.2026, siehe Quellen. [V] = Quelle gelesen, [S] = Sekundärquelle, [Verm.] = Vermutung. Die Tests bestätigen die Verfahren, soweit sie synthetisch prüfbar sind.

| Format | Fundorte | Verfahren | Stand |
|---|---|---|---|
| JPEG | APP1 `Exif`, APP1 XMP, APP1 Extended XMP, APP13 IPTC (8BIM 0x0404), APP13 XMP (0x0424), APP2 MPF, Thumbnail (IFD1), Daten nach EOI | Segment-Walker. TIFF-Walker auf `Exif`. XMP-Scrubber. IPTC-IIM Ortsfelder 2:90, 2:92, 2:95, 2:100, 2:101 mit `0x20`. MPF-Zusatzbilder rekursiv. Motion-Photo-MP4 nach EOI | umgesetzt [V] |
| PNG | `eXIf`, `tEXt` und `iTXt` mit „Raw profile type exif“, `iTXt` `XML:com.adobe.xmp`, `zTXt` und komprimiertes `iTXt`, Daten nach IEND | Chunk-Walker mit CRC neu. Hex-Text bleibt gleich lang, geänderte Bytes werden als `0` in den Hex-Text geschrieben. Komprimierte Texte werden mit Größenlimit geprüft, nicht geändert [V, W3C PNG-3] | umgesetzt |
| WebP | RIFF-Chunks `EXIF` (mit oder ohne `Exif\0\0`) und `XMP ` (alle Vorkommen) | RIFF-Walker. RIFF-Größe bleibt gleich [V, Google RIFF] | umgesetzt |
| GIF | Application Extension `XMP DataXMP` | Nur das XMP bis `<?xpacket end ... ?>` wird mit `0x20` überschrieben, nie `0x00`. Magic-Trailer und Blockstruktur bleiben [S, Adobe XMP Part 3] | umgesetzt |
| MP4, MOV, 3GP | `udta` (auch in `trak`): `©xyz`, `loci`. `meta` mit `keys` und `ilst` (`com.apple.quicktime.location.*` und `©xyz`). XMP-`uuid`. `free`/`skip`. Metadaten-Tracks | Box-Walker, 64-bit-Größen, `size 0`. Text bleibt samt Länge und Sprache, nur der Wert wird mit `0x20` überschrieben. `loci`-Koordinaten mit `0x00`. `keys` und Indizes bleiben [V, ExifTool QuickTime] | umgesetzt |
| HEIC, HEIF, AVIF | Exif-Items, XMP-mime-Items | **Nicht umgesetzt.** Liefert `unsupported`, siehe Abweichungen | offen |

### XMP (gemeinsam)

Toleranter Byte-Scanner über UTF-8. Namespaces werden über die URI aufgelöst, nicht über das Präfix:
- `http://ns.adobe.com/exif/1.0/`: alle `GPS*`-Eigenschaften
- `http://ns.adobe.com/photoshop/1.0/`: City, State, Country
- `http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/`: Location, CountryCode
- `http://iptc.org/std/Iptc4xmpExt/2008-02-29/`: LocationCreated, LocationShown

Elemente werden samt Tags, Attribute als `name="wert"` durch Leerzeichen gleicher Länge ersetzt. Das XML bleibt wohlgeformt. Das wird im Test mit einem XML-Parser geprüft. Unlesbares XML mit Standortverdacht ist `structure`.

### TIFF/IFD (gemeinsam)

Gemeinsamer Walker für alle EXIF-Vorkommen (II und MM, Bounds, Schleifenschutz über besuchte Offsets). Er prüft IFD0, das Exif-IFD mit MakerNote, das GPS-IFD und das IFD1-Thumbnail. Das Thumbnail ist ein eigenes JPEG und wird rekursiv geprüft [V, ExifTool GPS, EXIF].

## Determinismus und Dedupe

Die Füllbytes sind fest (`0x00`, `0x20`), die Längen bleiben. Zwei Dateien, die sich nur im Standort unterscheiden, werden nach dem Bereinigen gleich. Der Test prüft das am Hash (`imagesThatDifferOnlyInTheLocationShareTheSameBytesAfterScrubbing`). Dedupe läuft über den Hash der verarbeiteten Bytes. Medien vor 3.1b behalten ihren Hash (`processing_version = 0`).

## Einwilligung und Sicherung

Mit Einwilligung ist eine Kopie der unveränderten Datei nötig. Scheitert die Bereinigung halb geschrieben (zum Beispiel bei einer Verifikationsfehlanzeige), stellt der Vault das Original aus der Kopie wieder her. Ohne Einwilligung entsteht keine Kopie. Die Kopie liegt im Space-Speicher unter einem Temp-Namen und wird in jedem Fall gelöscht.

## Index und API

- Room-Version 1 → 2 mit `location_kept` (Boolean, Standard `false`) und `processing_version` (Int, Standard `0`). Die Migration ist additiv. Der Test `SpacesDatabaseMigrationTest` prüft sie an einer von Hand angelegten Version-1-Datenbank.
- `POST /api/v1/media`: `422` (`location_not_removable`, mit `reason`), Einwilligungs-Header wie oben, `201` und `200` enthalten `locationKept`.
- Medienliste: `locationKept` je Eintrag.
- Details: ADR 0014, Abschnitt „API v1“.

## Ergebnis zu Festlegung 7

Die Tests prüfen mit ExifInterface (`getLatLong()`, Robolectric) und metadata-extractor (`GpsDirectory`, `getGeoLocation()`) an einer Datei mit vollständigem GPS-IFD und Thumbnail. Nach dem Bereinigen melden beide keinen Standort. Der Zeiger `GPSInfo` in IFD0 bleibt, und das leere GPS-IFD reicht. Eine zusätzliche Behandlung des Zeigers war deshalb nicht nötig. Das gilt für die getesteten Lesewege, nicht für alle denkbaren Leser.

## Prüfung und Gegenprobe

- **Eigener Detektor**: derselbe Walker läuft ohne Änderung erneut. Das deckt Strukturfehler nicht auf, die der Walker selbst übersieht. Dafür ist der Byte-Scan da.
- **Byte-Scan**: streamend über die ganze Datei, 64-KiB-Fenster mit 256 Bytes Überlappung. Das Lesen ist teuer bei großen Videos. Es ist bewusst Teil der Prüfung.
- **Unabhängige Leser**: ExifInterface und metadata-extractor in den JVM-Tests (`LocationScrubberTest`).
- **ExifTool und ffmpeg** (`ExternalToolCrossCheckTest`): nur in der CI. Der Runner installiert beide per apt, lokal fehlen sie und der Test wird übersprungen. Mit `CI=true` schlägt der Test bei fehlenden Werkzeugen fehl. Er erzeugt ein MP4 mit ffmpeg (`-metadata location=…`, `com.apple.quicktime.location.ISO6709`), prüft mit ExifTool, dass Standort drin ist, bereinigt und prüft erneut mit ExifTool.
- **Robustheit**: abgeschnittene und zufällig veränderte Eingaben (fest seeded) dürfen nie Standort durchlassen und nie eine Ausnahme aus dem Scrubber werfen. Unerwartete Laufzeitfehler im Walker gelten als `structure`.

## Restrisiken

- **Herstellerspezifische Trailer** (zum Beispiel Samsung SEF) landen bei der Einwilligung, nicht bei der automatischen Bereinigung.
- **Echte iPhone-Dateien** sind ungeprüft und zurückgestellt. Sie sind HEIC und fallen unter `unsupported`.
- **Echte Honor-Aufnahmen** folgen im Gerätetest.
- **MakerNote** wird geleert. Herstellerdaten, die für die Bildwiedergabe nötig wären, gehen dabei verloren.
- **Unbekannte Strukturen** werden abgelehnt, nicht durchgelassen. Das kann gültige, aber ungewöhnliche Dateien betreffen. Sie landen dann ebenfalls bei der Einwilligung.
- **Ein Standort im Bild selbst** (etwa ein Straßenschild) bleibt. Dieses ADR behandelt nur Metadaten.

## Abweichungen und offene Punkte

1. **HEIC, HEIF und AVIF nicht umgesetzt.** Der Auftrag verlangt Exif-Items über `iinf`, `iloc` (Konstruktionsmethoden 0 und 1, mehrere Extents), XMP-mime-Items und `unsupported` nur für Konstruktionsmethode 2. Dieser Walker fehlt. Die Formate werden deshalb mit `unsupported` abgelehnt. Mit Einwilligung kommen sie unverändert, markiert durch. Ohne Einwilligung braucht jede HEIC-Datei eine Bestätigung. Das betrifft viele iPhone-Aufnahmen und ist die wichtigste offene Lücke.
2. **Quellen nicht erneut gelesen.** Die Fundorte stammen aus der Recherche vom 19.10.2026. In dieser Umsetzung wurden die Quellen nicht erneut abgerufen. Die Tests prüfen die Verfahren nur auf synthetischen Dateien, die ich selbst nach diesem Stand gebaut habe.
3. **Echte Testdateien** mit Standort (Ordner außerhalb des Repos) sind in diesem Paket nicht verwendet worden.
4. **Ein Fehler im Scrubber bricht nie mit einer Ausnahme ab**, sondern wird als `structure` gemeldet. Das schützt, verdeckt aber Fehler. Fehlschläge in den Tests müssen deshalb als Fehler der Umsetzung gelesen werden.

## Quellen

Quellenstand laut Recherche vom 19.10.2026, nicht in dieser Umsetzung erneut gelesen:

- ExifTool, Tag-Tabellen: TagNames GPS, EXIF, QuickTime, XMP, https://exiftool.org/TagNames/
- W3C, PNG Specification, Third Edition (PNG-3): https://www.w3.org/TR/png-3/
- Google, WebP RIFF-Container: https://developers.google.com/speed/webp/docs/riff_container
- digiKam, `dmetadata_libheif.cpp` (Exif-Item-Offset, Lesen von HEIF-Metadaten)
- drewnoakes, metadata-extractor (Apache-2.0): https://github.com/drewnoakes/metadata-extractor
- Adobe, XMP Specification Part 3 (Speicherung in Dateien, GIF-Extension): https://developer.adobe.com/xmp/docs/XMPSpecifications/
- Skia, MPF-Lesen (Quelltext aus der Recherche, nicht erneut geprüft): Motion-Photo- und Multi-Picture-Struktur
- AndroidX Media3, `Mp4LocationData`: Standort in MP4-Metadaten
- CIPA DC-007, Multi-Picture Format (MPF)

Lizenz der Test-Abhängigkeit metadata-extractor: Apache-2.0. Sie ist nur `testImplementation`, keine Laufzeitabhängigkeit.
