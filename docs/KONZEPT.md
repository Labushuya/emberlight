# emberlight: Konzept und Entscheidungen

Stand: 16.10.2026, nach Meilenstein 1 (Grundgerüst). Dieses Dokument hält fest, was entschieden ist. Offene Punkte stehen am Ende.

## Idee

Eine Android-App aus zwei Teilen:

1. **Galerie**: So schön und modern wie möglich. Kein Wettbewerb mit anderen Gallery-Apps, sondern der Anspruch, dass sie sich hochwertig anfühlt.
2. **Sharing-Space (Kern der App)**: Ein Nutzer öffnet einen Space, andere vor Ort treten per QR-Code bei und tauschen Fotos und Videos aus.

## Feste Entscheidungen

### Nur lokal, nur bei Anwesenheit

- Austausch findet ausschließlich im lokalen Netz statt. Wer teilen will, muss vor Ort sein.
- **Kein Server, kein Relais, keine Cloud, keine Konten.** Es gibt keinen Dienst, den Labushuya betreibt.
- Jeder Nutzer kann selbst Host sein und einen Space öffnen.

### Zwei Netzwerk-Wege, für den Nutzer gleich

1. **Vorhandenes WLAN**: Sind alle im selben WLAN (zu Hause, Location), läuft der Space darin. Auffindbar per mDNS/NSD und über die URL im QR-Code.
2. **Eigenes WLAN der App**: Gibt es kein gemeinsames WLAN, spannt die App per `WifiManager.startLocalOnlyHotspot()` ein eigenes auf.
   - Hat nichts mit dem Tethering-Hotspot aus den Einstellungen zu tun, mobile Daten werden nicht geteilt.
   - SSID und Passwort vergibt Android zufällig, sie sind nicht anpassbar. Der QR-Code enthält sie im Format `WIFI:T:WPA;S:…;P:…;;`.
   - Gäste haben, solange sie verbunden sind, kein Internet. Manche Geräte melden „Kein Internet“ und wechseln zurück. Die Gäste-Seite muss erklären, dass man verbunden bleiben soll.
   - Viele Host-Geräte verlieren währenddessen ihr eigenes WLAN. Abhängig vom Chip.
   - Berechtigung `NEARBY_WIFI_DEVICES` ab Android 13, darunter Standort.

Für den Nutzer: Space öffnen → QR-Code zeigen → fertig. Die App wählt den Weg, der Nutzer kann ihn überschreiben.

### Verschlüsselung

- Standard: In fremden, geteilten WLANs nutzt die App ihr eigenes WLAN (LocalOnlyHotspot, WPA2). Dort ist der Verkehr verschlüsselt.
- App-zu-App-Verbindungen sind immer verschlüsselt, mit Zertifikats-Pinning über den QR-Code.
- Im eigenen bzw. vertrauten WLAN ist für Browser-Gäste HTTP mit Zugangsschlüssel möglich. Ohne Cloud gibt es für Browser-Gäste keine Verschlüsselung ohne Browser-Warnung, da nur selbstsignierte Zertifikate möglich sind.
- Der Nutzer kann den Weg überschreiben. Die App weist dann auf das Risiko hin.

### Gäste ohne App

- Die App betreibt einen eingebetteten HTTP-Server. Gäste scannen den QR-Code, wählen einen Anzeigenamen und landen auf einer Webseite, auf der sie hochladen, ansehen und herunterladen können.
- Die Gäste-Seite zeigt alle Medien mit Uploader und Zeitpunkt. Herunterladen geschieht über den Download des Browsers; so gelangen Medien für Gäste auf das Gerät (siehe Trennung unter Spaces).
- Hinweis für iPhone-Gäste: Browser-Downloads landen dort zuerst in der App „Dateien“, nicht direkt in der Fotos-App. Die Gäste-Seite soll das erklären.
- Die Gäste-Seite übernimmt die Gestaltung des Spaces (Name, Titelbild, Farbe).
- Nach Ablauf ist die Gäste-Seite nicht mehr erreichbar. Browser-Gäste kommen danach technisch an nichts mehr (siehe Ende und Nachhalten).
- Nutzer mit emberlight treten dem Space nativ bei (QR-Scan in der App).

### Spaces

