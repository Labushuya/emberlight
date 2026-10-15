# emberlight: Konzept und Entscheidungen

Stand: 15.10.2026, nach Meilenstein 1 (Grundgerüst). Dieses Dokument hält fest, was entschieden ist. Offene Punkte stehen am Ende.

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

- Die App betreibt einen eingebetteten HTTP-Server. Gäste scannen den QR-Code und landen auf einer Webseite, auf der sie hochladen, ansehen und herunterladen können.
- Die Gäste-Seite übernimmt die Gestaltung des Spaces (Name, Titelbild, Farbe).
- Nutzer mit emberlight treten dem Space nativ bei (QR-Scan in der App).

### Spaces

- Eigenschaften: Name, Titelbild, Akzentfarbe, Laufzeit/Ablaufzeitpunkt.
- Rechte pro Space (MVP): Hochladen erlaubt ja/nein, Herunterladen erlaubt ja/nein. Später feiner (pro Person, einseitig/beidseitig, Moderation durch Host).
- Einladungscodes und Space sind nur bis zum Ablauf gültig. Danach schließt der Server, Tokens sind ungültig.
- Empfangene Medien landen in einem eigenen Album, z. B. `Pictures/Emberlight/<Space-Name>`.
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

- Verschlüsselung im **vorhandenen** WLAN: Im eigenen WLAN der App schützt WPA2. In einem fremden, geteilten WLAN wäre HTTP mit Token mitlesbar. Für das MVP reicht HTTP plus Token, TLS mit selbstsigniertem Zertifikat (Fingerprint im QR-Code) später prüfen.
- Kompatibilität mit dem LocalSend-Protokoll: Interessant, aber kein MVP-Ziel.
- Moderne Galerie-Funktionen wie semantische Suche oder Gesichtsgruppierung, nur On-Device: nach dem MVP.
- Design-Richtung der App: Der Banner (`docs/banner.png`) gibt die Stimmung vor (dunkel, warme Glut, Funken), ist aber noch kein festes Design-System. Das Basis-Theme steht (ADR 0003), das Design-System entsteht ab Meilenstein 2.
