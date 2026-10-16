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

## Nachtrag 16.10.2026: Deterministische App-Navigationstests

### Befund

- Die Navigationstests in `app` (`EmberlightAlbumNavigationTest`, `EmberlightViewerNavigationTest`, `EmberlightAppNavigationTest`) scheiterten auf dem CI-Runner sporadisch. Belege: Run 38930655676 mit vier Fehlschlägen in `EmberlightViewerNavigationTest` (Logs der Gradle-Ausgabe, Assertion je Test). Run 38946288317 Versuch 1 mit einem Fehlschlag. Dessen Fehlerlog ist nicht mehr abrufbar, der Befund stützt sich auf die Angabe des Auftrags.
- Die Fehlermeldung von Compose lautet jeweils „The component with (Text ... contains 'Camera' ...) is not displayed!“ bzw. mit „Other“. Die Assertion läuft direkt nach einem Klick. Der Knoten ist dann vorhanden, aber noch nicht an seiner Stelle sichtbar, weil der Navigationsübergang läuft.
- Der Test wartete nicht auf diesen Zustand. Nur `awaitNode` wartete auf die Existenz des Knotens, nie auf die Anzeige.

### Ursache

1. **Hauptursache, aus der Fehlermeldung abgeleitet:** Assertions auf Anzeige direkt nach einem Klick oder Zurück. Der Navigationsübergang (NavHost) und das Einblenden der Titelleiste (`AnimatedVisibility` mit `fadeIn`) laufen nach dem Klick noch. Ein Knoten kann dann im Baum stehen und trotzdem nicht sichtbar sein. Ein Gegenbeweis mit abgeschalteten Übergängen steht aus. Die Behebung (Warten auf die Bedingung) hat die Fehler in der Messung beseitigt.
2. **Mitwirkend, nicht isoliert:** Die Zeitleiste und das Album bilden den Zustand auf `Dispatchers.Default` ab (`MediaGridViewModel.viewState`). Die Galerie-Eingaben kommen aus zwei DataStore-Flows (`ZoomLevelStore`, `PermissionPromptStore`) mit echter Datei-I/O. Die Tests ersetzen den Default-Dispatcher nicht und die Stores ebenfalls nicht. Die Warte-Bedingungen decken diese asynchronen Schritte mit ab. Ein eigener Gegentest mit `Dispatchers.Unconfined` ist nicht gemacht worden, weil die Messung nach der Korrektur 0 Fehlschläge zeigt.

### Reproduktion

Lokal (Windows, 16 logische Kerne), je 10 Läufe der drei Klassen mit `--rerun` auf `:app:testDebugUnitTest`. Die Prozesse waren mit `Affinität 0xF` auf vier Kerne festgelegt, um den Runner (4 vCPU) nachzubilden:

| Stand | Bedingung | Läufe mit Fehlschlag | Fehlgeschlagene Tests |
|---|---|---|---|
| vorher | 16 Kerne, Standard-Zeitzone, 14 Last-Prozesse | 0 von 10 | 0 |
| vorher | 16 Kerne, UTC, `testForks=2`, 14 Last-Prozesse | 1 von 10 | 1 |
| vorher | 4 Kerne, UTC, `testForks=2`, ohne Last | 6 von 10 | 7 |
| nachher | 4 Kerne, UTC, `testForks=2`, ohne Last | 0 von 10 | 0 |
| nachher | 4 Kerne, UTC, `testForks=2`, 14 Last-Prozesse | 0 von 10 | 0 |

Der Rechner läuft mit UTC+2 (Sommerzeit), der Runner in UTC. Die Zeitzone wurde nicht getrennt von der Kernzahl gemessen. Die Reproduktion läuft daher immer mit UTC, wie der Runner.

### Regeln für UI-Tests mit Navigation und Laden

- Jeder Schritt wartet auf den Zustand, den er braucht. Verwendet werden `awaitUi`, `awaitDisplayed`, `awaitAbsent` und `clickWhenDisplayed` aus `app/src/test/.../testing/UiWaits.kt`.
- Die Wartebedingung ist die Compose-Assertion selbst, sie wird wiederholt. Feste Wartezeiten (`Thread.sleep`, `advanceTimeBy` als Ersatz) sind nicht erlaubt.
- Eine Abwesenheit (Navigationsleiste im Viewer) wird ebenfalls bedingt geprüft, nicht sofort.
- Die Obergrenze (5 s) bricht nur einen Zustand ab, der nie eintritt. Sie ist kein Puffer für langsame Läufe. Bei Ablauf meldet der Test die letzte Compose-Fehlermeldung mit dem Zustand des Baums.
- Keine Retry-Annotationen, keine pauschal längeren Timeouts ohne Bedingung.
- Die Galerie-Compose-Tests in `feature:gallery` prüfen überwiegend feste Zustände. `GalleryWiringTest` nutzt ein echtes ViewModel und wartet bereits mit `waitUntilAtLeastOneExists`. Sie haben das Muster nicht und sind unverändert. Unter Last (4 Kerne, 14 Last-Prozesse) liefen 3 von 3 Läufen mit je 228 Tests grün.

### Konsequenzen

- Die Tests sind an die Compose-Semantik gebunden. Eine neue Navigationsart braucht eigene Bedingungen, keine Zeitannahme.
- Der Default-Dispatcher bleibt in den Tests echt. Sollte die Abbildung der Zeitleiste einmal selbst zur Wartezeit werden, ist `Dispatchers.Unconfined` als Hilt-Testmodul der nächste Schritt.

## Quellen

- Messungen: lokal mit `./gradlew :app:testDebugUnitTest --rerun`, Ausgabe pro Lauf im Build-Ordner.
- Compose-Fehlermeldung „is not displayed“ aus den XML-Reports der fehlgeschlagenen Läufe.
