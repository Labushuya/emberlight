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

## Nachtrag Paket 5a: Viewer-Goldens in eigener Testaufgabe

Die Goldens des Vollbild-Viewers (ADR 0010) laufen in der Aufgabe `testViewerGoldens` des Moduls `feature:gallery` in einem gemeinsamen Prozess (Standard von Gradle, `forkEvery` nicht gesetzt). Die übrigen Tests des Moduls laufen wie bisher in `testDebugUnitTest`, die Viewer-Goldens sind dort ausgeschlossen. `check` hängt an beiden Aufgaben.

### Befund: weiße Viewer-Aufnahmen im Hauptlauf (Stand 16.10.2026)

- Symptom: Im Lauf von `testDebugUnitTest` mit eingeschlossenen Viewer-Goldens scheitern `ViewerGermanScreenshotTest`, `ViewerImmersiveScreenshotTest` und der Chrome-Test von `ViewerScreenshotTest`. Die Ist-Aufnahme ist fast weiß (`#FAFAFA`, Standardhintergrund), das Golden ist schwarz mit Inhalt.
- Allein laufen alle vier Viewer-Klassen im selben Prozess grün, in vier Wiederholungen.
- Gesamtlauf: In drei vollständigen Läufen scheiterten dieselben drei Klassen, `ViewerGermanScreenshotTest`, `ViewerImmersiveScreenshotTest` und der Chrome-Test von `ViewerScreenshotTest`.
- Teilmengen, jeweils mit den Viewer-Goldens:
  - Die fünf Galerie-Wurzelklassen einzeln, und die Hälfte mit Galerie- und Album-Tests (14 Klassen): Fehler.
  - Die Hälfte mit `timeline`-Tests und den übrigen Viewer-Tests (21 Klassen): nur `ViewerImmersiveScreenshotTest` scheitert.
  - Kleine Mengen mit zwei oder drei Klassen waren nicht stabil. `DurationFormatTest` mit den Viewer-Goldens scheiterte einmal dreimal identisch, danach lief dieselbe Menge grün. `ZoomMathTest`, `ViewerFormatsTest` und `DurationFormatTest` mit einzelnen Viewer-Klassen sind in anderen Läufen grün.
- Geprüft und verworfen als alleinige Ursache: `TestThumbnails` setzt den globalen Coil-Singleton-Loader. Alle Klassen, die ihn setzen, rufen `reset()` auf. Die Viewer-Goldens bekommen ihre Bilder aus `solidImageSources` und nicht über Coil. Das Leeren des Looper per `flushMainLooper()` ist vorhanden, reicht aber nicht.
- Nicht geprüft: Robolectric-Sandbox-Wiederverwendung und der Zustand von Choreographer und Looper über Klassen hinweg. Ob ein Test eine Sandbox übernimmt, hängt von der Konfiguration ab (Immersive und `ViewerScreenshotTest` teilen `en-w412dp-h915dp`). Vermutung, nicht belegt: Der Fehler hängt an der Zahl der vorher gelaufenen Compose-Tests im Prozess.
- Ergebnis: Die Ursache ist nicht gefunden. Der Gesamtlauf reproduziert den Fehler zuverlässig, kleine Teilmengen nicht. Es handelt sich also um einen Zustand oder um Timing im gemeinsamen Robolectric-Prozess, der durch die Menge der vorher gelaufenen Tests beeinflusst wird.

### Lösung: ein gemeinsamer Prozess für alle Viewer-Klassen

- Vorher: vier Prozesse, eine je Klasse (`forkEvery = 1`). Gemessen mit `--rerun`: 26,3 s und 27,0 s.
- Nachher: ein Prozess für alle vier Klassen, `forkEvery` entfällt. Gemessen: 7,4 s und 8,3 s. Alle fünf Viewer-Goldens sind gegen die committeten Bilder grün.
- Der Hauptlauf bleibt ohne Viewer-Goldens. Eine Rückführung in `testDebugUnitTest` ist nicht möglich, solange die Ursache offen ist.

### CI-Parallelität

