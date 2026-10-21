<div align="center">

<img src="docs/banner.png" alt="emberlight: eine glimmende Glut bei Nacht, aus der Funken und kleine Fotos aufsteigen" width="100%">

**Eine Galerie für deine Fotos und Videos. Und ein eigenes WLAN, über das alle, die gerade dabei sind, ihre Bilder teilen.**

<img alt="Status: in Entwicklung" src="https://img.shields.io/badge/Status-in%20Entwicklung-ff8a3d?style=flat-square">
<img alt="Android 8 oder neuer" src="https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
<img alt="Keine Cloud" src="https://img.shields.io/badge/Cloud-keine-2a1410?style=flat-square">
<img alt="Lizenz GPL-3.0-or-later" src="https://img.shields.io/badge/Lizenz-GPL--3.0--or--later-8b2635?style=flat-square">

</div>

## Was ist emberlight?

Nach einer Feier, einem Urlaub oder einem Abend mit Freunden liegen die Fotos auf zehn Handys verteilt. Geteilt
wird dann über WhatsApp-Gruppen, Cloud-Alben oder gar nicht. emberlight löst das vor Ort:

- **Space öffnen.** Du gibst ihm einen Namen, ein Titelbild, eine Farbe und eine Laufzeit, zum Beispiel „bis 2 Uhr nachts“.
- **QR-Code zeigen.** Wer ihn scannt, ist im Space. Gibt es kein gemeinsames WLAN, spannt emberlight ein eigenes auf.
- **Teilen.** Alle laden Fotos und Videos hoch und holen sich, was sie wollen. Gäste brauchen dafür keine App, der Browser reicht.
- **Vorbei.** Läuft die Zeit ab, schließt der Space. Jeder behält seine Kopien, nichts liegt auf fremden Servern.

Dazu kommt eine Galerie, die so schön und flüssig sein soll wie möglich.

| | |
|---|---|
| Galerie | Zeitleiste, Alben, Pinch-Zoom, Vollbild mit Zoom und Video, Infobereich, eigenes dunkles Design (keine Dynamic Colors) |
| Sharing-Space | Zeitlich begrenzt, Nachhaltefrist, Gäste per QR-Code |
| Netzwerk | Vorhandenes WLAN oder eigenes WLAN der App, kein Internet nötig |
| Datenschutz | Keine Konten, keine Cloud, kein Tracking, keine Google Play Services |

## Datenschutz

- **Keine Google Play Services.** Die App kommt ohne ML Kit, Firebase, Nearby oder andere Google-Dienste aus. Eine Build-Prüfung sorgt dafür (`checkNoPlayServices`).
- **Kein Tracking, keine Analytics, keine Konten.**
- **Netzwerk nur für den lokalen Space-Server.** Gäste verbinden sich mit ihm über das WLAN oder den Hotspot des Space. Es gibt kein Backend und kein Relais.
- **Standortdaten werden beim Teilen entfernt.**
- **Space und Galerie sind getrennt.** Medien eines Space liegen im app-internen Speicher. Nur was ausdrücklich ausgewählt und heruntergeladen wird, landet in der eigenen Galerie.

## Stand

- **Meilenstein 1, Grundgerüst:** abgeschlossen mit [v0.1.0](https://github.com/Labushuya/emberlight/releases/tag/v0.1.0).
- **Meilenstein 2, Galerie:** abgeschlossen mit [v0.2.0](https://github.com/Labushuya/emberlight/releases/tag/v0.2.0).
- **Meilenstein 3, Space im vorhandenen WLAN:** in Arbeit. Server-Basis, Space-Speicher und Standortentfernung sind umgesetzt, die Oberfläche für Spaces folgt. Der Paketplan steht in [docs/PLAN-M3.md](docs/PLAN-M3.md).
- **Meilenstein 4, Eigenes WLAN** und **Meilenstein 5, Beitritt per App:** geplant.

## Dokumentation

- [Konzept und Entscheidungen](docs/KONZEPT.md)
- [Architekturentscheidungen (ADR)](docs/adr/)
- [Hinweise für die Entwicklung mit Claude Code](CLAUDE.md)
- [Sicherheitsrichtlinie](SECURITY.md)

## Bauen

Voraussetzungen: JDK 17 für Gradle und den Build, Android SDK mit API 37. JDK 21 für die Unit-Tests lädt Gradle automatisch.

```sh
./gradlew check                 # Lint, ktlint, Unit-Tests, Screenshot-Prüfung, Coverage, Prüfung auf Play Services
./gradlew assembleDebug         # Debug-APK
./gradlew assembleRelease -Pemberlight.version=0.2.0   # Release-APK, Version ohne Property: 0.0.0-dev
```

Signierte Release-APKs brauchen eigene Schlüssel, die nie im Repository liegen. Die Anleitung steht in [docs/RELEASE.md](docs/RELEASE.md).

## Releases

Fertige, signierte APKs gibt es unter [Releases](https://github.com/Labushuya/emberlight/releases). Jede Datei hat eine `.sha256`-Datei mit der Prüfsumme. Vor der Installation bitte prüfen.

Die App wird per Sideloading verteilt.

## Lizenz

emberlight ist freie Software unter der **GNU General Public License, Version 3 oder später** (SPDX: `GPL-3.0-or-later`).
Der vollständige Lizenztext steht in der Datei [LICENSE](LICENSE).

```
emberlight
Copyright (C) 2026 Labushuya

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

## Drittanbieter

**Bibliotheken.** emberlight nutzt Bibliotheken anderer Projekte, unter anderem AndroidX, Jetpack Compose, Kotlin, Coil, Media3, Ktor, Room, Hilt, Telephoto, kotlinx und ZXing (QR-Codes, Kern-Bibliothek `com.google.zxing:core`, Version 3.5.4). Sie stehen unter der [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). ZXing: [Lizenz](https://github.com/zxing/zxing/blob/master/LICENSE). Die vollständige Liste mit Lizenzhinweisen steht in der App unter „Über & Lizenzen“ (Galerie, Überlaufmenü, drei Punkte). Sie entsteht beim Build aus den Abhängigkeiten der Release-Version mit [AboutLibraries](https://github.com/mikepenz/AboutLibraries) (Apache-2.0). Die Entscheidung steht in [ADR 0020](docs/adr/0020-lizenzhinweise-in-der-app.md).

**Schriften.** Die App liefert die Schriften Fraunces (Titel) und Inter (Text) mit. Beide stehen unter der [SIL Open Font License 1.1](https://openfontlicense.org/). Die Lizenztexte mit den Copyright-Zeilen liegen unter [`core/design/src/main/assets/licenses`](core/design/src/main/assets/licenses): [Fraunces](core/design/src/main/assets/licenses/fraunces_OFL.txt), [Inter](core/design/src/main/assets/licenses/inter_OFL.txt). Die Schriftdateien sind unverändert übernommen.

Die Schriften liegen als eigenständige Dateien neben dem Programmcode, sie sind kein Teil des GPL-Codes. Diese Einordnung ist eine Auskunft des Projekts und keine Rechtsberatung.
