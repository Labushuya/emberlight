# emberlight: Konzept Meilenstein 4 (Netz, Hotspot, Gast-Modus)

Stand: 25.10.2026. Dieses Dokument ist ein Konzept, kein Code. Grundlage sind die Nutzervorgaben und Entscheidungen vom 25.10.2026. Sie gelten als verbindlich und sind im Text mit **Vorgabe** gekennzeichnet.

Code-Stand für alle **belegt**-Angaben: `origin/main` @ `72fa9be`.

Verhältnis zu den anderen Dokumenten: [KONZEPT.md](KONZEPT.md) bleibt die Entscheidungsgrundlage. Dort sind überholte Aussagen markiert und verweisen auf Abschnitt 1 dieses Dokuments. Für den Stand von Meilenstein 3 gelten weiter [PLAN-M3.md](PLAN-M3.md), [ADR 0012](adr/0012-lokaler-server.md), [ADR 0017](adr/0017-einrichtung-und-betrieb.md) und [ADR 0018](adr/0018-einladung-und-beitritt.md).

## 0. Kennzeichnung

- **belegt** `Pfad:Zeile`: im Quelltext oder in den Dokumenten gefunden.
- **erwartet**, mit Quelle: Android-, iOS- oder Browser-Dokumentation bzw. Erfahrungswert. Nicht gemessen.
- **zu prüfen**: offen. Muss am Gerät, in einem Test oder über eine Quelle geklärt werden.
- **Vorgabe**: Nutzerentscheidung vom 25.10.2026.
- Ein Punkt gilt erst dann als „unterstützt“, wenn die Messkriterien in Abschnitt 13 erfüllt sind. Bis dahin steht im Konzept „vorgesehen“ oder „erwartet“.

Die Pfade sind relativ zum Repo-Wurzelverzeichnis.

## 1. Widersprüche zum bisherigen Konzept

Markierungen: **überholt** (die Aussage gilt nicht mehr), **präzisieren** (die Aussage stimmt nur teilweise), **offen** (Entscheidung fehlt), **Hinweis** (kein Widerspruch, aber zu beachten). In [KONZEPT.md](KONZEPT.md) sind die Stellen mit Verweis auf diesen Abschnitt markiert. Der Text bleibt zur Nachvollziehbarkeit stehen.

