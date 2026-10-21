# CLAUDE.md: emberlight

Android-App: Galerie plus lokaler Sharing-Space (eigenes WLAN oder vorhandenes WLAN, Gäste per QR-Code, auch per Browser).
Was entschieden ist und warum, steht in [docs/KONZEPT.md](docs/KONZEPT.md). Lies es vor jeder größeren Änderung.

## Harte Grenzen

- **Nur lokal.** Kein Backend, kein Relais, keine Cloud-Dienste, keine Konten, kein Tracking, keine Analytics.
- **Keine Abhängigkeit von Google Play Services** (kein ML Kit, kein Nearby, kein Firebase). Die App wird per Sideloading verteilt und soll auf jedem Android 8+ laufen.
- Nichts einbauen, was einen späteren Play-Store-Umzug verhindert. Der In-App-Updater kommt später in einen eigenen Build-Flavor.
- **Strikte Trennung von Space und Galerie.** Ein Space gibt nur Medien preis, die ausdrücklich in ihn geteilt wurden. Die private Galerie ist für Server und Teilnehmer nie erreichbar. Space-Medien liegen nur im app-internen Space-Speicher; in eine Galerie gelangt nur, was ausdrücklich ausgewählt und heruntergeladen wurde. Technische Durchsetzung ab Meilenstein 3: `share:server` darf über keine auflösbare Konfiguration `core:media`, `:feature:*` oder `:app` erreichen (Build-Prüfung `checkServerIsolation`, transitiv). Der Quelltext von `share:server` darf keine Galerie-Medien- oder Speicherzugriffe enthalten (Build-Prüfung `checkServerSources`). Auslieferung nur aus dem Space-Speicher, Tests dafür.

## Technik (Vorgabe, begründet abweichen ist erlaubt)

- Kotlin, Jetpack Compose, Material 3 mit eigenem Theme (dunkel, warm, siehe `docs/banner.png`)
- Gradle Kotlin DSL mit Version Catalog (`gradle/libs.versions.toml`)
- `minSdk 26`, `targetSdk` und `compileSdk` aktuell
- applicationId: `io.github.labushuya.emberlight` (entschieden, siehe [ADR 0004](docs/adr/0004-versionierung-und-release.md))
- Hilt, Room, kotlinx.serialization, Coroutines/Flow
- Bilder und Video-Vorschauen: Coil 3; Wiedergabe: Media3/ExoPlayer
- Eingebetteter Server: Ktor Server (CIO)
- QR-Codes erzeugen und scannen: ZXing (core) plus CameraX
- Gäste-Webseite: statisch, im App-Assets-Ordner, ohne Build-Kette im Browser; schlank, responsiv, Deutsch und Englisch

## Struktur

Modular aufbauen, damit Galerie und Sharing unabhängig wachsen:

- `app`: Einstieg, Navigation, Theme
- `core:*`: z. B. `core:media` (MediaStore-Zugriff), `core:design`, `core:data`
- `feature:gallery`, `feature:space`
- `share:server` (Ktor, Gäste-Web), `share:network` (LocalOnlyHotspot, NSD/mDNS)

## Release und Versionen

- SemVer. Version kommt aus dem Git-Tag `vX.Y.Z` oder `vX.Y.Z-(alpha|beta|rc).N`. `versionName` ist die Version ohne `v`, `versionCode = (X*10000 + Y*100 + Z)*100 + Stufe` (ADR 0004, Nachtrag).
- Conventional Commits (`feat:`, `fix:`, `chore:` …), damit Release-Notes daraus entstehen können.
- `.github/workflows/ci.yml`: bei Push und PR bauen, Lint, Unit-Tests.
- `.github/workflows/release.yml`: bei Tag `vX.Y.Z` oder Vorab-Tag signierte Release-APK bauen, als `emberlight-vX.Y.Z.apk` benennen, SHA-256 berechnen, GitHub-Release mit APK, `.sha256`-Datei und Prüfsumme in den Notes anlegen. Vorerst in diesem Repo, später im öffentlichen `emberlight-releases`.
- Signierung über Repository-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Keystore niemals committen. Fehlen die Secrets, bricht der Release-Job mit klarer Meldung ab.
- Probelauf per Dispatch (`workflow_dispatch`, Version eingeben): baut, signiert und prüft, legt aber kein Release und keinen Tag an. Zertifikats-Pinning über die Repository-Variable `RELEASE_CERT_SHA256`. Anleitung: [docs/RELEASE.md](docs/RELEASE.md).

## Befehle

