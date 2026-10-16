# ADR 0001: Build und Modulstruktur

Status: angenommen, 15.10.2026

## Kontext

Galerie und Sharing sollen unabhängig wachsen (siehe [CLAUDE.md](../../CLAUDE.md), Abschnitt Struktur). Dafür braucht das Projekt eine Build-Konfiguration, in der Versionen an einer Stelle stehen, Module nicht per Copy-Paste konfiguriert werden und Verstöße gegen die harten Grenzen (keine Google Play Services) automatisch auffallen.

## Entscheidung

- Gradle 9.8.1 mit Kotlin-DSL (`*.gradle.kts`) und Wrapper.
- Alle Versionen im Version Catalog `gradle/libs.versions.toml`.
- Gemeinsame Einstellungen in Convention-Plugins im Included Build `build-logic` (Application, Library, Feature, Compose, Hilt). Module wenden nur die Plugins an, die sie brauchen.
- Modulschnitt: `app`, `core:design`, `core:data`, `core:media`, `feature:gallery`, `feature:space`, `share:server`, `share:network`. In Meilenstein 1 sind `core:data`, `core:media`, `share:server` und `share:network` nur ein Gerüst, damit spätere Meilensteine ohne Umbau der Gradle-Struktur auskommen.
- AGP 9.4.1 mit eingebautem Kotlin. Kotlin 2.4.21.
  - Das Update kam per Dependabot-PR #2. Lokal ohne Build-Cache und in der CI grün, ohne Compiler-Warnungen.
  - KSP 2.3.12 läuft mit Kotlin 2.4.21. KSP 2.3.10 enthält laut Release-Notes den Fix „Sanitize ':' in internal-name module suffix so KSP works with Kotlin 2.4.0 default module names“ ([KSP 2.3.10](https://github.com/google/ksp/releases/tag/2.3.10)). Eine ausdrückliche Freigabe von KSP 2.3.12 für Kotlin 2.4.21 fehlt. Das wird beobachtet.
  - Die geplante Bibliothek Coil 3.6.3 setzt `kotlin-stdlib` 2.4.10 voraus ([POM](https://repo1.maven.org/maven2/io/coil-kt/coil3/coil/3.6.3/coil-3.6.3.pom)). Ohne Kotlin 2.4.x hebt Gradle die stdlib auf 2.4.10 an, Compiler (2.3.x) und Stdlib lägen dann auseinander.
- Hilt 2.60.1, Annotation-Processing über KSP statt kapt. Dagger unterstützt KSP offiziell (stabil ab Dagger 2.60 und KSP 2.3.9). Das Plugin kotlin-kapt ist mit dem eingebauten Kotlin von AGP 9 laut Android-Doku nicht kompatibel, die Doku empfiehlt die Migration zu KSP.
- `minSdk` 26 (Android 8), `compileSdk` und `targetSdk` 37.
- Quelltext und Bytecode auf Java 17. Unit-Tests laufen auf JDK 21 (siehe ADR 0005).
- Lint bricht bei Fehlern und Warnungen ab (`warningsAsErrors`), ohne Baseline. Kotlin-Warnungen sind Fehler. Die Gegenprobe ist belegt: Eine unnötige Non-Null-Assertion bricht `:feature:gallery:compileDebugKotlin` und `:app:compileDebugKotlin` mit `-Werror`. Die Einstellung wirkt über `configureKotlinAndroid()` in den Convention-Plugins für Application und Library (`compilerOptions.allWarningsAsErrors`).
- ktlint 1.8.0 mit Compose-Regeln über Spotless.
- Task `checkNoPlayServices` läuft als Teil von `check` und bricht ab, wenn der Release-Classpath Play-Services-Artefakte enthält.

## Konsequenzen

- Neue Module und Einstellungen sind schnell eingebunden. Eine Änderung an den Compile-Optionen wirkt überall gleich.
- Strenge Lint- und Compiler-Regeln verlangen Disziplin, dafür fallen Fehler früh auf.
- Das Upgrade auf Kotlin 2.4.21 ist vollzogen. Hilt 2.60.1 und KSP 2.3.12 laufen darauf, die offizielle KSP-Freigabe für 2.4.21 fehlt noch (siehe oben).
- Mit AGP 9 wird das Kotlin-Android-Plugin nicht separat angewendet, weil AGP 9 Kotlin eingebaut hat (siehe Quellen).

## Wiedervorlage: sporadischer interner Lint-Fehler

- Symptom: ein interner Lint-Fehler „Error while resolving FirRegularClassImpl“ im Task `:app:lintAnalyzeDebugUnitTest`.
- Häufigkeit: 3 Vorkommen lokal. Danach wurde er in 10 weiteren Läufen nicht mehr reproduziert (0 von 10).
- Weiteres Vorkommen bei Paket 5a (Meilenstein 2): `:app:lintAnalyzeDebugUnitTest`, Datei `EmberlightAlbumNavigationTest.kt`. Mit `LINT_PRINT_STACKTRACE=true` zeigt der Stacktrace eine `KotlinIllegalArgumentExceptionWithAttachments` in `LLFirModuleLazyDeclarationResolver`. Zwei Wiederholungen mit `--rerun-tasks` liefen ohne Fehler. Ein Vorkommen trat also mit Stacktrace auf und verschwand von selbst, die Ursache bleibt offen.
- Hypothese, nicht belegt: Lint 32.4.1 bettet einen Kotlin-Compiler mit stdlib 2.2.10 ein. Das Projekt nutzt Kotlin 2.4.21. Die Versionen passen nicht zusammen, das könnte den Fehler auslösen.
- Diagnose-Rezept: `LINT_PRINT_STACKTRACE=true` setzen. Ein Absturz zeigt dann den vollständigen Stacktrace. Die Variable ist in `ci.yml` und `release.yml` gesetzt, damit ein Wiederauftreten im CI-Log sichtbar ist.
- Maßnahme erst bei belegter Ursache. Ohne Stacktrace wird nichts geändert.

## Alternativen

- Groovy-DSL: verworfen, die Kotlin-DSL ist typsicher und passt zum Projekt.
- Ein einziges App-Modul: verworfen, es widerspricht dem Ziel, Galerie und Sharing getrennt wachsen zu lassen.
- Kotlin 2.3.x: zunächst gewählt, bis Hilt und KSP mit Kotlin 2.4 gebaut waren. Mit dem Update auf 2.4.21 überholt.
- kapt statt KSP: verworfen. Kotlin-kapt ist laut Android-Doku mit dem eingebauten Kotlin von AGP 9 nicht kompatibel, und Dagger unterstützt KSP offiziell.

## Quellen

- Android-Doku, Migration zu eingebautem Kotlin (kapt, KSP): https://developer.android.com/build/migrate-to-built-in-kotlin
- Dagger-Doku, KSP-Unterstützung: https://dagger.dev/dev-guide/ksp
