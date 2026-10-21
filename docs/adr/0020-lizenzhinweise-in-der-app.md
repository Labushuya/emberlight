# ADR 0020: Lizenzhinweise in der App (Meilenstein 3, Paket 3.7 vorgezogen)

Status: angenommen (21.10.2026, Nutzerfreigabe). Umsetzung im Branch `feature/m3-ueber-lizenzen`.

## Kontext

Apache-2.0 verlangt bei Weitergabe die Lizenz und etwaige NOTICE-Texte. Die App wird per Sideloading verteilt, ohne Google Play Services. Der Bildschirm „Über & Lizenzen“ muss die Lizenzhinweise aller mitgelieferten Bibliotheken zeigen, die Liste darf nicht veralten. Dazu kommen die Schriften Fraunces und Inter (SIL OFL 1.1) und die GPL-3.0-or-later der App selbst. Das Plugin `oss-licenses` von Google scheidet aus, es setzt Play Services voraus.

## Entscheidungen

### 1. Lizenzliste aus dem Build (umgesetzt)

- **Werkzeug:** AboutLibraries 15.3.0 (mikepenz, Apache-2.0, Lizenz im Repo geprüft). Das Gradle-Plugin `com.mikepenz.aboutlibraries.plugin.android` erzeugt beim Android-Build die Ressource `raw/aboutlibraries.json`. Die Laufzeitbibliothek `aboutlibraries-core` liest sie.
- **Einbindung über ein Konvention-Plugin:** Das Plugin liegt im Build-Logic-Classpath (`build-logic/convention`), angewendet über `emberlight.about.libraries`. Angewendet im Root-Projekt bricht es ab, weil AGP dort nicht im Classloader liegt. Das Plugin liegt nur auf dem Gradle Plugin Portal. Deshalb gibt es in `build-logic/settings.gradle.kts` ein Repository, das auf die Gruppe `com.mikepenz.aboutlibraries.plugin` begrenzt ist.
- **Nur Release-Laufzeit:** Das Plugin sammelt pro Variante aus deren eigenen Abhängigkeiten. Ohne Gegenmaßnahme stünden in Debug die Werkzeug-Bibliotheken drin (`ui-tooling`, `ui-tooling-data`, `ui-test-manifest` und die `-android`-Varianten). Die Konfiguration schließt sie per `exclusionPatterns` vollständig verankert aus. `ui-tooling-preview` bleibt, es ist eine Laufzeitabhängigkeit. Ergebnis: Debug-Liste und Release-Liste sind identisch. Der Unit-Test `releaseListHasNoTestOrToolingDependencies` prüft das im Debug-Build.
- **Unklare Lizenzen:** `StrictMode.WARN`. Erlaubt sind Apache-2.0, MIT und BSD-3-Clause. Eine Lizenz außerhalb dieser Liste oder eine fehlende Lizenzangabe erscheint als Warnung im Build. Der Build bricht nicht ab. Im Bildschirm steht eine Bibliothek ohne Lizenz als „Lizenz unbekannt“, sie verschwindet nicht.
- **Warum das Plugin und nicht eine eigene Gradle-Aufgabe:** Das Plugin läuft unter AGP 9.4 und Gradle 9.8 mit Configuration Cache und ohne Play-Services-Abhängigkeit. Die Lizenzen stehen als Volltext in der JSON-Ressource. Eine eigene Aufgabe aus `releaseRuntimeClasspath` mit POM-Lizenzen bleibt die Rückfallebene, falls das Plugin bei einem AGP-Update bricht.

### 2. Laufzeit (umgesetzt)

- Die Liste wird aus der Ressource geladen. Fehlt sie, zeigt der Bildschirm einen Hinweis (`NotIncluded`). Ein Absturz findet nicht statt.
- Die Auswertung prüft die JSON-Syntax zuerst. `Libs` verwirft ungültiges JSON still und liefert eine leere Liste. Ungültiges JSON ergibt deshalb `Unreadable`.
- Der Lookup der Ressource geschieht über den Namen zur Laufzeit (`getIdentifier`, mit begründetem `@SuppressLint`). So bleibt der Code in jeder Variante übersetzbar.

