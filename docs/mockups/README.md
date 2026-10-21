# Design-Mockups Meilenstein 3 (Spaces)

Diese Mockups sind ein **Entwurf und nicht bindend**. Sie zeigen, wie die Bildschirme aussehen sollen. Verbindlich ist, was in der App umgesetzt und in `strings.xml` festgehalten ist. Die Festlegungen stehen in [PLAN-M3.md](../PLAN-M3.md) und in [ADR 0016](../adr/0016-space-verwaltung.md). Wo ein Mockup und die Umsetzung auseinanderlaufen, gilt die Umsetzung. Die Mockups werden nicht automatisch geprüft.

## Dateien

| Datei | Größe | Inhalt |
|---|---|---|
| `emberlight-mockups.html` | ca. 99 KB | Alle Bildschirme als HTML und SVG, ohne Build-Schritt. Öffnen im Browser. |
| `screenshot-android.png` | ca. 859 KB | Übersicht der Android-Bildschirme |
| `screenshot-gaeste-desktop.png` | ca. 296 KB | Gäste-Webseite auf dem Desktop |
| `screenshot-gaeste-mobil.png` | ca. 614 KB | Gäste-Webseite auf dem Telefon |

Die Screenshots liegen unter 1,5 MB und sind unverändert übernommen.

**Schriften:** Die HTML-Datei lädt Fraunces und Inter aus `core/design/src/main/res/font/`. Es gibt hier keine Kopie der Schriftdateien, damit keine Duplikate entstehen. Die Quellen liegen in `emberlight-builds/mockups/` außerhalb des Repos. Dort liegen die Originale, die Schriften sind byte-gleich.

## Abschnitte und Paketzuordnung

Die Abschnitte stehen in der Reihenfolge der HTML-Datei.

**Android-App**

| Abschnitt | Paket |
|---|---|
| 1 Navigation mit Galerie und Spaces (zwei Reiter) | 3.2 |
| 2 Space anlegen (Formular, Würfel, Aufbewahrung, Speicherlimit) | 3.2 |
| Foldable: Innendisplay (Liste und Detail nebeneinander) | 3.2 |
| 3 Einrichtung | 3.3 |
| 4 Laufender Space (Start und Stopp) | 3.3 |
| 4b Space beenden: Bestätigung | 3.3 |
| 5 Pausiert | 3.3 |
| 6 Beendet und Aufbewahrung (Nachhalten) | 3.6, Übernahme 3.7 |
| 7 Erinnerung | 3.6 |
| 8 Über und Lizenzen | 3.7 |

**Gäste-Webseite**

| Abschnitt | Paket |
|---|---|
| W1 Beitritt (mit dem Hinweis aus (d), umgesetzt in 3.4; ohne Farbe und Titelbild vor dem Beitritt, siehe [ADR 0018](../adr/0018-einladung-und-beitritt.md)) | 3.4 |
| W2 Hauptseite | 3.5 |
| W3 Upload mit Standort-Rückfrage (zusätzlich Paket 3.1b) | 3.5 |
| W4 Detail | 3.5 |
| W5 Beendet | 3.6 |
| Desktop: Hauptseite | 3.5 |

## Designentscheidungen

Festgehalten am 20.10.2026. Die Umsetzung steht im angegebenen Paket.

- **(a)** „Jetzt löschen“ in der Erinnerung nur mit Bestätigung. Paket 3.6.
- **(b)** Die acht Akzentfarben, wie umgesetzt. Paket 3.2, `core:design`.
- **(c)** Speicherlimit-Optionen über der Obergrenze des Geräts sind ausgegraut, mit dem Hinweis „Nicht genug freier Speicher, max. X GiB“. Umgesetzt in Paket 3.2.
- **(d)** Gäste-Beitrittsseite mit dem Hinweis „Verbindung läuft direkt im WLAN zum Handy des Gastgebers, nicht über das Internet. Verschlüsselung folgt in einer späteren Version.“ Paket 3.4 und 3.5.
- **(e)** Zurückziehen eigener Uploads mit kurzer Bestätigung. Standort-Rückfrage mit den Knöpfen „Mit Standort hochladen“ bzw. „Nicht hochladen“. Paket 3.5.
- **(f)** Bei „Sofort löschen“ kommt die Erinnerung kurz vor dem Ende. Paket 3.6.

## Entscheidungen vom 20.10.2026 (Paket 3.2)

1. **Namensvorschläge:** Ein Würfel neben dem Namensfeld schlägt einen Namen aus Adjektiv und Substantiv vor, etwa „Tanzende Waschbären“ oder „Dancing Raccoons“. Die Wortlisten liegen je Sprache in `strings.xml`.
2. **Speicherlimit mit harter Grenze:** Die Obergrenze ist der freie Speicher des Geräts minus 2 GiB, abgerundet auf halbe GiB. Ein eigener Wert darüber sperrt das Speichern. Die Prüfung läuft beim Speichern erneut. Für Paket 3.3 gilt: Prüfung beim Start, das Limit ist keine Reservierung.
3. **Feldbezeichnung:** „Nachhaltefrist“ heißt in der Oberfläche „Aufbewahrung nach dem Ende“ (en: „Keep after the end“), mit Erklärtext.
4. **Datum und Uhrzeit:** Das feste Ende wird über Auswahldialoge von Material 3 gewählt, nicht mit Textfeldern.

## Schriften und Lizenzen

- **Fraunces** und **Inter** stehen unter der SIL Open Font License 1.1. Die Lizenztexte mit den Copyright-Zeilen liegen in `core/design/src/main/assets/licenses/` (`fraunces_OFL.txt`, `inter_OFL.txt`). Paket 3.7 nimmt sie in die „Über und Lizenzen“-Seite auf.
- **Icons** stammen aus Material Symbols (Apache-2.0), soweit nicht anders vermerkt. Der Würfel im Formular ist selbst gezeichnet.
- Die Telefonrahmen in den Mockups sind Zeichnungen, keine Gerätefotos.