Eigenschaften eines Space: Name, Titelbild, Akzentfarbe, Laufzeit/Ablaufzeitpunkt, Nachhaltefrist, Speicherlimit.

#### Teilnahme und Anzeige

- Teilnahme nur mit Einladung: QR-Code bzw. Zugangsschlüssel.
- Beim Beitritt wählt jeder Teilnehmer, auch Browser-Gäste, einen Anzeigenamen. Er ist eindeutig pro Space. Bei App-Nutzern ist er aus den App-Einstellungen vorbelegt.
- Der Gastgeber ist als solcher gekennzeichnet.
- Bekannte Grenze: Ohne Konten sind die Namen selbst gewählt und nicht geprüft. Wer einen fremden Namen wählt, ist nicht erkennbar.
- Der Space zeigt alle Medien mit Uploader (Anzeigename) und Zeitpunkt.

#### Rechte

- Während der Laufzeit darf jeder Teilnehmer alle Medien sehen und herunterladen sowie eigene hochladen.
- Die bisherigen Schalter „Hochladen/Herunterladen ja/nein“ pro Space entfallen.
- Teilnehmer dürfen eigene Uploads zurückziehen. Der Gastgeber darf beliebige Medien entfernen.
- Feinere Rechte und Moderation bleiben „später“.

#### Trennung von Space und Galerie (harte Grenze)

- Space-Medien liegen ausschließlich im app-internen Space-Speicher des Gastgebers: nicht im MediaStore, für andere Apps und Galerien unsichtbar.
- Die persönliche Galerie ist für Server und Teilnehmer nie erreichbar.
- Was der Gastgeber in den Space teilt, wird als isolierte Kopie in den Space-Speicher übernommen und mit dem Space-Speicher gelöscht.
- In eine Galerie gelangt nur, was ein Teilnehmer ausdrücklich auswählt und herunterlädt: in der App ins Album `Pictures/Emberlight/<Space>`, im Browser über den Download des Browsers.
- Eigene Uploads werden als „von dir“ markiert und nicht erneut heruntergeladen.
- Identische Dateien (Prüfsumme SHA-256) werden nur einmal gespeichert.

#### Schutz gegen Manipulation

Grundsätze; Details folgen als ADR in Meilenstein 3.

- Nur Bilder und Videos. Geprüft wird der Dateiinhalt, nicht die Endung.
- Anfragen enthalten nur IDs, keine Pfade.
- Kein Überschreiben fremder Dateien.
- Größenlimits.
- Zugangsschlüssel pro Teilnehmer. Mit dem Space werden sie ungültig.
- GPS-Standortdaten werden beim Hochladen in einen Space standardmäßig entfernt (EXIF-Ortsdaten). Das ist pro Upload oder in den Einstellungen abschaltbar. Herstellerextras am Dateiende (etwa Trailer nach dem Bild- oder Videoteil) werden beim Teilen entfernt.
- Lässt sich der Standort nicht sicher entfernen (etwa bei HEIC, bei Sonderformaten oder bei Metadaten-Tracks), lehnt der Upload ab. Die Gästeseite fragt dann: „Standort kann nicht entfernt werden. Trotzdem mit Standort hochladen?“ Bei Ja wird die Datei unverändert gespeichert und in der Liste als „Standort enthalten“ markiert. Entscheidung der Nutzerin bzw. des Nutzers vom 19.10.2026, Umsetzung in [ADR 0015](adr/0015-entfernung-von-standortdaten.md).

#### Speicher

- Der Gastgeber legt ein Limit für den Space-Speicher fest.
- Ab 80 % erscheint eine Warnung.
- Bei Erreichen werden weitere Uploads mit klarer Meldung an die Teilnehmer abgelehnt.
- Zusätzlich gibt es einen Mindest-Restspeicher des Geräts, der nie unterschritten wird.

#### Ende und Nachhalten

