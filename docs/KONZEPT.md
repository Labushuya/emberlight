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
- **Wiederkehr** (Paket 3.4, [ADR 0018](adr/0018-einladung-und-beitritt.md)): Der Browser merkt sich den Gast für den Space. Wer die Seite erneut öffnet, bleibt angemeldet, solange der Token gilt. Ein Abmelden-Knopf löst die Sitzung im Browser und auf dem Server.
- **Einladung erneuern**: Der Gastgeber erneuert die Einladung. Alte QR-Codes und Links sind danach ungültig. Beigetretene Gäste bleiben.
- **Entfernte Gäste**: Der Zugang endet sofort, laufende Uploads brechen ab. Die Uploads bleiben und zeigen „von <Name> (entfernt)“. Beim Entfernen kann der Gastgeber auch alle Uploads des Gastes löschen. Der Gastgeber kann sich nicht selbst entfernen.
- **Grenzen**: Höchstens 50 aktive Teilnehmer, der Gastgeber mitgezählt. Namen haben 1 bis 24 Zeichen (Buchstaben, Ziffern, Marks, Leerzeichen und `- _ . '`). Namen sind pro Space eindeutig, ohne Beachtung von Groß- und Kleinschreibung. Ist ein Name vergeben, schlägt der Server einen freien Namen vor.

#### Rechte

- Während der Laufzeit darf jeder Teilnehmer alle Medien sehen und herunterladen sowie eigene hochladen.
- Die bisherigen Schalter „Hochladen/Herunterladen ja/nein“ pro Space entfallen.
- Teilnehmer dürfen eigene Uploads zurückziehen. Der Gastgeber darf beliebige Medien entfernen.
- **Gastgeber trägt selbst bei** (Paket 3.5c): Der Gastgeber kann während der Laufzeit Fotos und Videos aus seiner Galerie in den Space geben. Die Kopie läuft wie ein Gast-Upload: gleiche Grenzen, Standort-Entfernung mit Rückfrage je Datei, Dedupe und Speicherlimit. Uploader ist der Gastgeber. Hochladen geht nur im laufenden Space, nicht in der Pause und nicht nach dem Ende. Der Gastgeber darf alle Medien entfernen.
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

- Bei Ablauf oder vorzeitigem Beenden durch den Gastgeber ist der Space sofort beendet. Der Server antwortet noch 60 s lang mit `410` und der Stoppzeit, dann schließt er. Alle Zugangsschlüssel werden ungültig.
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

- **Voreinstellungen beim Anlegen** (Paket 3.2, [ADR 0016](adr/0016-space-verwaltung.md)): Farbe die erste der acht Akzentfarben, Laufzeit 1 Tag, Nachhaltefrist 1 Woche, Speicherlimit 4 GiB. Der Name ist Pflicht. Laufzeiten: 2 h, 6 h, 12 h, 1 Tag, 3 Tage, 1 Woche, Eigenes Ende (nur in der Zukunft, höchstens 30 Tage). Nachhaltefrist: Sofort löschen, 1 Tag, 1 Woche, Eigene (1 bis 90 Tage). Speicherlimit: 1, 2, 4, 8, 16 GiB, Eigenes (0,5 bis 64 GiB). Die Obergrenze auf dem Gerät gilt seit dem 20.10.2026 als harte Grenze, siehe Nachtrag unten.
- **Titelbild** über die System-Fotoauswahl. Das Bild wird in den Space-Speicher kopiert, mit Standort-Entfernung. Ein Bild, bei dem sich der Standort nicht entfernen lässt, wird abgewiesen. Es gibt keine Einwilligung für das Titelbild.
- **Laufzeit als Regel**: Eine Dauer bleibt Dauer, bis der Space in Paket 3.3 startet. Ein festes Ende ist ein Zeitpunkt.
- **Begriff „Aufbewahrung nach dem Ende“** (Nachtrag vom 20.10.2026): In der Oberfläche heißt die Nachhaltefrist jetzt „Aufbewahrung nach dem Ende“ (en: „Keep after the end“). Darunter steht: „Nach dem Ende bleiben die Medien nur bei dir in der App. Du wählst aus, was in deine Galerie soll; danach wird automatisch gelöscht.“ Der Begriff „Nachhaltefrist“ in diesem Konzept bleibt die fachliche Bezeichnung, interne Bezeichner ebenso.
- **Harte Obergrenze des Speicherlimits** (Nachtrag vom 20.10.2026): Die Obergrenze ist der freie Speicher des Geräts minus 2 GiB Puffer, abgerundet auf halbe GiB. Voreinstellungen darüber sind ausgegraut und mit dem Hinweis „Nicht genug freier Speicher, max. X GiB“ versehen. Ein eigener Wert darüber ist ein Fehler und sperrt das Speichern. Ist die Obergrenze kleiner als 0,5 GiB, lässt sich kein Space anlegen, das Formular erklärt den Grund. Die Prüfung wiederholt sich beim Speichern. Das Limit ist keine Reservierung von Speicher. Die Prüfung beim Start folgt in Paket 3.3.
- **Namensvorschläge** (Nachtrag vom 20.10.2026): Ein Würfel neben dem Namensfeld schlägt einen Namen aus Adjektiv und Substantiv vor, etwa „Tanzende Waschbären“ oder „Dancing Raccoons“. Die Wortlisten sind je Sprache hinterlegt. Ist das Feld leer, übernimmt der Würfel den Vorschlag sofort. Steht ein eigener Name darin, fragt die App vorher nach. Der Vorschlag bleibt editierbar.