| Nr | Fundstelle | Bisherige Aussage | Neuer Stand | Markierung |
|---|---|---|---|---|
| 1 | KONZEPT.md Z. 22–23, 30 | Zwei Wege, „für den Nutzer gleich“; „Die App wählt den Weg“ | Hotspot ist Standard. Vorhandenes WLAN ist die Alternative für große Gruppen (Vorgabe). Der Standardweg ist nicht mehr dem Gerät überlassen, sondern eine Nutzerentscheidung mit Geräte-Check. | überholt |
| 2 | KONZEPT.md Z. 34 | „Standard: In fremden, geteilten WLANs nutzt die App ihr eigenes WLAN … Dort ist der Verkehr verschlüsselt.“ | Standard ist der Hotspot (Vorgabe). Verschlüsselt ist nur die Funkstrecke (WPA2-PSK). Das Passwort geht über den QR-Code an alle Gäste. Browser-Gäste laufen per HTTP (README.de.md:112, ADR 0012: „Keine TLS-Verschlüsselung“). | überholt, präzisieren |
| 3 | KONZEPT.md Z. 35 | „App-zu-App-Verbindungen sind immer verschlüsselt, mit Zertifikats-Pinning über den QR-Code.“ | Der Anspruch bleibt. Das Verfahren ist im Prototyp festzulegen (Vorgabe). Im Code gibt es kein TLS (ADR 0012, Z. 40). README.de.md Z. 124: „geplant, ohne Termin“. | präzisieren |
| 4 | KONZEPT.md Z. 22 | „Auffindbar per mDNS/NSD“ | Im Code gibt es kein NSD. `share/network` enthält nur `build.gradle.kts` (belegt). M3 findet über QR-Code und URL. Die Vorgabe nennt NSD nicht. NSD wäre von `ACCESS_LOCAL_NETWORK` betroffen (ADR 0012, Z. 37). | offen |
| 5 | KONZEPT.md Z. 62 | Höchstens 50 aktive Teilnehmer | Die Grenze hängt vom Netzweg ab. Im Hotspot ist die Gästezahl vermutlich deutlich niedriger (oft um 10, zu prüfen). Im Code: `share/space/.../participants/ParticipantRoster.kt:232`, `MAX_PARTICIPANTS = 50`. | offen |
| 6 | Beitrittsablauf (neu), ADR 0018 Nachtrag, Punkt 19 | M3: Die Gäste-Seite holt die Vorschau über den Server (`POST /api/v1/join/preview`, ADR 0018 Nachtrag 3.4b). Der QR-Code trägt nur das Einladungsgeheimnis im Fragment (`guest.js:11`, `#i=` plus 43 Zeichen). | Vorgabe: Die Einladung wird offline aus dem QR-Code angezeigt (Space-Name, Gastgeber, Ende), dann „Annehmen“. Das verlangt ein neues QR-Format. Die Versionierung ist zu klären. | überholt (ab M4) |
| 7 | KONZEPT.md Z. 25 | Der QR-Code enthält die WLAN-Daten im Format `WIFI:T:WPA;S:…;P:…;;`. | Für Browser-Gäste bleibt das so: zwei QR-Codes, zuerst WLAN, dann Space (Stufe 3). In Stufe 1 gibt es einen QR-Code mit allen Daten (Vorgabe). Dieses Format ist noch zu entwerfen. | präzisieren |
| 8 | KONZEPT.md Z. 17, 57; CLAUDE.md „Harte Grenzen“ | „Keine Konten“ | Vorgabe: lokales Profil mit Identitätsschlüssel, kein Online-Konto. Die Regel „keine Konten“ in CLAUDE.md ist damit zu präzisieren. Die Änderung von CLAUDE.md gehört nicht zu diesem Dokument. | präzisieren, offen (CLAUDE.md) |
| 9 | CLAUDE.md, Einleitung („soll auf jedem Android 8+ laufen“) | Lauffähig auf Android 8 und neuer | Vorgabe: Zielgruppe sind moderne Smartphones, ältere Geräte bekommen einen Fallback. `minSdk` bleibt 26. Der Fallback ist Stufe 2 (Android 8 und 9). | präzisieren |
| 10 | KONZEPT.md Z. 28 | Berechtigung `NEARBY_WIFI_DEVICES` ab Android 13, darunter Standort | Zusätzlich `ACCESS_LOCAL_NETWORK` ab API 37 bei targetSdk 37 (belegt: `share/server/src/main/AndroidManifest.xml:6`, ADR 0017, Abschnitt „Lokales Netzwerk“). Im Manifest fehlen derzeit `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` und `CHANGE_WIFI_STATE` (belegt: nicht vorhanden). | präzisieren |
| 11 | PLAN-M3.md, Zeile 3.7 | „… danach Release `v0.3.0`“ | Vorgabe: `v0.3.0-rc.1` dient dem Test der Gastgeber-Seite. Kein finales `v0.3.0`. Das nächste reguläre Release kommt nach M4. | überholt (in PLAN-M3, dort nicht geändert) |
| 12 | KONZEPT.md Z. 116 | Der Host-Dienst soll nicht `dataSync` nutzen (6-Stunden-Limit ab Android 15) | Der Server nutzt `connectedDevice` (`share/server/src/main/AndroidManifest.xml:23`). Der Export-Worker nutzt aber `dataSync` (`feature/space/src/main/AndroidManifest.xml:37`, `SpaceExportWorker.kt:174`). Der Worker läuft nur kurz. Ob das relevant ist, ist zu prüfen. | Hinweis |
| 13 | KONZEPT.md Z. 36 | Im „eigenen bzw. vertrauten WLAN“ ist HTTP möglich | Vertraut ist kein Kriterium, das die App prüfen kann. Gast-Zugänge, öffentliche, Hotel- und Firmen-WLANs isolieren Geräte meist (Vorgabe). Der Hotspot ist der Standard. | präzisieren |

## 2. Kernidee

- **Eine** Android-App. Man kann Gastgeber **und** Gast sein. Keine eigenständige Browser-Version (Vorgabe).
- Der Gastgeber erzeugt, verwaltet und löscht Spaces. Er teilt **einen** QR-Code (Vorgabe).
- Die Verbindung läuft über einen **lokalen Hotspot** des Gastgeber-Handys. Mobile Daten laufen parallel weiter (Vorgabe, Gastgeber: erwartet, siehe Abschnitt 3.1).
- Muss dafür das aktuelle WLAN verlassen werden, ist das suboptimal, aber akzeptiert. Voraussetzung ist, dass es keine Lösung für WLAN, mobile Daten und Hotspot gleichzeitig gibt (Vorgabe). Die Messung in Abschnitt 13 (M1) prüft genau das.
- Kein zusätzliches Hardware-Gerät. Keine Cloud, kein fremder Server (Vorgabe, deckt sich mit CLAUDE.md, belegt).
- Zielgruppe: moderne Smartphones. Ältere Geräte bekommen einen Fallback (Vorgabe).
- Der Nutzer erfährt **immer**, was mit seinem Handy möglich ist und was nicht, und bekommt Auswege angeboten (Vorgabe, Abschnitt 6).

## 3. Netzwege

### 3.1 Hotspot (Standard)