- Bei Ablauf oder vorzeitigem Beenden durch den Gastgeber schließt der Server sofort. Alle Zugangsschlüssel werden ungültig.
- Die Space-Medien bleiben für die gewählte Nachhaltefrist im Space-Speicher: sofort löschen, 1 Tag, 1 Woche oder eigene Angabe. Die Frist wird beim Anlegen festgelegt und ist jederzeit änderbar (verlängern oder verkürzen).
- Das Nachhalten gilt nur beim Gastgeber. Teilnehmer laden während der Laufzeit selbst herunter, was sie behalten wollen. Browser-Gäste kommen nach Ablauf technisch an nichts mehr.
- In der Nachhaltezeit wählt der Gastgeber aus, was in die eigene Galerie übernommen wird. Vorauswahl: nichts. Schaltflächen „Alle auswählen“ und „Nichts auswählen“.
- Vor Fristende kommt eine Erinnerung, damit der Gastgeber mitbekommt, dass das Nachhalten ausläuft und er handeln sollte. Die Erinnerung ist eine Benachrichtigung mit den Aktionen „Ansehen / Verlängern / Jetzt löschen“. Zusätzlich erscheint ein Hinweis-Punkt am App-Symbol und ein Hinweis beim Öffnen der App. Android-„Bubbles“ werden nicht genutzt.
- Reagiert niemand auf die Erinnerung, wird zum Fristende trotzdem automatisch gelöscht.

#### Gastgeber-Handy als Verteiler

- Ist das Gastgeber-Handy aus, leer oder außer Reichweite, ist der Space für alle nicht erreichbar.
- Schon Heruntergeladenes bleibt bei den Teilnehmern.

#### Betrieb

- Der Host-Dienst darf bei langen Events (Hochzeit, ganzer Abend) nicht nach einigen Stunden sterben. Foreground-Service-Typ so wählen, dass kein Zeitlimit greift (`dataSync` hat ab Android 15 ein 6-Stunden-Limit pro Tag, daher vermeiden).
- Hintergrundbetrieb könnte auf manchen Herstellern zusätzliche Freigaben verlangen. Beobachtung auf einem Gerät (Honor Magic V2, Android 16): Mit Akku-Ausnahme allein blieben die Anfragen im Akkubetrieb aus, mit zusätzlicher Hintergrund-Freigabe („App-Start“) kamen sie durch. Das ist eine Beobachtung und keine gesicherte Ursache. Sie beruht auf einem Gerät mit je einem Lauf, Einzelheiten und Einschränkungen stehen in [ADR 0012](adr/0012-lokaler-server.md). Der Dienst erkennt Pausen durch das System und zeigt dann einen Hinweis mit Weg zur Einstellung. Die Einrichtung folgt in Paket 3.3. Siehe [ADR 0012](adr/0012-lokaler-server.md).

### Verteilung

- **Sideloading über GitHub hat Priorität.** Play Store vorerst vernachlässigt, aber nichts verbauen, was einen späteren Umzug verhindert.
- Releases nach SemVer, gebaut mit GitHub Actions, Vorbild Ashfall / Ashfall-Releases:
  - Tag `vX.Y.Z` → signierte `emberlight-vX.Y.Z.apk` plus SHA-256-Prüfsumme in den Release-Notes und als eigene Datei.
  - Später ein öffentliches Repo `emberlight-releases` nur für Downloads, dieses Repo hier bleibt privat.
- Später: In-App-Updater, der neue Versionen im Releases-Repo findet und die Installation anstößt.

### Plattform und Sprache

- Android 8 (API 26) oder neuer.
- iOS vielleicht später oder gar nicht. Die Gäste-Webseite deckt iPhones von Anfang an ab.
- Texte Deutsch und Englisch.

## Nachträge aus Meilenstein 1

- **applicationId** `io.github.labushuya.emberlight`, entschieden am 15.10.2026 ([ADR 0004](adr/0004-versionierung-und-release.md)).
- **Schriften eingebettet**: Fraunces und Inter liegen im APK, weil Downloadable Fonts Google Play Services brauchen ([ADR 0003](adr/0003-theme-und-schriften.md)).
- **Probelauf vor dem Release**: Jede Version läuft zuerst als Probelauf per `workflow_dispatch`, ohne Release und Tag ([docs/RELEASE.md](RELEASE.md)).
- **Cloud-Backup deaktiviert**: Per Manifest (`allowBackup="false"`, `data_extraction_rules`) ist jede Sicherung in die Cloud ausgeschlossen.

## Nachträge aus Meilenstein 2

Details und Messwerte stehen in den ADRs 0006 bis 0011.

