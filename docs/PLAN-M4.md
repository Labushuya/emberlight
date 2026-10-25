# Paketplan Meilenstein 4: Netz, Hotspot und Gast-Modus

**Vorläufig. Wird nach dem Prototyp 4.0 angepasst.** Die Pakete 4.1 bis 4.10 sind Vorschläge. Sie werden mit dem Messbericht aus Paket 4.0 überarbeitet. Bis dahin gilt im Konzept nichts als „unterstützt“ (KONZEPT-M4.md, Abschnitt 13).

Stand: 25.10.2026.

Grundlage ist [KONZEPT-M4.md](KONZEPT-M4.md). Dort stehen die Nutzervorgaben vom 25.10.2026 und die Messkriterien (Abschnitt 13). Für den Stand von Meilenstein 3 gilt [PLAN-M3.md](PLAN-M3.md), Abschnitt „Abschluss Meilenstein 3“. Die Grundlage im Projekt ist [CLAUDE.md](../CLAUDE.md), Meilenstein 4.

## Pakete

| Paket | Inhalt | Abhängig von |
|---|---|---|
| 4.0 Netlab-Prototyp und Messung | Prototyp in `tools/netlab`, Messung M1 bis M9, Messbericht | keine |
| 4.1 QR-Format v2 und Einladung offline | Neues Format mit WLAN-Zugang, Fingerprint, Einladungsgeheimnis, signierte Vorschau | 4.0 |
| 4.2 Hotspot-Modus beim Gastgeber | Lebenszyklus, Server-Bindung an das Hotspot-Interface, Neustart, Teilnehmergrenze | 4.0 |
| 4.3 Verschlüsselung App zu App | TLS mit gepinntem Zertifikat oder gleichwertig | 4.0, 4.1 |
| 4.4 Gast-Modus, Beitritt | Scan, Einladung, Verbindung, Hintergrund-Check, Name | 4.1, 4.2, 4.3 |
| 4.5 Gast-Modus, Space | Upload mit Standort-Entfernung auf dem Gast-Gerät, Ansehen, Ende, Verlassen, Übernahme | 4.4 |
| 4.6 Geräte-Check, Auswege, Diagnose | Verbindungstest, VPN, WLAN-Modus, Captive Portal | 4.0, 4.2 |
| 4.7 Browser-Fallback Stufe 3 | Zweiter QR-Code, Feature-Check, `:focus`-Rückfall, Hinweise | 4.0, 4.1 |
| 4.8 Lokales Profil, „Neue Gäste bestätigen“ | Name, Bild, Schlüssel, bekannte Gäste, Vertraute, Sperrliste | 4.1, 4.4 |
| 4.9 Profilumzug per Transfer-Space | Nachfolge-Erklärung, Sperre des alten Profils, Notfall-Widerruf | 4.3, 4.8 |
| 4.10 Releases `v0.4.0` | alpha, rc, final, Endtest durch den Nutzer | 4.0 bis 4.9 |

## Pakete im Einzelnen

### 4.0 Netlab-Prototyp und Messung

- **Ziel:** Die Messpunkte M1 bis M9 (KONZEPT-M4, Abschnitt 13) sind auf den Geräten des Nutzers gemessen. Die Befunde ersetzen die erwarteten Angaben im Konzept.
- **Inhalt:**
  - Eigenes Debug-Modul bzw. eigene App `tools/netlab` mit eigener App-ID. Der Release-Build enthält sie nicht.
  - Gastgeber: `startLocalOnlyHotspot()`, Anzeige der Fähigkeiten (STA+AP-Concurrency, Concurrency für lokale Verbindungen, Stufe), kleiner Testserver.
  - Gast: QR-Scan, `WifiNetworkSpecifier` mit Netz-Bindung, Ping, Upload-Durchsatz, Status der anderen Netze (mobile Daten, WLAN, VPN).
  - Ungebundener Test, der das Verhalten eines Browser-Gastes simuliert.
  - Browser-Testseite mit Zählen der Gäste.
  - Bericht als Text.
  - Messung M1 bis M9 mit dem Nutzer auf seinen Geräten.
- **Abhängigkeiten:** keine. Voraussetzung für alle weiteren Pakete.
- **Akzeptanz:**
  - Messbericht `docs/MESSUNG-M4.md` mit je Gerät: Modell, Android-Version, Ergebnis je Messpunkt gegen die Kriterien aus Abschnitt 13.
  - Die Schwellen aus Abschnitt 13 sind vom Nutzer bestätigt, bevor sie als Maßstab gelten.
  - Dieser Plan ist nach der Messung angepasst (Gästezahl, Browser-Grenze, Verschlüsselungsverfahren, Stufen).