- Android `WifiManager.startLocalOnlyHotspot()`. SSID und Passwort vergibt Android zufällig. Beide sind nicht anpassbar (KONZEPT.md Z. 25; die Vorgabe bestätigt das). Der Hotspot hat nichts mit dem Tethering aus den Einstellungen zu tun (KONZEPT.md Z. 24).
- Die App startet den Hotspot nicht im Code. Im Repo gibt es dafür keine Umsetzung (belegt: keine Treffer für `startLocalOnlyHotspot`, `WifiNetworkSpecifier`, `NsdManager`, `TRANSPORT_VPN`; `share/network` ist leer). Der Start ist Paket M4.
- **Gastgeber, mobile Daten parallel:** erwartet, weil der LocalOnlyHotspot nur das eigene Funknetz betrifft. Zu prüfen (M1).
- **Bisheriges WLAN parallel:** nur bei STA+AP-Concurrency. `WifiManager.isStaApConcurrencySupported()` (erwartet API 30, zu prüfen) zeigt die Fähigkeit des Geräts. Der Test ist M1.
- **Berechtigungen (erwartet, zu prüfen):** `CHANGE_WIFI_STATE`. Standort (`ACCESS_FINE_LOCATION`) bis API 32, `NEARBY_WIFI_DEVICES` ab API 33. Zusätzlich `ACCESS_LOCAL_NETWORK` ab API 37 mit targetSdk 37 (belegt, ADR 0012, Z. 37). Das Manifest enthält derzeit nur `ACCESS_NETWORK_STATE` und `CHANGE_NETWORK_STATE` aus dem Netzbereich (belegt, `share/server/src/main/AndroidManifest.xml:9, 13`).
- **Grenzen:**
  - Gästezahl oft um 10 (zu prüfen). Android verrät normalen Apps die Grenze nicht (erwartet, siehe Abschnitt 13, M4).
  - Reichweite und Akku (erwartet).
  - Der Hotspot endet mit dem Prozess (erwartet, zu prüfen). Der Server hat schon einen Pfad für den Prozess-Tod (ADR 0017, Nachbesserung 3.3b).
- **Adresse:** Der Server bindet an die IPv4-Adresse der aktiven Netzschnittstelle, nicht an `0.0.0.0` (belegt, ADR 0013, Abschnitt „Bindung und Subnetz“, und ADR 0012, Nachtrag 22.10.2026). Im Hotspot muss die Schnittstelle des Hotspots gefunden werden (zu prüfen).

### 3.2 Vorhandenes WLAN (Alternative für große Gruppen)

- Alle Geräte hängen am selben Router oder Access Point im selben IPv4-Netz. Der Router vermittelt nur lokal. Er braucht kein Internet und speichert nichts (erwartet). Der Server bleibt das Gastgeber-Handy (Vorgabe, KONZEPT.md Z. 110–111).
- **Kein fremder Server, aber fremde Infrastruktur** (Vorgabe): Der Betreiber des Routers könnte unverschlüsselten Verkehr (Browser-Gäste) mitlesen. Mit App-Verschlüsselung sieht er nur, dass Geräte miteinander kommunizieren (erwartet, siehe Abschnitt 10).
- **Voraussetzungen** (Vorgabe, belegt in Teilen): keine Client-Isolation, gleiches Netzsegment, IPv4. Ohne IPv4-Adresse meldet die App „Kein WLAN mit IPv4-Adresse verbunden“ (belegt: `feature/space/src/main/res/values/strings.xml:272`).
- **Wechsel des Netzes** (belegt, ADR 0017, Abschnitt „WLAN und Netzwechsel“): Verlust pausiert den Server. Derselbe Netzwerkzustand setzt ihn fort. Ein anderes Netz wird bestätigt, und der QR-Code ändert sich.

### 3.3 Gast in fremdem WLAN

Vorgabe, Einschätzung nach Netztyp. Die Spalte „Erwartung“ ist erwartet, nicht gemessen.

| Netztyp | Erwartung | Hinweis an den Nutzer |
|---|---|---|
| Privates Heim-WLAN | meist erreichbar | Vorschlag: Vorhandenes WLAN nutzen |
| Gast-Zugang eines Routers (z. B. FRITZ!Box-Gastzugang) | meist isoliert, nicht erreichbar | „Hotspot nutzen“ |
| Öffentlich, bezahlt, Hotel, Firma | meist isoliert, nicht erreichbar | „Hotspot nutzen“ |
| Verein, Freunde, unbekanntes Heim-WLAN | zu prüfen | Verbindungstest (Abschnitt 8) |

- Alle Teilnehmer brauchen Zugang zu diesem WLAN. emberlight verteilt keine fremden WLAN-Passwörter (Vorgabe).
- Captive Portals erkennt Android (Vorgabe). Ob das bei einem LocalOnlyHotspot ohne Internet passiert, ist zu prüfen.

### 3.4 Browser-Fallback