- `./gradlew check`: Spotless/ktlint, Lint, Unit-Tests, Screenshot-Verifikation, Coverage-Untergrenze (`koverVerify`), Tests der Convention-Plugins und `checkNoPlayServices`
- `./gradlew recordRoborazziDebug`: Screenshot-Goldens bewusst neu aufnehmen, die Viewer-Goldens eingeschlossen (Ablage `src/test/screenshots`, Details in [ADR 0006](docs/adr/0006-screenshot-tests-und-coverage.md)). `./gradlew verifyRoborazziDebug`: nur prüfen, Viewer-Goldens eingeschlossen. `check` verifiziert immer und nimmt nie auf.
- `./gradlew koverHtmlReport` bzw. `./gradlew koverXmlReport`: Coverage-Report (HTML unter `build/reports/kover/html`)
- `./gradlew assembleDebug`: Debug-APK
- `./gradlew assembleRelease -Pemberlight.version=X.Y.Z`: Release-APK. Ohne Property gilt `0.0.0-dev` mit versionCode 1.
- Signierte Release-APK: Umgebungsvariablen `EMBERLIGHT_KEYSTORE_FILE`, `EMBERLIGHT_KEYSTORE_PASSWORD`, `EMBERLIGHT_KEY_ALIAS`, `EMBERLIGHT_KEY_PASSWORD` setzen. Mit `-Pemberlight.requireReleaseSigning=true` bricht der Build ab, wenn eine fehlt.

## Arbeitsweise

- Repo ist öffentlich. `main` ist geschützt: Änderungen nur per PR, grüne CI „Build, Lint und Tests“ ist Pflicht, kein Force-Push, kein Löschen. Merge per Squash.
- Dokumentation, README und Commit-Beschreibungen auf Deutsch, Code und Bezeichner auf Englisch.
- Alle Nutzertexte in `strings.xml` (de und en), keine fest eingebauten Texte.
- Nach jedem Meilenstein: bauen, testen, kurz in `docs/KONZEPT.md` nachtragen, wenn sich eine Entscheidung geändert hat.
- UI-Änderungen brauchen aktualisierte Screenshot-Goldens im selben PR.
- Coverage-Untergrenze: 70 % Zeilen (Kover, in CI erzwungen); nicht ohne begründete ADR senken.
- Gemergte Branches und vom Assistenten angelegte Test-Branches dürfen ohne Rückfrage gelöscht werden (Freigabe 17.10.2026). Tags, `main` und fremde Branches nie ohne Rückfrage.
- Kumulierter Testkatalog in [docs/TESTKATALOG-M3.md](docs/TESTKATALOG-M3.md). Jedes Paket schreibt ihn fort, gegliedert nach Funktionen.
- Paket-PRs werden bei grüner CI gemergt (Freigabe der Nutzerin bzw. des Nutzers vom 21.10.2026).
- Endtest durch die Nutzerin bzw. den Nutzer beim Vorab-Release (Testkatalog in `docs/TESTKATALOG-M3.md`).
- Gerätetests durch Agenten, wenn das Gerät angeschlossen ist.
- Chrome bzw. Edge für Browser-Tests nur headless starten (`--headless=new`), auch nicht `--version` ohne Headless-Flags.
- Gradle-Temp für Agenten einheitlich `C:/Users/Christopher/AppData/Local/Temp/emberlight-work/gradle-tmp` (`GRADLE_OPTS=-Djava.io.tmpdir=…`), vermeidet viele Daemons.

## Meilensteine

1. **Grundgerüst**: Gradle-Projekt, Module, Theme, Navigation, CI und Release-Workflow. Ergebnis: `v0.1.0` baut als signierte APK.
2. **Galerie**: MediaStore-Zugriff mit Berechtigungen (inkl. „ausgewählte Fotos“ ab Android 14), Zeitleiste mit Pinch-Zoom der Spalten, Alben, Vollbild mit Wischen/Zoom, Video-Wiedergabe, flüssige Übergänge.
3. **Space im vorhandenen WLAN** (Paketplan: [docs/PLAN-M3.md](docs/PLAN-M3.md)):
   - Space anlegen (Name, Titelbild, Farbe, Laufzeit, Nachhaltefrist, Speicherlimit)
   - Beitritt mit Anzeigenamen
   - Ktor-Server im Foreground-Service, QR-Code
   - Gäste-Webseite mit Upload, Download und Uploader-Anzeige
   - getrennter Space-Speicher, Ablauf erzwingen
   - Schutz gegen Manipulation (Details als ADR)
   - GPS-Entfernung beim Upload
   - Nachhalten mit Erinnerung und automatischer Löschung
   - gezielte Übernahme ausgewählter Medien in `Pictures/Emberlight/<Space>`
4. **Eigenes WLAN**: LocalOnlyHotspot, WLAN-QR-Code plus Beitritts-URL, Hinweise für Gäste ohne Internet, Fallbacks bei Geräten ohne Unterstützung.
5. **Beitritt per App**: QR-Scan, nativer Space-Bildschirm für Mitglieder.
6. Danach: Rechte verfeinern, In-App-Updater, öffentliches Releases-Repo, TLS im fremden WLAN.