- Gradle-Eigenschaft `emberlight.testForks` (Standard 1, gültig 1 bis 4) setzt `maxParallelForks` für alle Test-Tasks. Lokal bleibt es bei 1.
- `ci.yml` setzt `-Pemberlight.testForks=2` für den Build-Schritt, passend zu den 4 vCPU des Runners. Der Schritt „Toolchains anzeigen“ entfällt, er war nur eine Ausgabe.
- Speicher, lokal gemessen mit `check koverXmlReport assembleDebug assembleRelease checkNoPlayServices --rerun-tasks -Pemberlight.testForks=2` auf einem 31-GB-Rechner: Die Summe der Working-Sets aller `java`-Prozesse lag höchstens bei 8,75 GB (fünf Prozesse), der freie RAM fiel minimal auf 4,3 GB. Die CI-Runner haben 16 GB. Die Spitze liegt darunter, mit Puffer für das Betriebssystem. Der Wert ist eine lokale Messung, keine CI-Messung.
- Messung in der CI, Schritt „Build, Lint und Tests“ laut Gradle-Summary:
  - Vorher: PR-Run 38932178168 mit 16 min 43 s, main-Run 38934614930 mit 17 min 20 s. Der Job lief 18 min 18 s (PR) bzw. 19 min 40 s (main).
  - Nachher: PR-Run 38943495690 mit 17 min 36 s, Job rund 18 min 6 s. Kein messbarer Effekt. Der Toolchain-Schritt entfiel, aber der Build-Schritt wurde entsprechend länger.
  - Zeitverteilung im Lauf (aus den Zeitstempeln des Logs): Start und Kompilieren bis zu den Tests rund 3 bis 4 min, Unit-Tests rund 5 min, Lint und R8 für den Release-Build am Ende rund 9 min. R8 brauchte in der CI 4 min 26 s, lokal 43 s. Auf 4 vCPU laufen die Tasks deutlich langsamer als auf dem lokalen Rechner mit 16 Kernen.
  - Das Ziel von höchstens 12 min ist mit diesen Maßnahmen nicht erreicht.
- Der Configuration Cache wurde in der CI nicht wiederverwendet. Ohne Schlüssel speichert setup-gradle den Projektzustand gar nicht. Ursache und Umstellung stehen im Abschnitt „CI-Caches für Robolectric und Configuration Cache“ unten.

## CI-Caches für Robolectric und Configuration Cache (16.10.2026)

### Ausgangslage

- Robolectric lädt pro Lauf das Jar `android-all-instrumented-17-robolectric-15733970-i7.jar` für SDK 37 (238.744.924 Byte) nach `~/.m2/repository/org/robolectric/android-all-instrumented/`. setup-gradle cacht `~/.m2` nicht.
- Der Configuration Cache wurde nie wiederverwendet. Gradle meldete in jedem Lauf „Configuration cache entry stored.“, das ist aber nur der lokale Eintrag auf dem Runner. setup-gradle v6.4.0 schreibt den Projektzustand nur mit `cache-encryption-key` und nur, wenn sein Projektzustand-Status aktiv ist (siehe Befund unten). Quelle: `sources/src/caching-report.ts` im Tag v6.4.0: „Project state (build-logic and configuration cache) was not cached - a [cache-encryption-key] is required.“

### Umsetzung

