# ADR 0005: Tests

Status: angenommen, 15.10.2026

## Kontext

Die Oberfläche soll ohne Gerät testbar sein. Robolectric 4.17 läuft auf dem JVM und braucht für SDK 37 JDK 21. Der Build selbst bleibt auf JDK 17. Instrumentierte Tests auf einem Emulator würden in der CI die 2.000 Actions-Minuten pro Monat (Free-Plan) belasten.

## Entscheidung

- Unit-Tests laufen mit einer JDK-21-Toolchain als Test-Launcher. Compiler und Lint bleiben auf JDK 17 (siehe ADR 0001).
- In der CI stellt `actions/setup-java` JDK 21 bereit, Gradle findet es ohne Download. Lokal lädt Gradle JDK 21 über die Toolchain-Auflösung (foojay) automatisch.
- Espresso 3.7.0 wird fest gesetzt. Die von `ui-test` gezogene Version 3.5 scheitert mit SDK 37.
- Tests in Meilenstein 1:
  - `EmberlightColorContrastTest`: WCAG-Kontrast der Farben.
  - `EmberlightAppNavigationTest`: Navigation zwischen den Top-Level-Zielen.
  - `MainActivitySmokeTest`: Start der Activity mit Hilt.
  - Compose-Tests der Platzhalter-Screens in `feature:gallery` und `feature:space`.
- Screenshot-Tests (Goldens) kommen ab Meilenstein 2, wenn das Design-System steht. Ein Coverage-Ziel legen wir in Meilenstein 2 fest.

## Konsequenzen

- Zwei JDKs sind nötig. Der Test-Lauf dauert etwas länger.
- Robolectric und SDK-Version müssen zusammenpassen. Bei Updates prüft der Test-Lauf das.
- Ein Testgerät oder Emulator ist für die CI nicht nötig.

## Alternativen

- Nur JDK 17: verworfen, Robolectric 4.17 mit SDK 37 braucht JDK 21.
- Instrumentierte Tests auf Emulator in der CI: verworfen, zu teuer für den Free-Plan.
- Goldens sofort in Meilenstein 1: verworfen, sie würden jedes Design-Detail festschreiben, bevor das Design-System existiert.