### 3. Schriften und GPL (umgesetzt)

- Die Lizenztexte der Schriften liegen in `core/design/src/main/assets/licenses` (Fraunces, Inter).
- Die GPL-3.0 liegt als Volltext in `core/design/src/main/assets/licenses/gpl-3.0.txt`. Die Datei ist eine Kopie von `LICENSE`, damit der Text offline lesbar ist.

### 4. Bildschirm und Einstieg (umgesetzt)

- **Einstieg:** Überlaufmenü (drei Punkte) in der Leiste der Galerie, Eintrag „Über & Lizenzen“. Die Galerie ist der Startbildschirm und hat sonst keine Aktionen. Die Route `AboutRoute` liegt im Modul `app`, sie gehört zur Galerie-Gruppe der Navigation (ADR 0002). Die Galerie bleibt in der Navigationsleiste markiert.
- **Inhalt:** App-Name, `versionName`, der Satz „emberlight ist freie Software unter der GPL-3.0-or-later“, Link zum Quellcode (Systembrowser, `https://github.com/Labushuya/emberlight`), Liste der Bibliotheken mit Version und Lizenz (Antippen zeigt den Volltext), Schriften mit OFL-Text, GPL mit Volltext und Link auf gnu.org.
- **Links:** `ACTION_VIEW` mit der Kategorie `BROWSABLE`. Ohne passende App erscheint ein Hinweis statt eines Absturzes.
- **Oberfläche:** Material 3 mit dem dunklen Theme, alle Texte in `strings.xml` (de und en), Überschriften als Semantik, Schaltflächen mit Rolle und Beschriftung.

## Konsequenzen

- Neue Laufzeitabhängigkeit `aboutlibraries-core` (Apache-2.0, ohne Play Services). Das Gradle-Plugin liegt in der Build-Logic.
- Die Liste aktualisiert sich bei jedem Build. Wer eine neue Bibliothek nur in Debug einbindet, sieht sie in der Liste nicht. Wer eine neue debug-only Bibliothek mit anderer Koordinate einführt, muss die Ausschlussliste in `AboutLibrariesConventionPlugin` ergänzen. Der Unit-Test prüft das für die bekannten Werkzeug-Artefakte.
- Die Galerie-Goldens ändern sich wegen des Überlauf-Icons. Die App-Goldens ebenso.
- Neue Goldens: `AboutScreen_phone_{de,en}` und `AboutScreen_large_{de,en}` (Telefon 412×915 dp und großes Display 900×600 dp).
- Die Tests prüfen die Auswertung der Liste (Sortierung, Lizenztexte, Bibliothek ohne Lizenz, ungültiges JSON), das Laden der Assets, die Dialoge und den Quellcode-Link (Intent), den Hinweis bei fehlender oder unlesbarer Liste und den Weg über das Überlaufmenü.

## Alternativen

- **`oss-licenses` von Google:** verworfen, setzt Play Services voraus.
- **Eigene Gradle-Aufgabe aus `releaseRuntimeClasspath`:** nicht gewählt, solange das Plugin läuft. Rückfallebene bei einem Bruch durch ein AGP-Update.
- **Compose-Oberfläche von AboutLibraries:** verworfen. Ihre Texte sind nicht vollständig übersetzt, und der Bildschirm soll zur App passen (Schriften, Abstände, Tiefe im dunklen Theme). Die Bibliothek liefert nur die Daten.
- **Eigener Eintrag in der Navigationsleiste:** verworfen. Die Leiste hat zwei Ziele (ADR 0002). Eine Information dieser Art gehört nicht dorthin.

## Quellen

- AboutLibraries, Lizenz (Apache-2.0): https://github.com/mikepenz/AboutLibraries/blob/main/LICENSE
- AboutLibraries, Einrichtung und Plugin-Optionen: https://github.com/mikepenz/AboutLibraries
- Gradle Plugin Portal, `com.mikepenz.aboutlibraries.plugin`: https://plugins.gradle.org/plugin/com.mikepenz.aboutlibraries.plugin
- SIL Open Font License 1.1: https://openfontlicense.org/
- GNU GPL-3.0: https://www.gnu.org/licenses/gpl-3.0.html