- **Galerie mit Reitern**: Fotos und Alben. Der Wechsel geschieht nur per Antippen, nicht per Wischen ([ADR 0009](adr/0009-alben-und-galerie-reiter.md)).
- **Zeitleiste**: Pinch-Zoom in Stufen, abhängig von der Kachelbreite. Die Schnell-Scrollleiste reagiert nur auf ihren Griff ([ADR 0008](adr/0008-zeitleiste.md)).
- **Vollbild als Overlay** über dem Raster, mit Hero-Animation. Wischen nach unten schließt. Wischen nach oben zeigt den Infobereich mit Aufnahmedaten, ohne Ortsdaten und ohne Berechtigung `ACCESS_MEDIA_LOCATION`. Gezoomt wird geschwenkt, und am Rand wird geblättert ([ADR 0010](adr/0010-vollbild-viewer.md)).
- **Kurzer Blick** über die Rückgeste: Halten zeigt die Position im Raster, Abbruch bleibt, Vollenden schließt. Die Semantik folgt Android. Der Befund vom Honor Magic V2 ist in ADR 0010 festgehalten ([ADR 0010](adr/0010-vollbild-viewer.md)).
- **Debug- und Benchmark-Varianten** haben eigene Icons. Eine Demo-Bibliothek mit synthetischen Medien gibt es nur in diesen Varianten, zum Testen auf dem Gerät. Der Release-Build enthält sie nicht, private Fotos sind nie Teil davon ([ADR 0011](adr/0011-demo-bibliothek-und-mess-build.md)).
- **Arbeitsregel**: Gesten und Animationen werden vor dem Merge auf einem Gerät geprüft.

## Nachträge aus Meilenstein 3

- **Voreinstellungen beim Anlegen** (Paket 3.2, [ADR 0016](adr/0016-space-verwaltung.md)): Farbe die erste der acht Akzentfarben, Laufzeit 1 Tag, Nachhaltefrist 1 Woche, Speicherlimit 4 GiB. Der Name ist Pflicht. Laufzeiten: 2 h, 6 h, 12 h, 1 Tag, 3 Tage, 1 Woche, Eigenes Ende (nur in der Zukunft, höchstens 30 Tage). Nachhaltefrist: Sofort löschen, 1 Tag, 1 Woche, Eigene (1 bis 90 Tage). Speicherlimit: 1, 2, 4, 8, 16 GiB, Eigenes (0,5 bis 64 GiB). Warnung, wenn das Limit den freien Speicher minus 2 GiB übersteigt.
- **Titelbild** über die System-Fotoauswahl. Das Bild wird in den Space-Speicher kopiert, mit Standort-Entfernung. Ein Bild, bei dem sich der Standort nicht entfernen lässt, wird abgewiesen. Es gibt keine Einwilligung für das Titelbild.
- **Laufzeit als Regel**: Eine Dauer bleibt Dauer, bis der Space in Paket 3.3 startet. Ein festes Ende ist ein Zeitpunkt.

## Offene Punkte

- Kompatibilität mit dem LocalSend-Protokoll: Interessant, aber kein MVP-Ziel.
- Moderne Galerie-Funktionen wie semantische Suche oder Gesichtsgruppierung, nur On-Device: nach dem MVP.
- Design-Richtung der App: Der Banner (`docs/banner.png`) gibt die Stimmung vor (dunkel, warme Glut, Funken), ist aber noch kein festes Design-System. Das Basis-Theme steht (ADR 0003), das Design-System entsteht ab Meilenstein 2.

## Änderungsnotiz

Änderungen am 16.10.2026: Space-Speicher getrennt von der Galerie, Rechte-Schalter entfallen, Nachhalten mit Frist.

17.10.2026: Fragen F–K entschieden. Nachhalten nur beim Gastgeber, Löschung auch ohne Reaktion, Erinnerung als Benachrichtigung mit Aktionen, GPS-Daten beim Upload standardmäßig entfernt, eigenes WLAN der App in fremden Netzen, Zurückziehen eigener Uploads und Entfernen durch den Gastgeber.

18.10.2026: Paket 3.0 abgeschlossen (Server-Basis, Foreground-Service, ADR 0012). Auf einem Honor-Gerät blieben mit Akku-Ausnahme allein die Anfragen im Akkubetrieb aus, mit zusätzlicher Hintergrund-Freigabe kamen sie durch (Beobachtung auf einem Gerät, eingetragen im Abschnitt Spaces, Betrieb).
