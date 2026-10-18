# Paketplan Meilenstein 3: Space im vorhandenen WLAN

Stand: 18.10.2026. Der Plan ist von der Nutzerin bzw. dem Nutzer bestätigt. Die Pakete folgen aufeinander. Jedes Paket endet mit einem Gerätetest bzw. einem Testkatalog.

Grundlage ist [CLAUDE.md](../CLAUDE.md), Meilenstein 3, und [KONZEPT.md](KONZEPT.md), Abschnitt Spaces.

## Pakete

| Paket | Inhalt |
|---|---|
| 3.0 ✅ | Server-Basis, Foreground-Service, ADR 0012 (#25) |
| 3.1 | Server-Härtung und Space-Speicher: nur WLAN, CIO-Grenzen, Upload- und Download-API, Inhaltsprüfung, Dedupe, Speicherlimit, GPS-Entfernung; ADR „Schutz gegen Manipulation“. Ohne UI. Lieferung in zwei PRs: **3.1a** (alles außer GPS) und **3.1b** (GPS-Entfernung). 3.1b muss vor dem ersten für Nutzer erreichbaren Upload (Paket 3.5) gemergt sein. Bis dahin ist der Server aus keiner Oberfläche erreichbar. Umfang 3.1a: ADR 0014, Abschnitt „Umfang 3.1a“. |
| 3.2 | Space anlegen und verwalten (Name, Titelbild, Farbe, Laufzeit, Nachhaltefrist, Speicherlimit; Liste und Detail) |
| 3.3 | Einrichtung und Betrieb: Laufzeitberechtigungen, Akku-Ausnahme, Hersteller-Freigabe (Honor zuerst), Ladegerät-Hinweis, Pausen-Erkennung, Start und Stopp in der App (Voraussetzung von 3.3, nicht von 3.1; siehe ADR 0012, Nachtrag), Start/Stopp-Abfolge über einen Kanal. Netzverlust: automatisches Fortsetzen, wenn dasselbe WLAN mit derselben IP zurückkommt; sonst Hinweis an den Gastgeber, weil sich der QR-Code ändert (ADR 0013) |
| 3.4 | Einladung und Beitritt: QR-Code, Zugangsschlüssel pro Teilnehmer, Anzeigename, Gastgeber-Kennzeichnung |
| 3.5 | Gäste-Webseite: Upload, Download, Uploader-Anzeige |
| 3.6 | Ende und Nachhalten: Ablauf erzwingen, Nachhaltefrist, Erinnerung mit Aktionen (Vorauswahl „nichts behalten“, Alles/Nichts auswählen), automatische Löschung |
| 3.7 | Gezielte Übernahme in `Pictures/Emberlight/<Space>`, danach Release `v0.3.0` |

## Festgelegt

- In M3 nehmen alle Teilnehmer über die Gäste-Webseite teil. App-zu-App-Beitritt kommt später, weil er Zertifikats-Pinning (siehe [KONZEPT.md](KONZEPT.md), Verschlüsselung) voraussetzt.
- Pro Paket gibt es am Ende einen Gerätetest bzw. einen Testkatalog.
- **GPS-Aufteilung** (18.10.2026): 3.1a und 3.1b bleiben getrennt. 3.1b muss vor dem ersten für Nutzer erreichbaren Upload (3.5) gemergt sein (ADR 0013, GPS).
- **Stopp in der App** ist Voraussetzung von 3.3 (Start aus der Oberfläche), nicht von 3.1 (ADR 0012, Nachtrag).
- **Netzverlust** (18.10.2026): 3.1a stoppt den Server bei Verlust oder Wechsel der WLAN-Schnittstelle bzw. ihrer Adresse. 3.3 setzt automatisch fort, wenn dasselbe WLAN mit derselben IP zurückkommt (ADR 0013, Netz).
- **`/health`** bleibt hinter der Zugriffsprüfung. Die Gäste-Webseite schickt den Token als Header mit (ADR 0014).
- **`SERVER_API_VERSION` bleibt 1.** Erhöhung erst bei inkompatiblen Änderungen nach dem ersten Release mit Routen (`v0.3.0`) (ADR 0014, Version).
- **Grenzwerte** (Dateigrößen, Speicherlimit, Restspeicher, Parallelität, Timeouts) sind vorgeschlagen und noch nicht von der Nutzerin bzw. dem Nutzer bestätigt (ADR 0013).
- **Engine** (CIO oder Netty) ist offen, bis die Messung in ADR 0013 vorliegt und entschieden ist.

## Entscheidungsdokumente

- Paket 3.1: [ADR 0013](adr/0013-schutz-gegen-manipulation.md) (Schutz gegen Manipulation, Entwurf) und [ADR 0014](adr/0014-space-speicher-und-server-api.md) (Space-Speicher und Server-API, Entwurf).
- Die Entwürfe sind vor der Implementierung von 3.1a zu prüfen. Offene Entscheidungen stehen am Ende der jeweiligen ADR.

## Testkatalog pro Paket

- **3.1a**: Die Pflicht-Tests aus [ADR 0012](adr/0012-lokaler-server.md) (Abschnitt „Pflicht vor Paket 3.1“) und die Tests aus ADR 0014 (Abschnitt „Teststrategie für 3.1a“). Dazu ein Gerätetest mit dem Debug-Einstieg aus ADR 0014.
- **3.1b**: Tests der GPS-Entfernung je Format mit echten Beispieldateien, dazu Prüfung mit einem Fremdprogramm (z. B. ExifTool). Gerätetest mit Fotos und Videos aus der Galerie.
- **3.2 bis 3.7**: Der Testkatalog wird zu Beginn des jeweiligen Pakets im Entwurf festgelegt.