- **Robolectric:** `actions/cache` v6.1.0, Commit `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` (Tag v6.1.0 ist ein leichter Tag, er zeigt direkt auf diesen Commit). Pfad `~/.m2/repository/org/robolectric/android-all-instrumented`, Schlüssel `robolectric-<Version>-sdk<Test-SDK>-<OS>`. Version und SDK liest der Workflow aus `gradle/libs.versions.toml` und `EmberlightConfig.kt`. Ein Versionswechsel ergibt einen neuen Schlüssel. `release.yml` liest nur (`actions/cache/restore`).
- **Verworfen:** Robolectric im Offline-Modus mit über Gradle aufgelösten Jars (`robolectric.offline`, `robolectric.dependency.dir`). Das verlangt eine zusätzliche Auflösung im Build-Logik-Modul. Das Jar läge dann im Gradle-Cache, den setup-gradle bei jedem Job lädt. Gewählt wurde der eigene Cache-Eintrag, er braucht keine Änderung am Build und koppelt den Schlüssel direkt an Version und SDK.
- **Configuration Cache:** `cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}` in `ci.yml` und `release.yml`. Die Doku von v6.4.0 sagt: „The `cache-encryption-key` parameter allows you to provide a base64-encoded AES key to encrypt configuration-cache data stored in the GitHub Actions cache.“ Als Beispiel nennt sie `openssl rand -base64 16`, also 16 Byte. Im Quelltext des Tags (`gradle-user-home.ts`) wird der Wert als `GRADLE_ENCRYPTION_KEY` exportiert. Eine Längenprüfung fand sich dort nicht.
- **Secret `GRADLE_ENCRYPTION_KEY`:** am 16.10.2026 angelegt. 16 Byte aus `RandomNumberGenerator`, Base64 (24 Zeichen). Übergabe per stdin aus einer Datei ohne Zeilenumbruch, die Datei wurde sofort gelöscht. Der Wert wurde nirgends ausgegeben. Der Schlüssel dient nur der Verschlüsselung des Configuration-Cache-Eintrags im GitHub-Cache. Er signiert nichts und ist kein Zugangsdatum. Ein späterer Wechsel bedeutet einen einmaligen Miss.
- **Zweite Bedingung (Befund, 16.10.2026):** Der Schlüssel ist notwendig, aber nicht hinreichend. Die Quelle von v6.4.0 (`cache-service.ts`, `caching-report.ts`) führt einen eigenen Status für den Projektzustand. Ohne `develocity-access-key` und `develocity-server-url` (Develocity-Caching-Trial) bzw. ohne einen versteckten Opt-in-Schalter ist der Status `not-enabled` oder `trial-not-licensed`. Dann wird der Projektzustand nicht gespeichert, auch mit Schlüssel. Die Job-Zusammenfassung zeigt dafür keine Zeile. Das passt zur Beobachtung unten. Ein Schalter wurde nicht gesetzt, weil die Aktivierung an ein Trial-Token gebunden ist. Das ist eine Entscheidung des Betreibers.
- **Einschränkung:** setup-gradle schreibt den Cache nur auf `main`. PR-Läufe lesen nur (Doku: „By default, The `setup-gradle` action will only write to the cache from Jobs on the default (`main`/`master`) branch.“). Ein PR nutzt den Configuration Cache daher erst, wenn `main` einen Eintrag geschrieben hat. Der Robolectric-Cache ist davon nicht betroffen, `actions/cache` speichert auf dem eigenen Branch.

### Messung PR #13 (Commit b8acc22)

Zwei Läufe auf demselben Commit: Versuch 1 (Run 38955527735) und der Rerun desselben Laufs als Versuch 2.

| Größe | Versuch 1 | Versuch 2 (Rerun) |
|---|---|---|
| Robolectric-Cache | Miss („Cache not found“), Speichern im Nachgang 3 s | Treffer („Cache restored from key“), Wiederherstellen 5,0 s |
| Testtask `:app:testDebugUnitTest` | 50,1 s (inkl. Download des Jars) | 41,0 s |
| Schritt „Build, Lint und Tests“ | 185 s | 177 s |
| Zeit bis „Calculating task graph“ | 5,1 s | 5,9 s |
| Configuration Cache | nicht gelesen, auf dem PR nicht gespeichert | nicht gelesen |

- Robolectric: Der Testtask spart 9,1 s, das Wiederherstellen kostet 5,0 s. Netto etwa 4 s. Das sind Einzelmessungen auf dem Runner, die Streuung ist nicht bekannt.
- Lokal gemessen: Der Download des Jars von Maven Central dauert 3,2 s (curl, 238,7 MB). Ein Kaltlauf der Navigationsklasse dauerte 17 s, ein warmer 11 bis 13 s. Der Nutzen ist also klein. Der Cache macht die Tests zusätzlich unabhängig von Maven Central.
- Der Configuration Cache wurde auf dem PR nicht wiederverwendet. Die Einschränkung oben erklärt das, die zweite Bedingung ebenfalls.
- Messung auf `main` nach dem Merge (Run 38956818696, Versuch 1 und Rerun als Versuch 2): Der erste Lauf schrieb Robolectric-Cache („Cache saved“) und Configuration-Cache-Eintrag (`cache-read-only: false`). Der Rerun las den Robolectric-Cache („Cache restored from key“), aber nicht den Configuration Cache: erneut „Calculating task graph“. Der Build-Schritt lag bei 183 s und 66 s. Im Rerun kam `:app:testDebugUnitTest` komplett aus dem Gradle-Build-Cache (`FROM-CACHE`). Dort misst die Robolectric-Zeit nichts. Die Robolectric-Messung oben am PR gilt.
- Konfigurationszeit auf `main` (Gradle-Start bis „Calculating task graph“): 6,8 s und 5,2 s. Damit gibt es keinen messbaren Unterschied zu den PR-Läufen und zu den Läufen ohne Schlüssel.
- Die Build-Dauer von 177 bis 185 s liegt weit unter den 16 bis 18 min früherer PR-Läufe. Der Grund ist der Gradle-Build-Cache aus dem Gradle-Home-Cache von `main`: Im Log stehen 345 Treffer `FROM-CACHE` gegenüber 121 im früheren Lauf 38943495690. Das ist nicht Teil dieser Änderung, sondern ein Zustand des Caches.