Siehe Stufe 3 in Abschnitt 4. Die heutige Gäste-Seite aus Meilenstein 3 wird weiterverwendet (Vorgabe, belegt: `share/server/src/main/assets/guest/`).

## 4. Stufenmodell

| Stufe | Geräte | Zugang | Eigenschaften |
|---|---|---|---|
| 1, App voll | Android 10 und neuer (erwartet: `WifiNetworkSpecifier` ab API 29) | Ein QR-Code. Die App verbindet sich mit `WifiNetworkSpecifier` und bindet nur ihren eigenen Verkehr an den Hotspot. | Andere Apps nutzen weiter mobile Daten (erwartet). Ab Android 12 (API 31) bleibt das bisherige WLAN verbunden, geräteabhängig (erwartet, zu prüfen: `isStaConcurrencyForLocalOnlyConnectionsSupported()`, Quelle: AOSP-Dokumentation zur STA/STA-Concurrency). Verschlüsselt, Hintergrund-Upload, Benachrichtigungen (Vorgabe). |
| 2, App eingeschränkt | Android 8 und 9 | Hotspot-Verbindung einmal von Hand (Vorgabe). | Bisheriges WLAN ist getrennt. Mobile Daten parallel nur mit Netz-Bindung zu prüfen (Vorgabe, zu prüfen). |
| 3, Browser modern | Geräte ohne App, z. B. iPhone, Laptop, Android ohne Installation | Zwei QR-Codes: WLAN, dann Space. | Unverschlüsselt (HTTP), mit Hinweis. Internet parallel eher nicht (zu prüfen). Kein Hintergrund-Upload. Grenze: Abschnitt 5. |
| 4, Browser basic | Browser unterhalb der Grenze aus Abschnitt 5 | **Vorerst nicht unterstützt** (Entscheidung). Hinweis „Browser zu alt“. | Vorgabe. |

- **iPhones** nutzen Stufe 3 (Vorgabe). Eine iOS-App ist später möglich. Vorgabe: iOS erlaubt Apps keinen eigenen Hotspot. Das ist erwartet und anhand der Apple-Dokumentation noch zu prüfen. iPhones können deshalb nur im Modus „vorhandenes WLAN“ Gastgeber sein.
- Die Stufe ergibt sich aus dem Gerät, aus `Build.VERSION.SDK_INT` und dem Geräte-Check (Abschnitt 6). Der Nutzer wählt den Netzweg, nicht die Stufe. Das ist eine Ableitung aus der Vorgabe, dass der Nutzer den Weg überschreiben kann (vgl. KONZEPT.md Z. 30, Abschnitt 1, Nr. 1).

## 5. Browser-Grenze

Vorgabe: „Modern“ ist vorläufig Chrome 88+ bzw. Safari 15+. Die Grenze muss anhand der tatsächlich genutzten APIs der Gäste-Seite festgelegt werden. Das ist hier geschehen.

**Methode:** Die Dateien `share/server/src/main/assets/guest/*.js` und `guest.css` wurden auf Syntax und Web-APIs durchsucht. Syntax: ES5 (`var`, `function`, keine Pfeilfunktionen, kein `let`/`const`, kein `async`/`await`, keine Module; belegt durch Suche). Die Versionsangaben stammen aus Erinnerung. Die MDN-Seiten gaben die Kompatibilitätstabellen beim Abruf nicht vollständig her. Deshalb sind alle Versionen **erwartet**, bis sie gegen die MDN-Tabellen geprüft sind.

| Nutzung | Beleg | Erwartete Mindestversion (Chrome / Safari / Firefox) | Folge bei Fehlen |
|---|---|---|---|
| `Blob.arrayBuffer()` (Vorabprüfung der Dateien) | `guest-upload.js:157` | 76 / 14 / 69 | **Kein Upload möglich.** Harte Grenze. |
| `fetch` mit `cache` und `credentials` | `guest-core.js:72, 92`, `guest-upload.js:95` | 42 / 10.1 / 39 | Seite lädt nicht. |
| `XMLHttpRequest` mit Upload-Fortschritt | `guest-upload.js:325` | alt | Upload ohne Fortschritt. |
| `IntersectionObserver` | `guest-space.js:894, 1349` | 51 / 12.1 / 55 | Mit Fallback (`guest-space.js:893–895, 1348`): kein Nachladen beim Scrollen, nur „Mehr laden“. Kein Mindestwert nötig. |
| CSS `aspect-ratio` (Raster-Kacheln) | `guest.css:310` | 88 / 15.0 / 89 | Kacheln ohne feste Höhe. Layout-Schaden, zu prüfen. |
| CSS `:focus-visible` | `guest.css:128–133` | 86 / 15.4 / 85 | **Kein sichtbarer Fokusring.** Barrierefreiheit betroffen. |
| CSS `inset` | `guest.css:448` | 87 / 14.1 / 66 | Abdunkelung der Dialoge fehlt. |
| CSS `gap` in Flex-Containern | `guest.css:110, 188, 262, 414, 432, 468, 530, 566` | 84 / 14.1 / 63 | Abstände fehlen. |
| `localStorage`, `Intl.NumberFormat`, `history.replaceState`, `Promise`, `Map`, `Array.from`, `Object.assign` | `guest-core.js:156, 258`, `guest.js:299`, `guest-space.js:739` u. a. | alt (ES2015 und früher) | Vereinzelt Funktionsverlust. |