### 4.1 QR-Format v2 und Einladung offline

- **Ziel:** Der Gast sieht die Einladung ohne Netz und stimmt zu, bevor das WLAN wechselt (KONZEPT-M4, Abschnitt 9, Schritt 2).
- **Inhalt:**
  - Neues QR-Format mit WLAN-Zugang, Fingerprint des Gastgebers, Einladungsgeheimnis, Space-Name, Gastgeber und Ende.
  - Signierte Vorschau, damit eine Veränderung auffällt.
  - Versionierung. Das neue Format löst `#i=` aus M3 ab bzw. ergänzt es (KONZEPT-M4, Abschnitt 1, Nr. 6 und 7).
  - Die Browser-Gäste bekommen den zweiten QR-Code (Paket 4.7).
- **Abhängigkeiten:** 4.0 (Format und Daten des Hotspots aus der Messung).
- **Akzeptanz:**
  - Die Einladung ist ohne Netz anzeigbar.
  - Ein verändertes Feld wird erkannt und abgelehnt (Test).
  - Die Versionierung ist festgelegt. Ein alter `#i=`-Link funktioniert weiter oder wird klar abgelehnt (Entscheidung des Nutzers).
  - Die Adresse des QR-Links bei fehlender App ist geklärt (KONZEPT-M4, Abschnitt 9).

### 4.2 Hotspot-Modus beim Gastgeber

- **Ziel:** Der Server läuft zuverlässig im eigenen Hotspot.
- **Inhalt:**
  - Lebenszyklus des Hotspots im Foreground-Service: Start, Stopp, Fehler, Neustart.
  - Bindung des Servers an die Adresse des Hotspot-Interfaces (ADR 0013, „Bindung und Subnetz“).
  - Verhalten nach Prozess-Tod und Neustart (ADR 0017, Nachbesserung 3.3b).
  - Teilnehmergrenze je Gerät. Sie wird aus M4 gemessen und angezeigt, sobald die App sie erkennt. Es gibt keine feste Zahl im Code.
  - Zufällige Subnetze ab Android 11 (zu prüfen).
- **Abhängigkeiten:** 4.0 (M1, M4, M8, M9), ADR 0017.
- **Akzeptanz:**
  - Hotspot 30 Minuten ohne Abbruch, mobile Daten parallel aktiv (M1).
  - Zwei Stunden ohne Pause über 2 Minuten, mit Akku-Ausnahme (M8).
  - Beim Ende sieht der Gast den Verbindungsverlust mit Erklärung (M9).

### 4.3 Verschlüsselung App zu App

- **Ziel:** Der Verkehr zwischen zwei emberlight-Apps ist verschlüsselt, auch im Hotspot (KONZEPT-M4, Abschnitt 10).
- **Inhalt:**
  - Verfahren festlegen: TLS mit gepinntem Zertifikat (Fingerprint aus dem QR-Code) oder gleichwertig. Der Vergleich findet im Prototyp 4.0 statt.
  - Umsetzung mit Ktor CIO. Prüfen, dass R8 durchläuft (optionale TLS-Provider fehlen, ADR 0013).
  - Browser-Gäste bleiben bei HTTP mit Hinweis.
- **Abhängigkeiten:** 4.0 (Vergleich), 4.1 (Fingerprint im QR-Code).
- **Akzeptanz:**
  - Ein falsches Zertifikat wird abgelehnt (Test).
  - Der Release-Build (R8) läuft durch.
  - Ktor CIO mit selbstsigniertem Zertifikat auf Android 8 bis 17 gemessen (M6).

### 4.4 Gast-Modus, Beitritt

- **Ziel:** Ein Gast mit App tritt einem Space bei (KONZEPT-M4, Abschnitt 9, Schritte 1 bis 5).
- **Inhalt:**
  - QR-Scan (CameraX und ZXing).
  - Einladung anzeigen, dann „Annehmen“.
  - Verbindung über `WifiNetworkSpecifier` mit Netz-Bindung (Stufe 1). Stufe 2 (Android 8 und 9): Verbindung von Hand, mit Anleitung.
  - Hintergrund-Check: Erreichbarkeit, Echtheit über den Fingerprint, Space offen, Platz frei.
  - Name aus dem Profil vorbefüllt, bei Kollision „Anna 2“.
  - Fehler mit Diagnose-Hinweis (Paket 4.6).