- **Prozess-Tod und Neustart** (Nachbesserung 3.3b, 20.10.2026, [ADR 0017](adr/0017-einrichtung-und-betrieb.md)): Ein Prozess-Tod oder ein Abbruch durch das System beendet einen Space nicht. Der Dienst startet neu (`START_STICKY`) und setzt einen aktiven, nicht abgelaufenen Space am selben Port fort, wenn die Akku-Ausnahme besteht. Sonst bleibt der Space aktiv und gilt als „unterbrochen“, bis der Gastgeber ihn in der App fortsetzt. Die Unterbrechung ist eine Pause. Ein Space, dessen Frist während der Unterbrechung abläuft, endet zur Frist. Die frühere Regel „nach dem Neustart der App beendet“ entfällt. Sie hatte keine Umsetzung (ADR 0012 und 0014, Nachträge).
- **Teilnahme, Einladung und Beitritt** (Paket 3.4, [ADR 0018](adr/0018-einladung-und-beitritt.md), Nutzerentscheidungen 1 bis 5 vom 20.10.2026, gemäß Empfehlung, änderbar bis zum Merge): Beitritt per QR-Code oder Link mit Einladungsgeheimnis im Fragment, persönlicher Token nur in der Beitrittsantwort, nur als Hash gespeichert. Wiederkehr im Browser, Abmelden, Einladung erneuern, entfernte Gäste mit Vermerk, Grenzen (50 Teilnehmer, Namensregeln, Drosselung). Abweichungen vom Entwurf stehen im ADR, unter anderem: Vor dem Beitritt zeigt die Gäste-Seite keine Daten des Space.
- **Hinweis zur erneuerten Einladung** (Nachtrag 3.4b, 21.10.2026, [ADR 0018](adr/0018-einladung-und-beitritt.md)): Der Hinweis erscheint nur nach einer automatischen Erneuerung. Er verschwindet mit „Verstanden“ oder mit einer manuellen Erneuerung und kehrt bis zum nächsten Start nicht zurück.
- **Ende-Hinweise, Verlängern, Vorlage, Medienzugriff** (Nachtrag vom 21.10.2026, Umsetzung in den Paketen 3.5a und 3.6): Die Restzeit ist im Browser immer sichtbar. Gäste sehen eine Vorwarnung 10 Min. vorher (Banner) und 1 Min. vorher (Dialog). Nach dem Ende zeigt die Seite eine ganzseitige Ende-Ansicht, die sich merkt, wo der Gast war. Nach dem Ende antwortet der Server noch 60 s lang mit `410`, danach beendet er sich (Umsetzung in 3.5a; bis dahin gilt die kurze Nachfrist aus Paket 3.3). Verbindungsverlust erklärt die Seite in klarem Text. Der Gastgeber wird 10 Min. vor dem Ende und beim Ende benachrichtigt. Der Beenden-Dialog weist darauf hin. **Verlängern** ist während der Laufzeit möglich, Gäste sehen dann ein Banner. **„Als Vorlage für neuen Space“** übernimmt die Einstellungen nach dem Ende, aber keine Medien. Ein beendeter Space bleibt geschlossen (Nutzerentscheidung „a“, 21.10.2026). Medien im Browser werden nur über signierte, kurzlebige URLs ausgeliefert. Die Entscheidung und das ADR dazu entstehen mit Paket 3.5a.