**Ergebnis (Vorschlag, erwartet):**

- Die harte Grenze ist `Blob.arrayBuffer()` (Safari 14), nicht Safari 15.
- Die Grenze mit Folgen für die Barrierefreiheit ist `:focus-visible` (Safari 15.4). Ohne eine Rückfallregel mit `:focus` hätten Safari 14 und 15.0 bis 15.3 keinen sichtbaren Fokus. Das widerspricht dem Grundsatz, dass Barrierefreiheit nicht eingespart wird (CLAUDE.md). Die Rückfallregel ist eine Code-Änderung und nicht Teil dieses Konzepts.
- Vorschlag für die Grenze „modern“: **Chrome 88+, Edge (Chromium) 88+, Firefox 89+, Safari 15.4+ bzw. iOS 15.4+.** Ob Samsung Internet oder andere Chromium-Ableger gleich zu behandeln sind, ist zu prüfen.
- Die Erkennung sollte per Feature-Check erfolgen, nicht per User-Agent: `window.fetch`, `Blob.prototype.arrayBuffer`, `CSS.supports("aspect-ratio", "1 / 1")`, `CSS.supports("selector(:focus-visible)")`. Fehlt eines, zeigt die Seite Stufe 4 („Browser zu alt“). Das ist eine Code-Änderung und nicht Teil dieses Konzepts.
- **Testlücke:** Die Browser-Tests laufen in CI nur mit Chrome headless (CLAUDE.md, „Browser-Tests“; ADR 0019, Nachtrag 3.5a-3). Safari und Firefox sind nicht getestet. Das ist mit M7 (Abschnitt 13) zu schließen.

## 6. Geräte-Check und Auswege

### 6.1 Prüfungen

Beim ersten Start und vor jedem Space. Die Prüfungen sind Vorgabe. Die Befunde sind belegt oder erwartet, wie angegeben.

| Prüfung | Quelle des Befunds | Stand |
|---|---|---|
| Hotspot möglich | `startLocalOnlyHotspot()` mit Rückmeldung (erwartet, API 26+) | nicht im Code |
| STA+AP-Concurrency | `isStaApConcurrencySupported()` (erwartet API 30, zu prüfen) | nicht im Code |
| Concurrency für lokale Verbindungen (Gast) | `isStaConcurrencyForLocalOnlyConnectionsSupported()` (erwartet API 31) | nicht im Code |
| Mobile Daten | `ConnectivityManager`, Transport `TRANSPORT_CELLULAR` (erwartet) | nicht im Code |
| VPN aktiv | `NetworkCapabilities.TRANSPORT_VPN` (erwartet, API 21+) | nicht im Code (belegt: keine Treffer) |
| Berechtigungen | Standort bzw. `NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK`, Benachrichtigungen | Einrichtung und Prüfung vorhanden für `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, Akku-Ausnahme (belegt, ADR 0017, Abschnitt „Lokales Netzwerk“ und „Benachrichtigungen“). Standort und `NEARBY_WIFI_DEVICES` fehlen (belegt). |
| Stufe des Geräts | `Build.VERSION.SDK_INT` (erwartet) | nicht im Code |

### 6.2 Auswege

Vorgabe, mit Ergänzungen. Alle Texte gehen in `strings.xml` (de und en), CLAUDE.md.

| Befund | Ausweg |
|---|---|
| Kein Hotspot | Modus WLAN bzw. anderes Handy als Gastgeber |
| Hotspot voll | Modus WLAN |
| Gast ohne App | Browser-QR (Stufe 3) |
| VPN sperrt | Ausnahme setzen bzw. VPN pausieren, mit Anleitung |
| Altes Android | Anleitung Stufe 2 |
| Captive Portal bzw. Isolation | Hotspot nutzen |

## 7. VPN

Einstufung **zu prüfen**. Erwartet, nicht gemessen (Vorgabe):

- Funktioniert, wenn die VPN-App lokale Netze bzw. emberlight ausnimmt (Split-Tunneling, „LAN erlauben“).
- Normales VPN ohne Ausnahme: Versuch, sonst Hinweis.
- Firmen-VPN mit Sperre: nicht unterstützt.

Die App erkennt ein aktives VPN über `TRANSPORT_VPN` (erwartet). Ob es sperrt, zeigt erst der Verbindungstest (Abschnitt 8). Im Prototyp werden 2–3 verbreitete VPN-Apps gemessen (M5). Der Nutzer wählt die Apps aus, die Liste steht hier nicht.

## 8. Netzanomalien und Verbindungstest

- **Hotspot-Modus:** kaum relevant. Das Gastgeber-Handy ist das Gateway. Zufällige Subnetze beachten (ab Android 11, erwartet, zu prüfen).
- **WLAN-Modus:** Ein Verbindungstest mit Diagnose statt Einzelfallabdeckung (Vorgabe). Kategorien:
  - Isolation vermutet
  - VPN aktiv
  - Captive Portal
  - anderes Netz bzw. Segment
  - nur IPv6
- **Nur-IPv6-Netze: nicht unterstützt** (Vorgabe). Der Server arbeitet mit IPv4 (belegt: ADR 0013, Abschnitt „Netz“, und `feature/space/src/main/res/values/strings.xml:272`).
- Der Server lehnt Anfragen aus fremden Subnetzen ab (belegt, ADR 0013, Abschnitt „Bindung und Subnetz“). Die Diagnose kann diesen Befund nutzen. Ob das ausreicht, um Isolation von einem Netzfehler zu unterscheiden, ist zu prüfen.

## 9. Beitrittsablauf (App)

Vorgabe, Schritte in dieser Reihenfolge:

1. **QR scannen** (In-App-Scanner oder Kamera). Ohne App führt der Link zur Download-Seite. Der QR-Code ist ein Link, alle Daten stehen im Fragment nach `#`. Die Daten gehen an keinen Server (Vorgabe). Belegt ist das Fragment-Prinzip (`guest.js:11`, `guest.js:299`, ADR 0013, Z. 113).
   - **Zu prüfen:** Auf welche Adresse zeigt der QR-Link? Zeigt er auf den Gastgeber im lokalen Netz, bleibt das Geheimnis im lokalen Netz. Zeigt er auf eine öffentliche Download-Seite, darf dort kein Geheimnis verarbeitet werden. Die Seite darf das Fragment nicht auslesen.
