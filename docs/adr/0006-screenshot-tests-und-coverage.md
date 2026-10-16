# ADR 0006: Screenshot-Tests und Coverage

Status: angenommen, 16.10.2026

## Kontext

Meilenstein 2 bringt das Design-System und die Galerie. Ohne Regressionsschutz fallen Theme-Änderungen erst beim Anschauen auf. ADR 0005 hat Goldens und ein Coverage-Ziel auf Meilenstein 2 verschoben. Dieses ADR legt beides fest.

Zwei Fragen waren offen:

- Ob Goldens, die unter Windows aufgenommen werden, in der CI (Linux) identisch verifizieren.
- Ob Kover mit AGP 9.4 und dem eingebauten Kotlin funktioniert oder ein Fallback auf JaCoCo nötig ist.

## Entscheidung

### Screenshot-Tests mit Roborazzi auf dem JVM

- Roborazzi 1.76.0 mit Robolectric 4.17 und `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. Die Tests laufen auf dem JVM, ohne Emulator und ohne Gerätezeit. Instrumentierte Screenshot-Tests (Paparazzi-Alternativen, Emulator) wären in der CI teuer, siehe ADR 0005.
- Goldens liegen je Modul in `src/test/screenshots` und werden committet. Namen beschreiben den Inhalt, z. B. `EmberlightApp_phone_de.png`, `GalleryPlaceholder_phone_en.png`.
- Abgedeckt sind: `EmberlightApp` auf dem Telefon (412×915 dp, en und de) und auf dem Tablet quer (900×600 dp, en, Rail sichtbar), der Galerie- und der Spaces-Platzhalter (en) sowie die Theme-Übersicht in `core:design` mit Farbflächen und Typografie-Probe.
- Die Tests sind deterministisch: keine Animationen und keine Uhrzeiten im Bild. Die Schriften liegen im APK.
- Verifikation: `roborazzi.test.verify=true` in `gradle.properties`. Jeder Testlauf, auch `check`, vergleicht gegen die Goldens. Ein fehlendes Golden lässt den Test fehlschlagen und erzeugt nichts. Neue oder geänderte Goldens entstehen nur über `./gradlew recordRoborazziDebug`.
- Bei Abweichung schreibt Roborazzi ein Compare-Bild nach `build/outputs/roborazzi`.

### Coverage mit Kover 0.9.11

- Kover auf allen Android-Modulen über den Convention-Plugin-Pfad, gemessen wird die Debug-Unit-Test-Variante. Die Aggregation und die Verifikation stehen im Root-Projekt.
- Ausgeschlossen ist nur generierter Code: `*_HiltModules*`, `*.Hilt_*`, `*_Factory`, `*_MembersInjector`, `*_GeneratedInjector`, `dagger.hilt.internal.*`, `hilt_aggregated_deps.*`, `*ComposableSingletons*`, `*.BuildConfig`, `*.R`, `*$$serializer`. Fach- und UI-Klassen bleiben drin. Compose-UI wird über die Robolectric-Tests mitgemessen.
- Untergrenze: 70 % Zeilen (`minBound(70, LINE)`). `koverVerify` hängt über `dependsOn` an `check`. Ein Unterschreiten bricht den Build.

### CI-Durchsetzung

- `ci.yml` führt `./gradlew check koverXmlReport assembleDebug assembleRelease` aus. `check` enthält die Screenshot-Verifikation und `koverVerify`.
- Der Schritt „Coverage und Screenshots zusammenfassen“ läuft mit `if: always()`. Er schreibt die Zeilenabdeckung aus dem Kover-XML und die Namen abweichender Screenshots nach `GITHUB_STEP_SUMMARY`. Die Summary steht außerdem im Log.
- Diff-Bilder werden nur bei Fehlschlag als Artefakt `screenshot-diffs` hochgeladen, mit `continue-on-error`. Artefakte sind kein Transportweg für Goldens.

## Messung: Windows gegen Linux

Goldens wurden lokal unter Windows 10 mit der JDK-21-Test-Toolchain aufgenommen und committet. Die CI (`ubuntu-24.04`) verifizierte sie ohne Anpassung.

| Lauf | Ergebnis |
|---|---|
| PR #5, Commit mit Goldens, `check` mit Verifikation ([Run 38877425442](https://github.com/Labushuya/emberlight/actions/runs/38877425442)) | grün, 0 abweichende Screenshots |
| Gegenprobe: `GlowOrange` in einem Wegwerf-Branch geändert ([Run 38878763624](https://github.com/Labushuya/emberlight/actions/runs/38878763624), per `workflow_dispatch`) | rot in `:app:testDebugUnitTest` und `:core:design:testDebugUnitTest`, drei App-Screenshots und die Theme-Übersicht betroffen |

Die Abweichung zwischen Windows und Linux ist für diese sechs Goldens null. Eine `changeThreshold` ist deshalb nicht nötig, der Vergleich läuft mit den Roborazzi-Standardoptionen. Ein Workflow `screenshots.yml` zum Aufnehmen auf Linux ist nicht nötig und wurde nicht angelegt. Stimmen Goldens in einem späteren Lauf nicht mehr überein, gilt dieser Befund nicht mehr. Dann wird das Verfahren neu bewertet.

Lokal arbeitet man so: `./gradlew recordRoborazziDebug` aufnehmen, die PNGs ansehen, committen. Maßgeblich für grün oder rot ist die CI.

## Coverage-Ist-Wert

Stand 16.10.2026, Root-Report (nur Debug-Variante):

| Modul | Zeilen gedeckt | Anteil |
|---|---|---|
| `app` | 54 von 56 | 96,4 % |
| `core:design` | 147 von 147 | 100 % |
| `feature:gallery` | 9 von 11 | 81,8 % |
| `feature:space` | 10 von 11 | 90,9 % |
| `core:data`, `core:media`, `share:*` | kein Code | – |
| **gesamt** | **220 von 225** | **97,8 %** |

Die Untergrenze bleibt bei 70 %. Beleg für den Fehlschlag: Bei 99 % bricht `koverVerify` mit „lines covered percentage is 97.777800, but expected minimum is 99“ ab.

Einschränkungen:

- Der Nenner ist klein (225 Zeilen). Die 70 % sagen für den heutigen Code wenig. Sie wachsen mit der Galerie.
- `koverPrintCoverage` auf Modulebene addiert die Release-Variante, die keine Tests hat. Diese Modulwerte sind nicht aussagekräftig. Maßgeblich ist der Root-Wert.

## Kover statt JaCoCo

Kover 0.9.11 läuft mit AGP 9.4.1 und dem eingebauten Kotlin. Es gab keinen Fehler, deshalb ist kein JaCoCo-Fallback nötig.

## Konsequenzen

- Jede UI-Änderung aktualisiert die Goldens im selben PR (CLAUDE.md). Die CI meldet die betroffenen Dateien.
- `gradle.properties` setzt die Verifikation für alle Module. Das ist bewusst, damit `check` nie still aufnimmt.
- Die CI-Laufzeit steigt durch Roborazzi-Tests und den Kover-Report. Vorher lief der Main-Push in 8 min 54 s ([Run 38874078601](https://github.com/Labushuya/emberlight/actions/runs/38874078601)). Der PR-Lauf mit Kover und Screenshots lief in 10 min 25 s ([Run 38877425442](https://github.com/Labushuya/emberlight/actions/runs/38877425442)). Beide Läufe sind einzelne Messungen mit Cache-Schwankungen.
- Die Coverage-Untergrenze ist nur mit begründeter ADR änderbar.

## Alternativen

- Paparazzi: nicht gewählt. Roborazzi arbeitet auf dem JVM mit Robolectric, wie die übrigen Tests. Ein zweites Test-Framework wäre unnötig.
- Instrumentierte Screenshot-Tests auf Emulator: verworfen, siehe ADR 0005.
- JaCoCo als Standard: nicht nötig, siehe oben.
- Goldens nur auf Linux aufnehmen (`screenshots.yml`): nicht nötig, da die Messung keine Abweichung zeigt.
- Untergrenze senken: nicht getan. Der Ist-Wert liegt darüber.

## Quellen

- Roborazzi, Gradle-Tasks und Optionen: https://github.com/takahirom/roborazzi (Abschnitte „Gradle tasks“ und „roborazzi { } DSL“).
- Roborazzi-Gradle-Plugin 1.76.0 auf Maven Central: https://repo1.maven.org/maven2/io/github/takahirom/roborazzi/roborazzi-gradle-plugin/1.76.0/roborazzi-gradle-plugin-1.76.0.pom
- Kover-Gradle-Plugin 0.9.11 auf Maven Central: https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kover-gradle-plugin/0.9.11/kover-gradle-plugin-0.9.11.pom
- Kover-Doku (Gradle-Plugin): https://kotlin.github.io/kotlinx-kover/gradle-plugin/
- Messläufe: [PR #5](https://github.com/Labushuya/emberlight/pull/5), Run 38877425442 (grün) und Run 38878763624 (Gegenprobe, rot).