- **Abhängigkeiten:** 4.1, 4.2, 4.3.
- **Akzeptanz:**
  - Verbindungsaufbau in höchstens 30 Sekunden, in 9 von 10 Versuchen (M2).
  - Mobile Daten für fremde Apps aktiv (M2).
  - Ein fehlgeschlagener Echtheitscheck bricht den Beitritt ab (Test).

### 4.5 Gast-Modus, Space

- **Ziel:** Der Gast nutzt den Space in der App. Seine Medien bleiben getrennt von seiner Galerie.
- **Inhalt:**
  - Upload mit Standort-Entfernung bereits auf dem Gast-Gerät (KONZEPT-M4, Abschnitt 9, Verbesserung).
  - Ansehen, Ende (Ende-Ansicht wie im Browser), Verlassen.
  - Übernahme in die eigene Galerie nach dem Muster von Paket 3.7 (`Pictures/Emberlight/<Space>`).
  - Hintergrund-Upload (Stufe 1).
- **Abhängigkeiten:** 4.4, 3.5b und 3.5c (Upload-Regeln), 3.7 (Übernahme).
- **Akzeptanz:**
  - Der Standort ist beim Gast-Upload entfernt (Test mit Beispieldateien, ADR 0015).
  - Eine Galerie erhält nur, was der Gast ausdrücklich übernimmt.
  - Die Trennung gilt auf dem Gast-Gerät ebenso (Isolationsprüfungen).
  - Das Verhalten beim Verlassen ist festgelegt (zu klären).

### 4.6 Geräte-Check, Auswege und Diagnose

- **Ziel:** Der Nutzer weiß vor dem Start, was mit seinem Gerät möglich ist, und bekommt Auswege angeboten (KONZEPT-M4, Abschnitte 6 bis 8).
- **Inhalt:**
  - Prüfungen aus KONZEPT-M4, Abschnitt 6.1: Hotspot, STA+AP-Concurrency, Concurrency für lokale Verbindungen, mobile Daten, VPN, Berechtigungen, Stufe.
  - Auswege aus Abschnitt 6.2.
  - Verbindungstest im WLAN-Modus mit den Kategorien aus Abschnitt 8: Isolation vermutet, VPN aktiv, Captive Portal, anderes Netz, nur IPv6.
  - VPN-Hinweise nach den Messungen M5 (Abschnitt 7).
- **Abhängigkeiten:** 4.0 (M5, M6), 4.2.
- **Akzeptanz:**
  - Jede Prüfung liefert einen Befund oder den Zustand „unbekannt“.
  - Der Verbindungstest unterscheidet die Kategorien (Test mit Fälschungen).
  - Ergebnis M5 je App im Messbericht.
  - Alle Texte in `strings.xml` (de und en).

### 4.7 Browser-Fallback Stufe 3

- **Ziel:** Gäste ohne App kommen über zwei QR-Codes in den Space (KONZEPT-M4, Abschnitt 4, Stufe 3).
- **Inhalt:**
  - Zweiter QR-Code für den Space nach dem WLAN-QR-Code.
  - Feature-Check nach KONZEPT-M4, Abschnitt 5, ohne User-Agent.
  - Rückfallregel `:focus` bei fehlendem `:focus-visible`. Sie senkt die Grenze auf Safari 15.0 (Hinweis in Abschnitt 5). Entscheidung über die Zeigeweise des Fokus bei Mausklicks ist in diesem Paket zu treffen.
  - Hinweis „Browser zu alt“ für Stufe 4.
  - Hinweise „Verbunden bleiben“ und „kein Internet“.
- **Abhängigkeiten:** 4.0 (M3, M7), 4.1 (QR-Format).
- **Akzeptanz:**
  - M3 auf Android und iPhone: Seite lädt, Upload und Download gelingen, der Hinweis erscheint.
  - M7 je Version (Chrome, Safari, Firefox): alle Funktionen gehen, der Fokusring ist sichtbar.
  - Die Browser-Tests in CI bleiben grün. Safari und Firefox sind mindestens am Gerät geprüft.

### 4.8 Lokales Profil und „Neue Gäste bestätigen“

- **Ziel:** Wiedererkennung ohne Online-Konto (KONZEPT-M4, Abschnitt 11).
- **Inhalt:**
  - Profil mit Name, Bild und Identitätsschlüssel (AndroidKeyStore, nicht exportierbar; Vorschlag, KONZEPT-M4 Abschnitt 11).
  - Bekannte Gäste, Vertraute (automatisch annehmen), Sperrliste (lokal).
  - Option „Neue Gäste bestätigen“, standardmäßig aus. Gäste in der Warteschlange haben einen eigenen Zustand (KONZEPT-M4, Abschnitt 9).
  - Profilbild ohne Ortsdaten.
