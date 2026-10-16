# ADR 0005: Tests

Status: angenommen, 15.10.2026

## Kontext

Die Oberfläche soll ohne Gerät testbar sein. Robolectric 4.17 läuft auf dem JVM und wird im Projekt tatsächlich genutzt: Gradle löst `org.robolectric:robolectric:4.17` aus Maven Central auf (siehe Quellen). Die Test-Umgebung nutzt JDK 21. Die Robolectric-Release-Notes nennen JDK 21 für SDK 36 (Version 4.16). Für SDK 37 ist das nicht gesondert belegt, die Festlegung wird bei Updates geprüft. Der Build selbst bleibt auf JDK 17. Instrumentierte Tests auf einem Emulator würden in der CI die 2.000 Actions-Minuten pro Monat (GitHub Free, private Repositories) belasten.

## Entscheidung

- Unit-Tests laufen mit einer JDK-21-Toolchain als Test-Launcher. Compiler und Lint bleiben auf JDK 17 (siehe ADR 0001).
- In der CI stellt `actions/setup-java` JDK 21 bereit, Gradle findet es ohne Download. Lokal lädt Gradle JDK 21 über die Toolchain-Auflösung (foojay) automatisch.
- Espresso 3.7.0 wird fest gesetzt. Die von `ui-test` gezogene Version 3.5 ist im Projekt mit SDK 37 gescheitert (Projektbeobachtung, nicht extern belegt).
- Tests in Meilenstein 1:
  - `EmberlightColorContrastTest`: WCAG-Kontrast der Farben.
  - `EmberlightAppNavigationTest`: Navigation zwischen den Top-Level-Zielen.
  - `MainActivitySmokeTest`: Start der Activity mit Hilt.
  - Compose-Tests der Platzhalter-Screens in `feature:gallery` und `feature:space`.
- Screenshot-Tests (Goldens) kommen ab Meilenstein 2, wenn das Design-System steht. **Erledigt in Meilenstein 2:** Roborazzi-Goldens und Verifikation in `check`, Coverage-Untergrenze 70 % Zeilen (Kover, in CI erzwungen). Details und Messung in [ADR 0006](0006-screenshot-tests-und-coverage.md).

## Konsequenzen

- Zwei JDKs sind nötig. Der Test-Lauf dauert etwas länger.
- Robolectric und SDK-Version müssen zusammenpassen. Bei Updates prüft der Test-Lauf das.
- Ein Testgerät oder Emulator ist für die CI nicht nötig.

## Komplexitätstests ohne Wanduhr (Nachtrag 16.10.2026)

Die Laufzeit-Tests `MediaIndexPerformanceTest.buildGrowsLinearlyBetween10kAnd50k` (core:media) und `groupingReadsEachItemOnceBetween10kAnd50k` (feature:gallery, Datei `TimelineGroupingTest`) haben das Verhältnis zweier Wanduhr-Messungen geprüft. Ein Lauf unter Last hat das einmal scheitern lassen. Seither gilt:

- Geprüft wird die Anzahl der Operationen, nicht die Zeit. Die Zählung hängt nur von den Eingaben und vom Sortieralgorithmus des JDK ab, nicht von der Last der Maschine.
- `MediaIndex.build` und `mergeSorted` nehmen einen optionalen `order`-Comparator entgegen (Standard `TIMELINE_ORDER`). Der Test setzt einen zählenden Comparator ein. Produktiver Aufruf bleibt unverändert.
- Gruppierung (`TimelineGrouping.group`) liest die Eingabe über eine zählende Liste. Lineares Verfahren heißt: jedes Element genau einmal lesen.
- Schranken für 10.000 und 50.000 Einträge: Vergleiche höchstens 1,5 · n · log2(n) und Verhältnis 50k zu 10k höchstens 7,0. Erwartet sind linear 5,0, n log n etwa 5,9, quadratisch 25,0. Gemessen: 121.038 und 723.703 Vergleiche, Verhältnis 5,98, und die Gruppierung liest 10.000 bzw. 50.000 Elemente.
- Die gemessene Dauer bleibt im Testlog (`println`), geht aber in keine Assertion ein.

## Alternativen

- Nur JDK 17: verworfen, die Tests laufen mit JDK 21 (siehe Kontext).
- Instrumentierte Tests auf Emulator in der CI: verworfen, zu teuer für den Free-Plan.
- Goldens sofort in Meilenstein 1: verworfen, sie würden jedes Design-Detail festschreiben, bevor das Design-System existiert.

## Quellen

- GitHub-Doku, Actions-Minuten im Free-Plan (2.000 Minuten pro Monat für private Repositories): https://docs.github.com/en/billing/concepts/product-billing/github-actions
- Robolectric 4.17 auf Maven Central (POM erreichbar, HTTP 200, geprüft 15.10.2026): https://repo1.maven.org/maven2/org/robolectric/robolectric/4.17/robolectric-4.17.pom
- Projektbeleg: `./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath` listet `org.robolectric:robolectric:4.17` (Repository: `mavenCentral()` in `settings.gradle.kts`).