2. **Einladung zuerst.** Offline aus dem QR-Code: Space-Name, Gastgeber, Ende. Dann „Annehmen“. Die Zustimmung kommt vor dem WLAN-Wechsel (Vorgabe). Das ist die Abweichung 6 aus Abschnitt 1.
3. **Verbindung zum Hotspot** (Android-Bestätigung) und Hintergrund-Check: Erreichbarkeit, Echtheit des Gastgebers (Zertifikats-Fingerprint aus dem QR-Code), Space offen, Platz frei (Vorgabe). Der Platz ist belegt: `space_full`, HTTP 409 (`share/server/src/main/kotlin/io/github/labushuya/emberlight/share/server/SpaceRoutes.kt:474`).
4. **Name eingeben.** Aus dem Profil vorbefüllt. Bei Kollision „Anna 2“ (Vorgabe). Die Kollisionsregel ist belegt: `name_taken` mit Vorschlag (ADR 0018, Punkt 7).
5. **Im Space.** Bei Fehlern mit Diagnose-Hinweis (Abschnitt 8).

**Option für den Gastgeber: „Neue Gäste bestätigen“.** Entscheidung: ja, standardmäßig aus (Vorgabe). Ein Gast in der Warteschlange braucht einen eigenen Zustand. Ob das den Ablauf in ADR 0018 ändert, ist zu prüfen.

**Verbesserung (Vorschlag):** Standort-Bereinigung bereits auf dem Gast-Handy, damit der Standort das Gerät nicht verlässt (Vorgabe, Verbesserung). Heute erfolgt die Bereinigung auf dem Server (belegt, ADR 0015).

## 10. Verschlüsselung

- **App zu App:** TLS mit gepinntem Zertifikat (Fingerprint im QR-Code) oder ein gleichwertiges Verfahren. Das Verfahren ist im Prototyp festzulegen (Vorgabe). Offen.
  - Belegt: Der Server spricht heute HTTP (ADR 0012, Z. 40 und Z. 197).
  - Belegt: R8 bricht ohne Regeln ab, weil optionale TLS-Provider fehlen (tcnative, Conscrypt, BouncyCastle). Das ist beim Bau aufgetreten (ADR 0013, Z. 352). Für ein TLS-Setup mit Ktor CIO ist das relevant.
  - Zu prüfen: Ktor CIO mit selbstsigniertem Zertifikat auf Android 8 bis 17.
  - Vorgabe: Das Pinning-Verfahren wird im Prototyp verglichen.
