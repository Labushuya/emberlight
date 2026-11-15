# Handover: emberlight

## 1. Zweck und Stand

Dieses Dokument ist der Einstieg für neue Claude-Sessions. Es fasst zusammen, wo das Projekt steht, worauf der Nutzer wartet und was offen ist.

Stand: 15.11.2026

> Diese Datei bei Abschluss eines Pakets aktualisieren.

Vor größeren Änderungen zuerst [CLAUDE.md](../CLAUDE.md), [KONZEPT.md](KONZEPT.md) und für Meilenstein 4 [KONZEPT-M4.md](KONZEPT-M4.md) lesen.

## 2. Projekt und Stand

emberlight ist eine Android-App (Kotlin, Jetpack Compose) mit zwei Teilen: einer Galerie und einem lokalen Sharing-Space. Ein Gastgeber öffnet einen zeitlich begrenzten Space im lokalen Netz, Gäste treten per QR-Code im Browser bei und tauschen Fotos und Videos aus. Es gibt keinen Server, keine Cloud, keine Konten und keine Google-Play-Dienste. Lizenz: GPL-3.0-or-later.

Aktueller Stand:

- **Galerie:** stabil in `v0.2.0` (Latest).
- **Meilenstein 3** (Space im vorhandenen WLAN): abgeschlossen am 25.10.2026 laut [PLAN-M3.md](PLAN-M3.md), Abschnitt „Abschluss Meilenstein 3“. Vorab-Version `v0.3.0-rc.1` ist seit 25.10.2026 als Pre-release online. Ein finales `v0.3.0` gibt es erst nach dem Endtest. `v0.3.0-alpha.1` nicht mehr verwenden.
- **Meilenstein 4** (Netz, Hotspot, Gast-Modus): Konzept und Plan liegen vor, [KONZEPT-M4.md](KONZEPT-M4.md) und [PLAN-M4.md](PLAN-M4.md) (vorläufig, wird nach den Netlab-Messungen angepasst). Ziel ist `v0.4.0`.
- **Netlab** (Paket 4.0, PR #63, am 25.10.2026 gemergt): eigene Mess-App („emberlight Netlab“) für Hotspot, Gast-Verbindung und Kamera. Sie ist nicht Teil der emberlight-App. Auf einem echten Gerät ist sie bisher nicht gelaufen.

## 3. Ordner und Arbeitsweise

- **Hauptklon:** `H:\DEV\github\emberlight` (Branch `main`). Dort committet und pusht der Nutzer selbst.
- **Claude-Worktree:** `H:\DEV\#claude\emberlight` (Ruhe-Branch `claude/work`). Claude arbeitet nur hier. Pro Aufgabe ein Branch von aktuellem `origin/main`, dann PR. Claude merged nie selbst.
- **Build-Artefakte:** `H:\DEV\#claude\emberlight-builds`.
- **Umstellung am 15.11.2026:** Das Projekt war bisher ein eigenständiger Klon. Er wurde auf Hauptklon und Worktree umgestellt. Der alte Klon liegt unverändert in `H:\DEV\_backups\emberlight-klon-20261115`.
- **Release-Flow:** kein release-please. Der Workflow `.github/workflows/release.yml` läuft bei Tags `vX.Y.Z` oder Vorab-Tags (`-alpha.N`, `-beta.N`, `-rc.N`). Die Monotonie des `versionCode` wird gegen die veröffentlichten Releases geprüft. Details, Secrets und Fehlerbilder stehen in [RELEASE.md](RELEASE.md). Tags setzt der Nutzer, sie werden nicht verschoben.
- **Regeln:** Doku, README und Commit-Beschreibungen auf Deutsch, Code und Bezeichner auf Englisch. Commits im Conventional-Commits-Format. `main` ist geschützt, Merge per Squash durch den Nutzer. Pflicht-Check ist „Build, Lint und Tests“ (`ci.yml`). UI-Änderungen brauchen aktualisierte Screenshot-Goldens im selben PR. Jedes Paket schreibt den Testkatalog fort.

## 4. Wartet auf den Nutzer

### a) Netlab-Messung

Die APK liegt unter `H:\DEV\#claude\emberlight-builds\emberlight-netlab-8b80efe.apk`.

**Ehrlich:** Auf einem echten Handy ist Netlab noch nie gelaufen. Hotspot, Gast-Verbindung und Kamera lassen sich nur dort prüfen. Genau dafür ist die Messung da.

**Grenzen des Prototyps:**

- Der Hotspot läuft nur, solange Netlab **geöffnet** bleibt.
- Die Browser-Testseite meldet nur „Verbunden“, Uploads im Browser misst sie noch nicht.
- Für die Gästegrenze (vermutet um 10) reichen die Geräte nicht aus. Die bleibt offen.

**Geräteliste des Nutzers:**

- HONOR Magic v2
- Samsung Galaxy Note 20 Ultra 5G
- Sony Xperia 1 IV
- HTC OnePlus 3T Midnight Black
- Samsung Galaxy Note 3 (zu alt für die Installation von Netlab, testet nur den Browser)
- Sony Xperia Arc (eigentlich nicht mehr verwendbar, nicht Teil der Messung)

**Vorbereitung:** Die APK auf Honor, Xperia 1 IV, Note 20 und OnePlus 3T kopieren und installieren. Das Note 3 testet nur den Browser. Alternativ installiert Claude Netlab per USB, wenn die Handys nacheinander angeschlossen werden und USB-Debugging aktiv ist.

**Ablauf (etwa 30 bis 45 Minuten):**

1. **Honor als Gastgeber:** WLAN und mobile Daten an. In Netlab „Gastgeber“ wählen, dann „Hotspot starten“. Netlab offen lassen.
2. **Xperia als App-Gast:** mobile Daten an. „Gast“ wählen, den App-QR vom Honor scannen, verbinden und „Messungen starten“. Drei Durchläufe.
3. **Note 20:** wie Schritt 2.
4. **OnePlus 3T:** wie Schritt 2. Netlab zeigt hier die Zugangsdaten. Damit von Hand in den WLAN-Einstellungen verbinden, dann zurück in Netlab und messen.
5. **Browser-Test (wichtigste Frage):**
   1. Auf dem Note 20 Netlab schließen, mobile Daten an lassen.
   2. Mit der Kamera den WLAN-QR vom Honor scannen und verbinden, dann den Browser-QR scannen.
   3. Zeigt die Seite „Verbunden“?
6. **Note 3:** Das WLAN von Hand mit den Daten vom Honor verbinden. Dann die Adresse unter dem Browser-QR in Chrome eintippen. Zeigt die Seite „Verbunden“?
7. **Am Ende:** Auf jedem Handy mit Netlab „Bericht teilen“ wählen und den Text in die Claude-Session einfügen. Name und Passwort des WLANs sind darin unkenntlich gemacht. Für Schritt 5 und 6 reicht das Ergebnis: Ja oder Nein.

Falls auf einem Handy eine VPN-App installiert ist: Schritt 2 auf diesem Handy einmal mit eingeschaltetem VPN wiederholen.

### b) Entscheidung zu den CLAUDE.md-Vorschlägen 1–6

Status: **offen**. Der Nutzer entscheidet, ob alle, einzelne oder anders übernommen werden. Die Doku-Nachzüge dazu (PR #62) sind gemergt: Konzept M4 mit den Entscheidungen zu Profil und Browser, Plan M3 als abgeschlossen, Plan M4 als vorläufig.

Vorgeschlagene Änderungen an `CLAUDE.md`:

1. „Keine Konten“ wird zu „kein Online-Konto“. Ein lokales Profil mit eigenem Schlüssel ist erlaubt.
2. „Läuft auf jedem Android 8+“ wird zu: Zielgruppe sind moderne Handys. Android 8 und 9 bekommen einen eingeschränkten Fallback. Die Mindestversion bleibt Android 8.
3. Meilenstein-Übersicht: Beitritt per App und Verschlüsselung rücken in Meilenstein 4, wie im neuen Plan.
4. Projektaufbau: die Mess-App Netlab kommt dazu. Automatisches Finden des Gastgebers (mDNS) wird als offen markiert.
5. Trennung von Space und Galerie: klarstellen, dass der Profilumzug (Transfer-Space) nicht über den Speicher der Spaces läuft.
6. Testkatalog: für Meilenstein 4 kommt ein neuer Katalog (`TESTKATALOG-M4.md`). Der alte bleibt für rc.1 bestehen.

### c) Endtest rc.1

Priorität laut letzter Absprache: **nach Netlab**. Der Testkatalog steht in [TESTKATALOG-M3.md](TESTKATALOG-M3.md), Abschnitt „Endtest in Kürze“. Kurzfassung der Schritte mit dem Honor als Gastgeber und einem zweiten Handy:

1. **Standort 9.7:** Honor-Kamera mit Standort ein Foto machen. Im Browser hochladen und „Standort entfernen“ wählen. In der Gastgeber-App darf keine Markierung „Standort enthalten“ stehen. Das Foto im Browser herunterladen und in der Galerie unter Details prüfen: kein Ort.
2. **Faltdisplay 4.28:** „Space starten“ im aufgeklappten Zustand antippen. Es muss sofort reagieren.
3. **Start abbrechen 4.29:** Einen Space beenden und gleich einen zweiten starten. Der zweite wartet. „Start abbrechen“ tippen: Er wird wieder Entwurf.
4. **Übernahme in die Galerie 11.1, 11.2, 11.14:** Im Space „In Galerie übernehmen“ wählen. Die Auswahl muss Vorschaubilder zeigen. Einige Fotos übernehmen. In der Galerie liegen sie unter `Emberlight/<Space>`. Noch einmal übernehmen: Es dürfen keine Doppelten entstehen.
5. **Aufgeklappt 11.11:** Die Übernahme auch auf dem Innendisplay testen.
6. **Beendeter Space 10.41:** Ein Foto antippen, es muss sich öffnen.
7. **Sonst:** Teilnehmerzahl (5.16), Gäste-Seite mit vielen Fotos flüssig (6.22).

Offen dazu: Welche **Chrome-Version** ist auf dem Note 3 installiert? Chrome, Einstellungen, Über Google Chrome.

## 5. Offene technische Punkte

Stand der Status: 15.11.2026, mit `gh` geprüft.

- **PR #66** (Dependabot Gradle, 15 Updates): rot. „Build, Lint und Tests“ bricht nach 3 s ab, „Tests und Lint“ nach 22 min. „APK bauen“ ist grün. Ursache noch nicht analysiert.
- **PR #67** (Dependabot Actions, 2 Updates): grün, alle drei Checks bestanden. Wartet auf Merge durch den Nutzer.
- **spike/m3-server:** Ktor-Spike, PR #23 am 18.10.2026 geschlossen, Branch liegt auf origin. Ob er bleibt, entscheidet der Nutzer. Nicht löschen ohne Rückfrage.
- **Netlab-Grenzen:** Hotspot nur bei geöffneter App, Browser-Uploads nicht gemessen, Gästegrenze (~10) offen.
- **Release-Notizen rc.1:** Zwei Korrekturen stehen noch aus. Das Insta360-Beispiel bei der Standort-Bereinigung ist nicht belegt. Bei der Übernahme in die Galerie steht „eines beendeten Space“, die Übernahme geht aber auch bei laufenden Spaces.
- **README:** nennt noch `v0.3.0-alpha.2` als aktuelle Pre-release und Paket 3.7 als nächsten Schritt. Das passt nicht mehr zum Stand rc.1.

## 6. Nächste Schritte

1. Netlab-Berichte der Geräte nach Eingang auswerten. Ergebnisse in [KONZEPT-M4.md](KONZEPT-M4.md) gegen die Messkriterien (Abschnitt 13) abgleichen.
2. Danach Meilenstein 4 laut [PLAN-M4.md](PLAN-M4.md). Die Pakete 4.1 bis 4.10 sind Vorschläge und werden mit dem Messbericht überarbeitet. Start mit Paket 4.1 (QR-Format v2 und Einladung offline).
3. Endtest rc.1 durch den Nutzer, nach Netlab (siehe 4c).
4. Entscheidung des Nutzers zu den CLAUDE.md-Vorschlägen (siehe 4b). Danach die Änderungen in einem eigenen PR umsetzen.

## 7. Quellen

- Die bisherige Hauptsession lief mit cwd `H:\DEV`. Transkript `2e4d5592-383a-4d97-b66c-1c5388baef2d` im Claude-Projektordner „H--“.
- Relevante Dokumente im Repo: [CLAUDE.md](../CLAUDE.md), [KONZEPT.md](KONZEPT.md), [KONZEPT-M4.md](KONZEPT-M4.md), [PLAN-M3.md](PLAN-M3.md), [PLAN-M4.md](PLAN-M4.md), [TESTKATALOG-M3.md](TESTKATALOG-M3.md), [RELEASE.md](RELEASE.md).