- **Abhängigkeiten:** 4.1, 4.4.
- **Akzeptanz:**
  - Der Schlüssel ist nicht exportierbar (Prüfung).
  - Die Option ist standardmäßig aus.
  - Ein gesperrter Gast wird beim nächsten Beitritt abgewiesen (Test). Das Verhalten bei einer laufenden Verbindung ist zu klären.
  - Das Profilbild enthält keine Ortsdaten (Test).

### 4.9 Profilumzug per Transfer-Space

- **Ziel:** Profil und Daten wechseln auf ein eigenes neues Gerät. Das alte Profil wird gesperrt (Vorgabe vom 25.10.2026).
- **Inhalt:**
  - Transfer-Space, ausschließlich für Profil und Daten zwischen eigenen Geräten. Er läuft nicht über den Space-Speicher.
  - Das neue Gerät erzeugt einen neuen Schlüssel. Das alte signiert eine Nachfolge-Erklärung (alt zu neu).
  - Das alte Gerät sperrt sein Profil nach dem erfolgreichen Umzug. Die Daten löscht es nur auf Wunsch.
  - Notfall-Widerruf aus der Profilsicherung (Datei).
  - Hinweis in der Oberfläche zur Tragweite: ohne Server keine Fern-Sperre.
- **Abhängigkeiten:** 4.3 (verschlüsselter Transfer), 4.8 (Profil). Ob der Transfer über die Hotspot-Technik läuft, ist zu prüfen (KONZEPT-M4, Abschnitt 11).
- **Akzeptanz:**
  - Nach dem Umzug ist das alte Profil gesperrt. Die Daten bleiben, bis der Nutzer ihre Löschung wünscht (Test mit zwei Geräten).
  - Ein abgebrochener Umzug lässt das alte Profil aktiv (Test).
  - Der Notfall-Widerruf entsteht beim Anlegen des Profils und liegt in der Profilsicherung (Test).
  - Der Transfer-Space ist nach den Isolationsregeln von Space-Medien getrennt (Prüfung).

### 4.10 Releases `v0.4.0`

- **Ziel:** Das nächste reguläre Release nach Meilenstein 4.
- **Inhalt:**
  - Vorab-Stufen `v0.4.0-alpha.N`, `v0.4.0-rc.N`, dann `v0.4.0`.
  - Jede Vorab-Stufe mit Endtest durch den Nutzer anhand des Testkatalogs.
  - Probelauf per `workflow_dispatch` vor jedem Tag ([docs/RELEASE.md](RELEASE.md)).
  - Release-Notes aus den Conventional Commits.
- **Abhängigkeiten:** 4.0 bis 4.9.
- **Akzeptanz:**
  - Endtest durch den Nutzer je Vorab-Stufe.
  - Tag und Vorab-Tag nur mit Freigabe des Nutzers.
  - APK signiert, SHA-256 in den Notes.

## Arbeitsweise (übernommen aus PLAN-M3)

- Der Testkatalog wird je Paket fortgeschrieben, gegliedert nach Funktionen. Welche Datei dafür gilt, ist zu klären (siehe Vorschläge zu CLAUDE.md unten).
- Paket-PRs werden bei grüner CI „Build, Lint und Tests“ gemergt (Freigabe des Nutzers vom 21.10.2026).
- Vorab-Releases nur mit Freigabe des Nutzers, mit Endtest.
- Gerätetests und Messungen laufen mit dem Nutzer auf seinen Geräten (KONZEPT-M4, Abschnitt 13).

## Festgelegt

- **Netzweg:** Hotspot ist der Standard. Vorhandenes WLAN ist die Alternative für große Gruppen (Vorgabe, 25.10.2026).
- **Stufe 4** (Browser basic) ist vorerst nicht unterstützt (Entscheidung, 25.10.2026).
- **Profilumzug** über einen Transfer-Space. Das neue Gerät erzeugt einen neuen Schlüssel. Das alte Profil sperrt sich nach dem Umzug automatisch. Die Daten löscht es nur auf Wunsch. Beim Anlegen entsteht ein Notfall-Widerruf in der Profilsicherung (Vorgabe, 25.10.2026, „Alle Drei: ja“).
- **Tragweite:** Ohne Server keine Fern-Sperre. Das bleibt dokumentiert.
- **„Neue Gäste bestätigen“** ist standardmäßig aus (Vorgabe).
- **Release:** `v0.3.0-rc.1` ist die Testversion der Gastgeber-Seite. Das nächste reguläre Release ist `v0.4.0` (Paket 4.10).