- **Hotspot-Passwort:** Das WPA2-Passwort geht über den QR-Code an alle Gäste. In einem WPA2-PSK-Netz kann jeder Teilnehmer mit dem Passwort den Verkehr anderer Teilnehmer mitlesen, wenn er den Verbindungsaufbau der anderen mitschneidet (erwartet, WPA2-Eigenschaft; zu prüfen). Die App-Verschlüsselung ist deshalb auch im Hotspot nötig, nicht nur im fremden WLAN.
- **Browser-Gäste:** unverschlüsselt (HTTP), mit Hinweis (Vorgabe). Belegt: Hinweistext unter dem Schlüssel `note` in `share/server/src/main/assets/guest/i18n/de.json`, Designentscheidung (d) in PLAN-M3.md.
- Browser können ohne fremden Server kein vertrauenswürdiges HTTPS anbieten (belegt, README.de.md:113, KONZEPT.md Z. 36).

## 11. Konto und Profil

Vorgabe: lokales Profil, kein Online-Konto. Enthält Name, Bild und einen festen Identitätsschlüssel. Möglich damit:

- wiederkehrende Gäste erkennen („bekannt“)
- Vertraute automatisch annehmen
- Sperrliste (lokal)
- Verlauf eigener Spaces
- Kontakttausch per QR-Code

Hinweise:

- **Identitätsschlüssel:** Der Einladungsschlüssel liegt im AndroidKeyStore, nicht exportierbar und an die Installation gebunden (belegt, ADR 0018, Nachtrag 3.4b, Punkt 18). Für den Identitätsschlüssel gilt das gleiche Prinzip, sofern er so abgelegt wird (Vorschlag). Ein Umzug auf ein anderes Gerät bringt den Schlüssel dann nicht mit.
- **Profilumzug (Vorgabe):** über einen **Transfer-Space**, der ausschließlich der Übertragung von Profil und Daten zwischen eigenen Geräten dient. Der Geräte-Schlüssel muss dabei angepasst werden.
  - Vorschlag, Entscheidung offen: Das neue Gerät erzeugt einen neuen Schlüssel. Das alte signiert eine Nachfolge-Erklärung (alt zu neu). Kontakte übernehmen sie beim nächsten Treffen. Das alte Gerät sperrt sein Profil nach dem erfolgreichen Umzug und löscht die Daten optional auf Wunsch.
  - **Tragweite:** Ohne Server gibt es keine zentrale Deaktivierung. Ein verlorenes oder gestohlenes Altgerät kann nicht aus der Ferne gesperrt werden (Vorgabe, Feststellung).
  - Abhilfe (Vorschlag): Beim Anlegen des Profils ein **Notfall-Widerruf** in der Profilsicherung (Datei).
- **Transfer-Space und Trennung (zu prüfen):** Der Transfer-Space darf nicht über den Space-Speicher laufen, sonst gelten die Regeln für Space-Medien (KONZEPT.md, Abschnitt Trennung). Ob der Transfer über dieselbe Hotspot-Technik läuft, ist für große Datenmengen vorgesehen (Vorgabe) und zu prüfen.
- **Profilbild:** Ortsdaten entfernen, wie bei Titelbildern (Hinweis; belegt für Titelbilder in KONZEPT.md Z. 154, für Uploads in ADR 0015).
- **Ein lokales Profil ist kein Konto im Sinne einer Online-Anmeldung.** Die Regel „keine Konten“ in CLAUDE.md muss präzisiert werden (Abschnitt 1, Nr. 8).

## 12. Releases

- Vorgabe: `v0.3.0-rc.1` dient dem Test der Gastgeber-Seite. Kein finales `v0.3.0`. Das nächste reguläre Release kommt nach Meilenstein 4.
- Belegt: Vorab-Tags (`vX.Y.Z-rc.N`) sind umgesetzt (CLAUDE.md, „Release und Versionen“, ADR 0004). Das Gate prüft den Probelauf.
- Die Abweichung zu PLAN-M3, Paket 3.7, steht in Abschnitt 1, Nr. 11.

## 13. Prototyp und Messplan

Vorgabe: Der erste Schritt von M4 ist ein Prototyp mit Messplan. Die Kriterien unten sind **Vorschläge**. Die Schwellen muss der Nutzer bestätigen. Bis dahin gilt nichts als „unterstützt“.

