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

### Deterministische Screenshot-Tests

Die Regeln gelten für alle Goldens, die neuen wie die alten:

- Kein Touch, um einen Zustand zu wechseln. Ein Antippen löst eine Ripple-Animation aus, das Bild wäre zeitabhängig. Stattdessen Semantik-Aktionen (`performSemanticsAction`) oder direkte Zustandswechsel im Test.
- Vorschaubilder kommen aus einem Fake-ImageLoader (`TestImageLoader`, `coil-test`). Kein echtes Laden, keine Dateizugriffe, keine Wartezeit auf Bilder.
- Kein `Thread.sleep`.
- Keine laufenden Animationen im Bild.
- Ein vermeintliches Flackern war ein veraltetes Golden, kein instabiler Test.

### Coverage mit Kover 0.9.11

- Kover auf allen Android-Modulen über den Convention-Plugin-Pfad, gemessen wird die Debug-Unit-Test-Variante. Die Aggregation und die Verifikation stehen im Root-Projekt.
- Ausgeschlossen ist nur generierter Code: `*_HiltModules*`, `*.Hilt_*`, `*_Factory`, `*_MembersInjector`, `*_GeneratedInjector`, `dagger.hilt.internal.*`, `hilt_aggregated_deps.*`, `*ComposableSingletons*`, `*.BuildConfig`, `*.R`, `*$$serializer`. Fach- und UI-Klassen bleiben drin. Compose-UI wird über die Robolectric-Tests mitgemessen.
- Untergrenze: 70 % Zeilen (`minBound(70, LINE)`). `koverVerify` hängt über `dependsOn` an `check`. Ein Unterschreiten bricht den Build.

### CI-Durchsetzung

- `ci.yml` führt `./gradlew check koverXmlReport assembleDebug assembleRelease` aus. `check` enthält die Screenshot-Verifikation und `koverVerify`.
- Der Schritt „Coverage und Screenshots zusammenfassen“ läuft mit `if: always()`. Er schreibt die Zeilenabdeckung aus dem Kover-XML und die Namen abweichender Screenshots nach `GITHUB_STEP_SUMMARY`. Die Summary steht außerdem im Log.
- Diff-Bilder werden nur bei Fehlschlag als Artefakt `screenshot-diffs` hochgeladen, mit `continue-on-error`. Artefakte sind kein Transportweg für Goldens.

### Unit-Tests je Variante

- Release-Unit-Tests sind abgeschaltet. Das geschieht in der Convention über `androidComponents.beforeVariants`, für alle Android-Module. R8 greift in Unit-Tests nicht, die Release-Variante würde also nur die Laufzeit verdoppeln, ohne etwas zu prüfen. Beleg: `:app:testReleaseUnitTest` und `:core:media:testReleaseUnitTest` sind im Task-Graph nicht vorhanden (Gradle: „not found“).
- Gelaufen werden weiterhin `testDebugUnitTest` aller Module, `finalizeTestRoborazziDebug` (Screenshot-Verifikation) und `koverVerify`, belegt durch den vollen Lauf dieses PR.

### Android-Ressourcen für Robolectric in allen Modulen

Robolectric lädt das Test-Manifest und die Android-Ressourcen eines Moduls nur mit `testOptions.unitTests.isIncludeAndroidResources = true`. Ohne das Flag läuft der Test auf einem Fallback-SDK. Gemessen mit einem Probe-Test, der `Build.VERSION.SDK_INT` ausgibt:

| Modul | ohne Flag | mit Flag |
|---|---|---|
| `core:media` | 23 (drei Tests der Gateway-Anbindung schlagen fehl) | 37 |
| `app`, `core:design`, `feature:gallery`, `feature:space` | 37 (Flag ist dort schon gesetzt) | 37 |

Die Lösung ist zentral: `configureUnitTestResources()` in `AndroidCommon.kt` setzt das Flag in `AndroidLibraryConventionPlugin` und `AndroidApplicationConventionPlugin`. Die Einzelstellen in Feature-Plugin, App und `core:media` sind entfallen. Das Probe-Ergebnis mit dem Flag für alle Module: SDK 37 in allen fünf Modulen mit Robolectric-Tests. `core:data` und `share:*` haben keine Robolectric-Tests.

### Screenshot-Befund: Navigationsleiste

Ein früherer Versuch hatte das Flag für alle Library-Module gesetzt und dabei eine Farbänderung des Galerie-Icons (orange statt blau) beobachtet. Das ließ sich nicht reproduzieren. Mit dem Flag für alle Libraries nimmt `recordRoborazziDebug` alle sechs Goldens byte-identisch zu den committeten auf. Die Goldens sind damit nicht falsch und werden nicht neu aufgenommen.