## Offene Punkte aus KONZEPT-M4 und ihr Paket

| Offener Punkt (KONZEPT-M4, Abschnitt 14) | Paket |
|---|---|
| Gästezahl-Grenze je Netzweg | 4.0 (M4), 4.2 |
| Browser-Grenze final | 4.0 (M7), 4.7 |
| Verschlüsselungsverfahren | 4.0, 4.3 |
| Rückfallregel `:focus-visible` | 4.7 |
| NSD ja oder nein | 4.0 (zu entscheiden, ACCESS_LOCAL_NETWORK) |
| QR-Format und Versionierung | 4.1 |
| Adresse des QR-Links bei fehlender App | 4.1 |
| „Neue Gäste bestätigen“, Warteschlange | 4.8 |
| Transfer-Space und Trennung | 4.9 |
| `dataSync` des Export-Workers | siehe Befund unten |
| CLAUDE.md, „keine Konten“ und „jedes Android 8+“ | Vorschläge unten |
| iOS-App, App-Verteilung, README | später (Vorgabe) |

## Vorschlag zur Anpassung von CLAUDE.md (Entscheidung des Nutzers nötig)

CLAUDE.md wird in diesem Paket nicht geändert. Die Vorschläge sind offen.

1. **„Harte Grenzen“, „Keine Konten“:** präzisieren auf „kein Online-Konto“. Ein lokales Profil mit Identitätsschlüssel ist erlaubt (KONZEPT-M4, Nr. 8 und Abschnitt 11).
2. **Einleitung, „soll auf jedem Android 8+ laufen“:** präzisieren auf moderne Smartphones als Zielgruppe. Android 8 und 9 bekommen den Fallback Stufe 2. `minSdk 26` bleibt (KONZEPT-M4, Nr. 9).
3. **Meilensteine 4 bis 6:** Die Liste passt nicht mehr zum Konzept. „Beitritt per App“ (bisher M5) und „TLS im fremden WLAN“ (bisher M6) stehen in M4 (Pakete 4.3 und 4.4). Vorschlag: Liste nach PLAN-M4 aktualisieren. (Eigener Befund, nicht im Konzept genannt.)
4. **Struktur:** `tools/netlab` als Ort des Prototyps aufnehmen (Paket 4.0). Hinweis, dass NSD/mDNS in `share:network` offen ist (KONZEPT-M4, Nr. 4). (Eigener Befund.)
5. **Harte Grenze „Strikte Trennung von Space und Galerie“:** klarstellen, dass der Transfer-Space (Profil und Daten) nicht über den Space-Speicher läuft (KONZEPT-M4, Abschnitt 11, zu prüfen). (Eigener Befund.)
6. **Arbeitsweise, Testkatalog:** Der Dateiname `TESTKATALOG-M3.md` ist an M3 gebunden. Entscheidung nötig: M3-Datei weiterführen oder neue Datei `TESTKATALOG-M4.md`. (Eigener Befund.)

## Befund: `dataSync` im Export-Worker

- Der Export-Worker (Übernahme in die Galerie, Paket 3.7) läuft als WorkManager-Vordergrundauftrag mit dem Typ `dataSync`. Belegt: `feature/space/src/main/AndroidManifest.xml:37` (`SystemForegroundService`), `SpaceExportWorker.kt:174` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`), ADR 0022.
- Der Server nutzt `connectedDevice`, weil `dataSync` ab Android 15 ein Limit von 6 Stunden pro Tag hat (ADR 0012, KONZEPT.md Abschnitt Betrieb).
- KONZEPT-M4, Nr. 12, sagt „Der Worker läuft nur kurz“. Das ist nicht belegt. Große Übernahmen können länger dauern.
- **Zu prüfen:** Dauer einer typischen und einer großen Übernahme, Wirkung des Limits ab Android 15 und die Alternative für Android 14 und neuer, User-Initiated Data Transfer Jobs. Die Alternative ist aus dem Auftrag übernommen und nicht geprüft. Für Android 8 bis 13 braucht es einen eigenen Weg.
- Entscheidung des Nutzers nötig, ob das in Paket 4.5 (Übernahme auf dem Gast-Gerät) oder in einem eigenen Paket behandelt wird.
