# CLAUDE.md: emberlight

Android-App: Galerie plus lokaler Sharing-Space (eigenes WLAN oder vorhandenes WLAN, Gäste per QR-Code, auch per Browser).
Was entschieden ist und warum, steht in [docs/KONZEPT.md](docs/KONZEPT.md). Lies es vor jeder größeren Änderung.

## Harte Grenzen

- **Nur lokal.** Kein Backend, kein Relais, keine Cloud-Dienste, keine Konten, kein Tracking, keine Analytics.
- **Keine Abhängigkeit von Google Play Services** (kein ML Kit, kein Nearby, kein Firebase). Die App wird per Sideloading verteilt und soll auf jedem Android 8+ laufen.
- Nichts einbauen, was einen späteren Play-Store-Umzug verhindert. Der In-App-Updater kommt später in einen eigenen Build-Flavor.

## Technik (Vorgabe, begründet abweichen ist erlaubt)

- Kotlin, Jetpack Compose, Material 3 mit eigenem Theme (dunkel, warm, siehe `docs/banner.png`)
- Gradle Kotlin DSL mit Version Catalog (`gradle/libs.versions.toml`)
- `minSdk 26`, `targetSdk` und `compileSdk` aktuell
- applicationId: `io.github.labushuya.emberlight` (falls Ashfall eine andere Konvention nutzt, dieser folgen und Don fragen)
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

- SemVer. Version kommt aus dem Git-Tag `vX.Y.Z`. `versionName = X.Y.Z`, `versionCode = X*10000 + Y*100 + Z`.
- Conventional Commits (`feat:`, `fix:`, `chore:` …), damit Release-Notes daraus entstehen können.
- `.github/workflows/ci.yml`: bei Push und PR bauen, Lint, Unit-Tests.
- `.github/workflows/release.yml`: bei Tag `v*.*.*` signierte Release-APK bauen, als `emberlight-vX.Y.Z.apk` benennen, SHA-256 berechnen, GitHub-Release mit APK, `.sha256`-Datei und Prüfsumme in den Notes anlegen. Vorerst in diesem Repo, später im öffentlichen `emberlight-releases`.
- Signierung über Repository-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Keystore niemals committen. Fehlen die Secrets, bricht der Release-Job mit klarer Meldung ab.

## Arbeitsweise

- Dokumentation, README und Commit-Beschreibungen auf Deutsch, Code und Bezeichner auf Englisch.
- Alle Nutzertexte in `strings.xml` (de und en), keine fest eingebauten Texte.
- Nach jedem Meilenstein: bauen, testen, kurz in `docs/KONZEPT.md` nachtragen, wenn sich eine Entscheidung geändert hat.

## Meilensteine

1. **Grundgerüst**: Gradle-Projekt, Module, Theme, Navigation, CI und Release-Workflow. Ergebnis: `v0.1.0` baut als signierte APK.
2. **Galerie**: MediaStore-Zugriff mit Berechtigungen (inkl. „ausgewählte Fotos“ ab Android 14), Zeitleiste mit Pinch-Zoom der Spalten, Alben, Vollbild mit Wischen/Zoom, Video-Wiedergabe, flüssige Übergänge.
3. **Space im vorhandenen WLAN**: Space anlegen (Name, Titelbild, Farbe, Laufzeit, Rechte), Ktor-Server im Foreground-Service, QR-Code, Gäste-Webseite mit Upload/Download, Ablauf erzwingen, Empfangenes in `Pictures/Emberlight/<Space>` speichern.
4. **Eigenes WLAN**: LocalOnlyHotspot, WLAN-QR-Code plus Beitritts-URL, Hinweise für Gäste ohne Internet, Fallbacks bei Geräten ohne Unterstützung.
5. **Beitritt per App**: QR-Scan, nativer Space-Bildschirm für Mitglieder.
6. Danach: Rechte verfeinern, In-App-Updater, öffentliches Releases-Repo, TLS im fremden WLAN.
