# ADR 0001: Build und Modulstruktur

Status: angenommen, 15.10.2026

## Kontext

Galerie und Sharing sollen unabhängig wachsen (siehe [CLAUDE.md](../../CLAUDE.md), Abschnitt Struktur). Dafür braucht das Projekt eine Build-Konfiguration, in der Versionen an einer Stelle stehen, Module nicht per Copy-Paste konfiguriert werden und Verstöße gegen die harten Grenzen (keine Google Play Services) automatisch auffallen.

## Entscheidung

- Gradle 9.8.1 mit Kotlin-DSL (`*.gradle.kts`) und Wrapper.
- Alle Versionen im Version Catalog `gradle/libs.versions.toml`.
- Gemeinsame Einstellungen in Convention-Plugins im Included Build `build-logic` (Application, Library, Feature, Compose, Hilt). Module wenden nur die Plugins an, die sie brauchen.
- Modulschnitt: `app`, `core:design`, `core:data`, `core:media`, `feature:gallery`, `feature:space`, `share:server`, `share:network`. In Meilenstein 1 sind `core:data`, `core:media`, `share:server` und `share:network` nur ein Gerüst, damit spätere Meilensteine ohne Umbau der Gradle-Struktur auskommen.
- AGP 9.4.1 mit eingebautem Kotlin. Kotlin 2.3.21 statt 2.4.x, weil Hilt und KSP mit 2.3.21 nachweislich getestet sind.
- Hilt 2.60.1, Annotation-Processing über KSP statt kapt.
- `minSdk` 26 (Android 8), `compileSdk` und `targetSdk` 37.
- Quelltext und Bytecode auf Java 17. Unit-Tests laufen auf JDK 21 (siehe ADR 0005).
- Lint bricht bei Fehlern und Warnungen ab (`warningsAsErrors`), ohne Baseline. Kotlin-Warnungen sind Fehler.
- ktlint 1.8.0 mit Compose-Regeln über Spotless.
- Task `checkNoPlayServices` läuft als Teil von `check` und bricht ab, wenn der Release-Classpath Play-Services-Artefakte enthält.

## Konsequenzen

- Neue Module und Einstellungen sind schnell eingebunden. Eine Änderung an den Compile-Optionen wirkt überall gleich.
- Strenge Lint- und Compiler-Regeln verlangen Disziplin, dafür fallen Fehler früh auf.
- Ein Upgrade auf Kotlin 2.4.x ist ein eigener Schritt, erst wenn Hilt und KSP ihn tragen.
- Mit AGP 9 wird das Kotlin-Android-Plugin nicht separat angewendet.

## Alternativen

- Groovy-DSL: verworfen, die Kotlin-DSL ist typsicher und passt zum Projekt.
- Ein einziges App-Modul: verworfen, es widerspricht dem Ziel, Galerie und Sharing getrennt wachsen zu lassen.
- Kotlin 2.4.x: verworfen, Hilt und KSP sind damit nicht getestet.
- kapt statt KSP: verworfen, Hilt empfiehlt KSP.