Die Pixelwerte der Navigationsleiste im Telefon-Golden stammen alle aus dem Schema. Ein blauer Ton kommt nicht vor:

| Element | Farbe | Token |
|---|---|---|
| Leiste | `#1C1317` | `surfaceContainer` |
| Indikator-Pille | `#4A3020` | `secondaryContainer` |
| ausgewähltes Icon | `#FFE0B8` | `onSecondaryContainer` |
| ausgewähltes Label | `#FFC27A` | `secondary` |
| nicht ausgewähltes Label | `#C9A88D` | `onSurfaceVariant` |

Im Code gibt es keine Farbüberschreibung für die Navigationsleiste. Die Icons sind einfarbige Vektoren ohne `android:tint`, die Compose-Icon-Tönung kommt aus der Content-Farbe der Leiste. Die Ursache der beobachteten Abweichung bleibt offen. Eine Golden-Änderung gibt es nicht.

### Speicher für den lokalen Build

- `org.gradle.jvmargs=-Xmx4g` (unverändert) und `kotlin.daemon.jvmargs=-Xmx3g` in `gradle.properties`. Der Kotlin-Daemon lebt zwischen Builds und hielt gemessen bis zu 1,7 GB. Ohne Obergrenze würde er bis zum JVM-Standard (1/4 des RAM) wachsen.
- Test-Tasks: Gradle-Standard (ein Fork, 512 MB Heap). Gemessen erreichten die Test-Worker etwa 0,8 GB RSS, die Tests laufen darunter. Eine Anpassung ist nicht nötig.
- Gemessene Spitze eines vollen Laufs (`check koverXmlReport assembleDebug assembleRelease checkNoPlayServices --rerun-tasks`) auf einem 31-GB-Rechner: Java-Prozesse zusammen 6,6 GB RSS, freier RAM minimal 8,9 GB. Ein Speicherabbruch trat im Lauf nicht auf. Der Engpass davor kam von einem veralteten Gradle-Daemon mit 7 GB RSS. `./gradlew --stop` hat ihn beseitigt.
- Die CI-Runner haben 16 GB und 4 vCPU. Die Werte passen dorthin. Die Gradle-Worker-Zahl wird nicht begrenzt, da sie auf 4 vCPU ohnehin 4 ist.

## Messung: Windows gegen Linux

Goldens wurden lokal unter Windows 10 mit der JDK-21-Test-Toolchain aufgenommen und committet. Die CI (`ubuntu-24.04`) verifizierte sie ohne Anpassung.

| Lauf | Ergebnis |
|---|---|
| PR #5, Commit mit Goldens, `check` mit Verifikation ([Run 38877425442](https://github.com/Labushuya/emberlight/actions/runs/38877425442)) | grün, 0 abweichende Screenshots |
| Gegenprobe: `GlowOrange` in einem Wegwerf-Branch geändert ([Run 38878763624](https://github.com/Labushuya/emberlight/actions/runs/38878763624), per `workflow_dispatch`) | rot in `:app:testDebugUnitTest` und `:core:design:testDebugUnitTest`, drei App-Screenshots und die Theme-Übersicht betroffen |

Die Abweichung zwischen Windows und Linux ist für diese sechs Goldens null. Eine `changeThreshold` ist deshalb nicht nötig, der Vergleich läuft mit den Roborazzi-Standardoptionen. Ein Workflow `screenshots.yml` zum Aufnehmen auf Linux ist nicht nötig und wurde nicht angelegt. Stimmen Goldens in einem späteren Lauf nicht mehr überein, gilt dieser Befund nicht mehr. Dann wird das Verfahren neu bewertet.

Lokal arbeitet man so: `./gradlew recordRoborazziDebug` aufnehmen, die PNGs ansehen, committen. Maßgeblich für grün oder rot ist die CI.

## Coverage-Ist-Wert

Stand 16.10.2026, Root-Report (nur Debug-Variante), nach Einführung von `core:media`:

| Modul | Zeilen gedeckt | Anteil |
|---|---|---|
| `app` | 54 von 56 | 96,4 % |
| `core:design` | 147 von 147 | 100 % |
| `core:media` | 255 von 273 | 93,4 % |
| `feature:gallery` | 9 von 11 | 81,8 % |
| `feature:space` | 10 von 11 | 90,9 % |
| `core:data`, `share:*` | kein Code | – |
| **gesamt** | **475 von 498** | **95,4 %** |

Die Zeilen von `core:media` verteilen sich auf die Pakete `index`, `store`, `model` und `permission`. Ungedeckt sind `di` (9 Zeilen: der `@Provides`-Dispatcher, den nur Hilt aufruft) und `log` (2 Zeilen: `LogcatMediaLogger` mit `android.util.Log`).

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
