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

### Gäste ohne App

- Die App betreibt einen eingebetteten HTTP-Server. Gäste scannen den QR-Code, wählen einen Anzeigenamen und landen auf einer Webseite, auf der sie hochladen, ansehen und herunterladen können.
- Die Gäste-Seite zeigt alle Medien mit Uploader und Zeitpunkt. Herunterladen geschieht über den Download des Browsers; so gelangen Medien für Gäste auf das Gerät (siehe Trennung unter Spaces).
- Hinweis für iPhone-Gäste: Browser-Downloads landen dort zuerst in der App „Dateien“, nicht direkt in der Fotos-App. Die Gäste-Seite soll das erklären.
- Die Gäste-Seite übernimmt die Gestaltung des Spaces (Name, Titelbild, Farbe).
- Nach Ablauf ist die Gäste-Seite nicht mehr erreichbar (offen: F).
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

#### Speicher

- Der Gastgeber legt ein Limit für den Space-Speicher fest.
- Ab 80 % erscheint eine Warnung.
- Bei Erreichen werden weitere Uploads mit klarer Meldung an die Teilnehmer abgelehnt.
- Zusätzlich gibt es einen Mindest-Restspeicher des Geräts, der nie unterschritten wird.

#### Ende und Nachhalten

- Bei Ablauf oder vorzeitigem Beenden durch den Gastgeber schließt der Server sofort. Alle Zugangsschlüssel werden ungültig.
- Die Space-Medien bleiben für die gewählte Nachhaltefrist im Space-Speicher: sofort löschen, 1 Tag, 1 Woche oder eigene Angabe. Die Frist wird beim Anlegen festgelegt und ist jederzeit änderbar (verlängern oder verkürzen).
- In der Nachhaltezeit wählt man aus, was in die eigene Galerie übernommen wird. Vorauswahl: nichts. Schaltflächen „Alle auswählen“ und „Nichts auswählen“.
- Vor Fristende kommt eine Erinnerung, damit der Nutzer mitbekommt, dass das Nachhalten ausläuft und er handeln sollte. Danach wird automatisch gelöscht.
- Offen: ob das Nachhalten auch für App-Teilnehmer gilt (F), ob ohne Reaktion gelöscht wird (G), Form der Erinnerung (H).

#### Gastgeber-Handy als Verteiler

- Ist das Gastgeber-Handy aus, leer oder außer Reichweite, ist der Space für alle nicht erreichbar.
- Schon Heruntergeladenes bleibt bei den Teilnehmern.

#### Betrieb

- Der Host-Dienst darf bei langen Events (Hochzeit, ganzer Abend) nicht nach einigen Stunden sterben. Foreground-Service-Typ so wählen, dass kein Zeitlimit greift (`dataSync` hat ab Android 15 ein 6-Stunden-Limit pro Tag, daher vermeiden).

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

## Offene Punkte

- **F) Nachhalten bei App-Teilnehmern.** Gilt das Nachhalten nur beim Gastgeber oder auch bei App-Teilnehmern? Dafür müssten App-Teilnehmer während des Space alle Medien vorab in den getrennten Speicher laden, was Speicher kostet. Browser-Gäste kommen nach Ablauf technisch an nichts mehr. Vorschlag: Nachhalten nur beim Gastgeber. Teilnehmer laden während der Laufzeit selbst herunter, was sie behalten wollen.
- **G) Löschung ohne Reaktion.** Wird ohne Reaktion auf die Erinnerung zum Fristende trotzdem gelöscht? Vorschlag: ja.
- **H) Form der Erinnerung.** Vorschlag: Benachrichtigung mit Aktionen „Ansehen / Verlängern / Jetzt löschen“, Hinweis-Punkt am App-Symbol und Hinweis beim Öffnen der App. Android-„Bubbles“ sind für Unterhaltungen gedacht und dafür ungeeignet.
- **I) Standortdaten beim Hochladen.** Sollen GPS-Daten beim Hochladen standardmäßig entfernt werden, abschaltbar? Vorschlag: ja.
- **J) Fremdes WLAN.** Im eigenen WLAN der App schützt WPA2. In einem fremden, geteilten WLAN wäre HTTP mit Token mitlesbar. Für Browser-Gäste gibt es ohne Cloud keine Verschlüsselung ohne Browser-Warnung, weil nur selbstsignierte Zertifikate möglich sind. Bisher: Für das MVP reicht HTTP plus Token, TLS mit selbstsigniertem Zertifikat (Fingerprint im QR-Code) später prüfen. Vorschlag: In fremden WLANs standardmäßig das eigene WLAN der App (WPA2) nutzen. App-zu-App-Verbindungen immer verschlüsselt, mit Zertifikats-Pinning über den QR-Code.
- **K) Zurückziehen und Entfernen.** Dürfen Teilnehmer eigene Uploads zurückziehen und der Gastgeber beliebige Medien entfernen? Vorschlag: ja, beides.
- Kompatibilität mit dem LocalSend-Protokoll: Interessant, aber kein MVP-Ziel.
- Moderne Galerie-Funktionen wie semantische Suche oder Gesichtsgruppierung, nur On-Device: nach dem MVP.
- Design-Richtung der App: Der Banner (`docs/banner.png`) gibt die Stimmung vor (dunkel, warme Glut, Funken), ist aber noch kein festes Design-System. Das Basis-Theme steht (ADR 0003), das Design-System entsteht ab Meilenstein 2.

## Änderungsnotiz

Änderungen am 16.10.2026: Space-Speicher getrennt von der Galerie, Rechte-Schalter entfallen, Nachhalten mit Frist.