| ID | Messpunkt | Vorgehen | Kriterium „unterstützt“ (Vorschlag) |
|---|---|---|---|
| M1 | Gastgeber: Hotspot, mobile Daten und WLAN gleichzeitig | Hotspot an, WLAN verbunden, mobile Daten an. 30 Minuten laufen lassen. | Hotspot ohne Abbruch über 30 Minuten. Mobile Daten aktiv (Test in einer zweiten App). WLAN bleibt verbunden. Das Concurrency-Flag stimmt mit dem Befund überein. Ergebnis je Gerät. |
| M2 | Gast-App: `WifiNetworkSpecifier`, Netz-Bindung, mobile Daten parallel | Gast verbindet sich über den QR-Code. Eigener Verkehr läuft über den Hotspot. Fremde App testet mobile Daten. | Verbindungsaufbau in höchstens 30 s, in 9 von 10 Versuchen. Mobile Daten für fremde Apps aktiv. |
| M3 | Browser-Gast im Hotspot mit mobilen Daten (Android und iPhone) | Gast scannt zwei QR-Codes. Hinweis „kein Internet“ wird geprüft. Upload 50 MB, Download. | Seite lädt, Upload und Download gelingen. Hinweis „Verbunden bleiben“ erscheint. |
| M4 | Maximale Gästezahl | Gäste schrittweise zuschalten (2, 4, 8, 10, 12, 15 …), bis Verbindungsfehler auftreten. Je Gerät drei Läufe. | Anzahl, bei der alle drei Läufe stabil sind. Ergebnis je Gerät. Die App zeigt die Grenze, die sie erkennt. Android verrät die Grenze nicht (erwartet). |
| M5 | VPN-Apps (2–3, vom Nutzer gewählt) | Jede App mit (a) LAN-Ausnahme und (b) ohne Ausnahme, im Hotspot-Modus und im WLAN-Modus. | Seite lädt je Kombination. Ergebnis (Ausnahme nötig, Sperre, kein Effekt) je App. |
| M6 | Gerätematrix | Android 8/9 (Stufe 2), 10/11, 12/13, 14–16, 17. Mindestens drei Hersteller. Honor Magic V2 (Foldable) ist Teil der Matrix. | LocalOnlyHotspot startet. Stufe richtig erkannt. Befund je Gerät. |
| M7 | Browser-Grenze (Abschnitt 5) | Chrome 76, 88; Safari 14, 15.0, 15.4 (iOS und macOS); Firefox 85, 89. Gäste-Seite mit Upload, Download, Raster, Fokus (Tabulator). | Alle Funktionen gehen. Fokusring ist sichtbar. Ergebnis je Version. |
| M8 | Lange Laufzeit | Zwei Stunden Hotspot mit Akku-Ausnahme. | Keine Pause länger als 2 Minuten (Schwelle aus PLAN-M3, „Offene Punkte nach 3.3“). |
| M9 | Ende und Netzwechsel | Gastgeber beendet den Hotspot. Gast verliert die Verbindung. | Gast sieht den Verbindungsverlust mit Erklärung (ADR 0014, Nachtrag endedAt). |

Gerätetests laufen durch Agenten, wenn das Gerät angeschlossen ist (CLAUDE.md). Der Testkatalog wird fortgeschrieben (TESTKATALOG-M3.md).

## 14. Offene Punkte

- Profilumzug und Nachfolge-Erklärung (Abschnitt 11)
- Gästezahl-Grenze je Netzweg (M4)
- Browser-Grenze final, nach Messung M7 (Abschnitt 5)
- Verschlüsselungsverfahren (Abschnitt 10)
- Feature-Check und Rückfallregel für `:focus-visible` (Abschnitt 5)
- NSD ja oder nein (Abschnitt 1, Nr. 4)
- QR-Format für Stufe 1 und die Versionierung zu `#i=` (Abschnitt 1, Nr. 6 und 7)
- Adresse des QR-Links bei fehlender App (Abschnitt 9)
- „Neue Gäste bestätigen“: Ablauf für die Warteschlange (Abschnitt 9)
- Transfer-Space und Trennung von Space-Speicher (Abschnitt 11)
- `dataSync` des Export-Workers (Abschnitt 1, Nr. 12)
- CLAUDE.md, „keine Konten“ und „jedes Android 8+“ (Abschnitt 1, Nr. 8 und 9)
- PLAN-M3, Paket 3.7, Release `v0.3.0` (Abschnitt 1, Nr. 11)
- iOS-App (Vorgabe, später)
- App-Verteilung ohne Play Store: APK, F-Droid prüfen (Vorgabe)
- README an das neue Zielbild anpassen (Vorgabe, später)

## Quellen (erwartet)

- Android-Referenz `WifiManager`: <https://developer.android.com/reference/android/net/wifi/WifiManager>
- Android-Referenz `WifiNetworkSpecifier`: <https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier>
- AOSP, STA/STA-Concurrency: <https://source.android.com/docs/core/connect/wifi-sta-sta-concurrency>
- MDN, `Blob.arrayBuffer()`: <https://developer.mozilla.org/en-US/docs/Web/API/Blob/arrayBuffer>
- MDN, `:focus-visible`: <https://developer.mozilla.org/en-US/docs/Web/CSS/:focus-visible>
- MDN, `aspect-ratio`: <https://developer.mozilla.org/en-US/docs/Web/CSS/aspect-ratio>

Die Android- und MDN-Seiten konnten für die Versionsangaben nicht vollständig gelesen werden. Die Versionen sind deshalb erwartet und in M7 bzw. gegen die Referenzseiten zu prüfen.