### Offen

- Configuration Cache mit Treffer: nicht erreicht. Der Schlüssel allein genügt nicht (zweite Bedingung oben). Es braucht eine Entscheidung des Betreibers: entweder Develocity-Zugang mit Caching-Trial einrichten, oder den Configuration Cache in der CI ohne Wiederverwendung lassen. Das Secret `GRADLE_ENCRYPTION_KEY` ist angelegt. Solange die zweite Bedingung fehlt, hat es keine Wirkung.
- `release.yml`: Die neuen Schritte (Robolectric-Restore, Schlüssel) sind nicht über einen Lauf geprüft. Ein Probelauf per Dispatch wurde nicht ausgeführt, weil die Freigabe dafür fehlte.

## Nachtrag: Aufnahme und Verifikation der Viewer-Goldens (17.10.2026)

Die Lösung im Nachtrag Paket 5a hat einen Fehler. Die Viewer-Goldens waren nicht verifiziert. Dieser Abschnitt ersetzt dafür den Stand dort.

### Befund

- Roborazzi setzt `roborazzi.test.verify` und `roborazzi.test.record` nur auf `testDebugUnitTest` (Gradle-Plugin 1.76.0). `testViewerGoldens` bekam beides nicht. Ein absichtlich falsches Golden ließ den Test grün durchlaufen. Die Aussage „alle fünf Viewer-Goldens sind gegen die committeten Bilder grün“ oben war deshalb keine Verifikation.
- `recordRoborazziDebug` nahm die Viewer-Goldens nicht auf. Aufgenommen wurde nur mit einer temporären Systemproperty.
- Mit aktiver Verifikation schlug der gemeinsame Prozess fehl. Die Aufnahmen einzelner Tests waren leer (`#FAFAFA`). Welche Tests betroffen waren, wechselte zwischen Läufen. Je Klasse in einem eigenen Prozess liefen alle Tests grün. Die Ursache im Robolectric-Zustand ist weiterhin nicht geklärt, die Isolation je Klasse ist die Behandlung.
- Die Golden-Dateien waren keine Eingaben der Testaufgaben. Ein Test blieb nach einer Golden-Änderung `UP-TO-DATE`, und der Vergleich lief nicht.

### Lösung

- `testViewerGoldens` (Verifikation, von `check` und `verifyRoborazziDebug` abhängig): setzt `roborazzi.test.verify=true` und `roborazzi.test.record=false` explizit.
- `recordViewerGoldens` (Aufnahme, von `recordRoborazziDebug` abhängig): setzt `roborazzi.test.record=true` und `roborazzi.test.verify=false`.
- Beide Aufgaben laufen mit `forkEvery = 1`, je Viewer-Klasse ein Prozess. Der Lauf dauert 44 bis 48 s statt 7 bis 8 s. Der Mehraufwand ist die Stabilität wert.
- Das Golden-Verzeichnis ist in `configureScreenshotTests()` (`AndroidCommon.kt`) als optionale, relative Eingabe aller `Test`-Aufgaben eingetragen.
- Aufnahme: `./gradlew recordRoborazziDebug` (Galerie-Goldens und Viewer-Goldens). Prüfung: `./gradlew check` bzw. `./gradlew verifyRoborazziDebug`. Ein normaler Lauf nimmt nie auf. Die Aufnahme setzt nur der Aufnahme-Task.

### Belege

- Golden entfernt, `:feature:gallery:recordRoborazziDebug`: Datei wieder da, byte-identisch zu HEAD. Alle übrigen Goldens der Galerie ebenfalls unverändert.
- Golden gegen ein anderes PNG getauscht, `testViewerGoldens`: rot (`peekStageWithHighlightedTile`). Nach Rücktausch grün.
- Alle neun Viewer-Tests mit den committeten Goldens: grün, je Klasse ein Prozess, zwei Läufe.