- **Gastgeber-Upload und Medienansicht in der App** (Paket 3.5c, [ADR 0019](adr/0019-medienzugriff-und-vorschauen.md), Nachtrag 3.5c, 21.10.2026): Der Gastgeber fügt Medien über die System-Fotoauswahl hinzu, der Upload läuft über dieselbe Prüfung wie der Gast-Upload. Die Medienansicht im laufenden Space zeigt Vorschaubilder aus dem Space-Speicher, nie aus dem MediaStore. Auf dem Telefon gibt es eine Vault je Space im Prozess, damit Server und App dieselbe Sperre und Vorschau-Warteschlange nutzen.

## Offene Punkte

- Kompatibilität mit dem LocalSend-Protokoll: Interessant, aber kein MVP-Ziel.
- Moderne Galerie-Funktionen wie semantische Suche oder Gesichtsgruppierung, nur On-Device: nach dem MVP.
- Design-Richtung der App: Der Banner (`docs/banner.png`) gibt die Stimmung vor (dunkel, warme Glut, Funken), ist aber noch kein festes Design-System. Das Basis-Theme steht (ADR 0003), das Design-System entsteht ab Meilenstein 2.

## Änderungsnotiz

Änderungen am 16.10.2026: Space-Speicher getrennt von der Galerie, Rechte-Schalter entfallen, Nachhalten mit Frist.

17.10.2026: Fragen F–K entschieden. Nachhalten nur beim Gastgeber, Löschung auch ohne Reaktion, Erinnerung als Benachrichtigung mit Aktionen, GPS-Daten beim Upload standardmäßig entfernt, eigenes WLAN der App in fremden Netzen, Zurückziehen eigener Uploads und Entfernen durch den Gastgeber.

18.10.2026: Paket 3.0 abgeschlossen (Server-Basis, Foreground-Service, ADR 0012). Auf einem Honor-Gerät blieben mit Akku-Ausnahme allein die Anfragen im Akkubetrieb aus, mit zusätzlicher Hintergrund-Freigabe kamen sie durch (Beobachtung auf einem Gerät, eingetragen im Abschnitt Spaces, Betrieb).

20.10.2026: Entscheidungen der Nutzerin bzw. des Nutzers zu Paket 3.2 umgesetzt: Namensvorschläge, harte Obergrenze des Speicherlimits, Begriff „Aufbewahrung nach dem Ende“, Datum und Uhrzeit über Auswahldialoge. Die Designentscheidungen (a) bis (f) sind in [docs/mockups/README.md](mockups/README.md) festgehalten.
20.10.2026: Paket 3.3 (Einrichtung und Betrieb, ADR 0017). Nachtrag zum Abschnitt Spaces, Betrieb: Der Start verlangt Einrichtung (Pflicht: Lokales Netzwerk ab Android 37; empfohlen mit „Trotzdem starten“: Akku-Ausnahme, Hersteller-Freigabe, Benachrichtigungen). Pausen durch das System erkennt der Dienst über Herzschläge und meldet sie mit einem Banner. Das Ende prüft der Server bei jeder Anfrage, beim Herzschlag und über einen ungenauen Wecker. Bei einem WLAN-Wechsel pausiert der Server. Dasselbe Netz setzt er automatisch fort, ein anderes Netz bestätigt der Gastgeber in der App.

20.10.2026: Paket 3.4 (Einladung und Beitritt, ADR 0018). Nutzerentscheidungen 1 bis 5 vom 20.10.2026 gemäß Empfehlung, änderbar bis zum Merge: Beitritt, Wiederkehr im Browser, entfernte Gäste, Grenzen, Name vergeben. Der Abschnitt Spaces, Teilnahme, ist ergänzt. Der feste Port pro Space ist im ADR 0017 nachgetragen.

21.10.2026: Paket 3.4b (Nachbesserung). Die Einladung bleibt nach einem Neustart erhalten, solange das Geheimnis lesbar ist. Der Hinweis zur erneuerten Einladung verschwindet nach Bestätigung oder manueller Erneuerung. Die Pakete 3.5a bis 3.5c, 3.6 (Verlängern, Vorlage) und der Vorab-Release `v0.3.0-alpha.1` sind geplant (PLAN-M3.md). Der kumulierte Testkatalog steht in [TESTKATALOG-M3.md](TESTKATALOG-M3.md).
