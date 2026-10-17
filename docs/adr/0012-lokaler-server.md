# ADR 0012: Lokaler Server (Machbarkeits-Spike Meilenstein 3)

Status: Entwurf, 17.10.2026. Die Gerätemessung auf dem Honor Magic V2 (Android 16) ist nicht erfolgt, das Gerät war per adb nicht erreichbar. Der Spike-Code bleibt auf dem Branch `spike/m3-server` und wird nicht gemergt, bis die Gerätepunkte nachgeholt und bewertet sind.

## Kontext

Meilenstein 3 braucht einen Ktor-Server im Foreground-Service, über den Gäste im vorhandenen WLAN Dateien hochladen und herunterladen (siehe [KONZEPT.md](../KONZEPT.md), Abschnitt Spaces, und [CLAUDE.md](../../CLAUDE.md), Struktur `share:server`). Vor dem Bau waren fünf Fragen offen:

- Lässt sich Ktor 3.6.0 mit Kotlin 2.4.21, AGP 9.4.1 und `minSdk` 26 bauen, ohne Google Play Services und ohne R8-Probleme?
- Was kostet der Server in der Release-APK?
- Welche Engine und welche Bibliotheken braucht es, und wie groß ist der Overhead?
- Wie läuft ein Upload mit Limit und ein Range-Download?
- Überlebt der Dienst im Hintergrund auf einem echten Gerät (Doze, Bildschirm aus)?

Dieser ADR beantwortet die ersten vier Fragen mit Messwerten und Tests. Die letzte ist offen.

## Entscheidung

- **Engine**: Ktor 3.6.0, Engine CIO. `ktor-server-core`, `ktor-server-cio`, `ktor-server-partial-content`. Ktor 3.6.0 ist laut Maven Central die neueste stabile Version. Das POM nennt `kotlin-stdlib` 2.3.21, aufgelöst wird 2.4.21 (keine Konflikte).
- **Kapselung**: Aufrufer kennen nur das Interface `LocalServer` mit `start(ServerConfig): Int`, `stop()` und `state`. `ServerConfig` enthält Port (0 = frei) und Token. Ktor-Typen sind nach außen nicht sichtbar, außer dem Konstruktor von `KtorLocalServer`, der eine Routen-Lambda nimmt. Konsumenten brauchen dafür `ktor-server-core` auf dem Compile-Classpath (siehe Befund unten).
- **Bindung**: `0.0.0.0`, Port frei oder fest vorgegeben.
- **Zugriffsschutz**: Jeder Pfad, auch `/health` und unbekannte Pfade, verlangt `Authorization: Bearer <token>`. Der Vergleich läuft über `MessageDigest.isEqual`. Ohne gültigen Token kommt 401 mit `WWW-Authenticate: Bearer`. Der Wert des Tokens erscheint nie in `toString()` oder im Log.
- **Range**: `PartialContent` mit `LocalFileContent`. Die Antwort kommt mit `Content-Length`, Range-Anfragen liefern 206.
- **Upload**: Streaming über `part.provider()` in den app-internen Speicher. Die Größe zählt der Code selbst, weil `formFieldLimit` nur Formularfelder begrenzt, nicht Dateiteile. Das Limit liegt bei 2 GiB. Ein `Content-Length` über Limit plus 64 KiB wird vor dem Lesen mit 413 abgelehnt. Überschreitet der Strom das Limit, wird die Teildatei gelöscht. Der Dateiname des Clients wird nie übernommen.
- **Dienst**: `SpikeServerService` als Foreground-Service mit Typ `connectedDevice`. Start nur über den Debug-Einstieg `SpikeDebugActivity` (nur Debug-Source-Set).
- **Build-Sperre**: Task `checkServerIsolation` (Convention-Plugin `emberlight.server.isolation`) prüft den aufgelösten Abhängigkeitsgraphen von `:share:server` auf `:core:media` und `:feature:gallery` und hängt in `check`.

Der Spike-Code (Endpunkte `/spike/*`, Dienst, Debug-Einstieg) liegt nur auf dem Branch.

## Messwerte

### Abhängigkeiten und Build

| Prüfung | Ergebnis |
|---|---|
| Kompatibilität Kotlin 2.4.21 | Build ohne Compilerwarnungen, `allWarningsAsErrors` aktiv |
| `minSdk` 26, Lint | `:app:lintDebug` und `:share:server:lintDebug` grün. `checkDependencies` der App erfasst die Ktor-Bytecodes im Debug-Classpath, dort kein NewApi-Befund |
| Desugaring | Nicht eingerichtet, Lint verlangt es nicht. Das Laufzeitverhalten auf API 26 ist nicht getestet (offen) |
| `checkNoPlayServices` | grün |
| SLF4J | Nur `slf4j-api`, kein Backend. Ohne Provider greift der No-op-Logger. Eine Konsolenwarnung von SLF4J beim Start wurde nicht geprüft |
| Lint-Befunde im Spike | Zwei Befunde behoben: `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` ist erst ab API 29 (InlinedApi, Typ wird darunter weggelassen), `notify` ohne Prüfung von `POST_NOTIFICATIONS` (MissingPermission, Prüfung ab API 33) |
| Spotless und ktlint | grün |

### APK-Größe (unsigniert, Release)

| Stand | Größe |
|---|---|
| Baseline, `main` 8d04693 | 2 993 877 Byte (2,85 MiB) |
| Probe: Server im Release-Classpath verlinkt | 3 652 841 Byte (+658 964 Byte, +22,0 %) |
| Ausgeliefert (Server nur im Debug-Classpath) | 2 993 877 Byte, unverändert |

Die Probe band den Server kurz in `EmberlightApplication` ein, damit R8 die Engine behält. Danach wurde sie zurückgenommen. Die Release-APK des Spike-Branchs entspricht deshalb der Baseline. Die Debug-APK des Spike-Branchs misst 20 832 246 Byte. Eine Vergleichsmessung der Debug-APK vor dem Spike fehlt.

### R8

- Ohne Regel bricht der Release-Build ab: Ktor referenziert `java.lang.management.ManagementFactory` und `RuntimeMXBean` (Debugger-Erkennung in `IntellijIdeaDebugDetector`). Das Paket gibt es auf Android nicht.
- Eingetragen sind nur die zwei `-dontwarn`-Zeilen in `share/server/consumer-rules.pro`. Es wird keine Klasse behalten.
- Offen: Der Laufzeitpfad der Debugger-Erkennung ist auf Android nicht belegt. Er wird im Spike nicht aufgerufen, das ist aber nicht geprüft.

### Build-Sperre

- Task `:share:server:checkServerIsolation` hängt in `check`. Die Prüffunktion hat fünf Unit-Tests (`ProjectIsolationGuardTest`, Teil von `:convention:test`).
- Negativprobe: Eine temporäre Abhängigkeit `implementation(project(":core:media"))` in `share/server/build.gradle.kts` bricht den Task ab und nennt `:core:media`. Die Zeile wurde danach entfernt, der Task läuft wieder grün.
- Die Prüfung nutzt den aufgelösten Graphen (`allComponents`) und erfasst damit auch transitive Abhängigkeiten. Die Negativprobe deckt nur den direkten Fall ab.
- Quelle der Vorgabe: Der Auftrag nennt die Regel „Strikte Trennung von Space und Galerie“ und die Sperre aus CLAUDE.md. Beides steht weder in [CLAUDE.md](../../CLAUDE.md) noch in [KONZEPT.md](../KONZEPT.md) (Stand `main` 8d04693). Die Regel ist hier aus dem Auftrag übernommen und muss in CLAUDE.md oder KONZEPT.md nachgetragen werden.

### Tests

- `share:server`: 23 Unit-Tests, alle grün. `AccessTokenTest` (6), `KtorLocalServerTest` (5, davon ein echter Socket-Test mit Start, 401 und 200 und Stopp), `SpikeRoutesTest` (12): Upload, Download, Range (`bytes=0-1023` und offen), Limit beim Lesen und bei `Content-Length`, Teildatei wird gelöscht, unbekannte und manipulierte IDs (404), Token-Pflicht, Fortschritt.
- Gesamte Kover-Zeilenabdeckung: 89,3 % (3685 von 4126 Zeilen), Untergrenze 70 %.
- `./gradlew check koverXmlReport assembleDebug assembleRelease` lokal grün.

## Gerätemessung (offen)

Auf dem Gerät ist nichts gemessen. `adb devices` zeigte bei der Ausführung kein angeschlossenes Gerät. Die Punkte stehen hier, damit sie nicht vergessen werden:

- Erreichbarkeit im WLAN, Durchsatz Upload und Download für 200 MB, Statuscodes 200 und 206 mit Range.
- Dauertest von mindestens 45 min mit `/health` alle 60 s, `pidof`, `dumpsys` des Dienstes (`isForeground`, `foregroundServiceType`), Abbrüche bei Doze und Bildschirm aus.
- `dumpsys meminfo` vor und nach dem Upload.
- Verhalten ohne `POST_NOTIFICATIONS` (Dienst läuft, Meldung unsichtbar; im Code protokolliert).

## Netzwerk und Berechtigungen

- `INTERNET` ist ab diesem Spike im Modul-Manifest (für den Server nötig). Im Manifest der App stand bisher keine INTERNET-Berechtigung.
- `ACCESS_LOCAL_NETWORK` ist im Manifest deklariert. Die Android-Doku legt die Durchsetzung auf targetSdk 37 (Android 17). Auf Android 16 gilt sie nicht, dort war die Berechtigung nur ein Übergang (die Doku nennt dafür `NEARBY_WIFI_DEVICES`). Dieser Ablauf ist aus der Doku abgeleitet und am Gerät nicht geprüft.
- Ablauf für Android 17 (noch nicht umgesetzt, Aufgabe der Oberfläche in Paket 3.x): Vor dem Start die Berechtigung prüfen (`checkSelfPermission`). Fehlt sie, mit `requestPermissions` anfragen und den Start erst nach Gewährung ausführen. Der Spike prüft nur und lehnt ab, er fragt nicht an.
- Der Dienst prüft `ACCESS_LOCAL_NETWORK` bei API 37 und höher. Darunter ist die Prüfung erfüllt.
- FGS-Typ: `connectedDevice` mit `FOREGROUND_SERVICE_CONNECTED_DEVICE` und `CHANGE_NETWORK_STATE` (eine der laut Doku nötigen Alternativen). Kein `dataSync`, weil dessen Zeitlimit (laut KONZEPT sechs Stunden pro Tag ab Android 15) den Dauerbetrieb begrenzt. Die Doku nennt für `connectedDevice` kein Zeitlimit (verifiziert).

## Logging und Datenschutz

- Ausgaben nur über `android.util.Log`, Tag `EmberlightSpike`. Protokolliert werden Port, Byte-Mengen und der Start- oder Stopp-Status.
- Nie protokolliert: Token, Dateinamen, Inhalte, Pfade.
- Den Token übergibt der PC als Intent-Extra. Der Dienst speichert ihn nicht. Der Befehl steht in der Shell-Historie, deshalb nur für Wegwerf-Tests.
- Der Spike schreibt nur nach `filesDir/spike/`, nie in MediaStore oder die Galerie.

## Konsequenzen

- Der Server ist mit Ktor CIO machbar. Build, Lint und R8 sind grün, nachdem zwei Lint-Befunde und ein R8-Fehler (fehlende Klassen) behandelt sind.
- Die Release-APK wächst um rund 0,66 MB (+22 %), sobald der Server eingebunden wird. Eine Aufschlüsselung nach Paketen fehlt.
- Der Konstruktor von `KtorLocalServer` verlangt Ktor auf dem Compile-Classpath des Aufrufers. Vor einem produktiven Einsatz sollte die Lambda-Schnittstelle gekapselt werden.
- Die Build-Sperre schützt die Modulgrenze ab jetzt im Build.

## Offene Risiken

- **Gerätetest fehlt**: Dauerbetrieb, Doze, Netzwerk nach Bildschirm aus und Durchsatz sind ungemessen. Der FGS-Typ `connectedDevice` ist damit nicht belegt.
- **Android 8 (API 26)**: Ktor und seine Abhängigkeiten sind auf API 26 nicht zur Laufzeit geprüft. Ein fehlendes Java-API erst zur Laufzeit wird von Lint nicht gemeldet.
- **Engine-Kompatibilität**: Eine ausdrückliche Android-Freigabe für CIO habe ich in der Recherche nicht gefunden. Die Suche war nicht erschöpfend, die Laufzeit auf Android ist nicht getestet.
- **Android 17**: Der Ablauf zu `ACCESS_LOCAL_NETWORK` ist nicht am Gerät getestet.
- **TLS**: Der Spike läuft über HTTP mit Token. TLS ist für Meilenstein 5 vorgesehen (siehe KONZEPT, offene Punkte).
- **Prozess-Tod**: Der Dienst startet nach einem Prozess-Tod nicht neu (`START_NOT_STICKY`). Ein Gast verliert dann die Verbindung ohne Hinweis.
- **Token über die Kommandozeile**: Nur für den Spike. Ein Produktivtoken wird von der App erzeugt und nicht über adb übergeben.

## Empfehlung für Paket 3.1 und 3.2

1. Gerätemessung nachholen (Punkte oben), bevor Ktor produktiv eingebunden wird.
2. Die Build-Sperre mit einer Negativprobe für den transitiven Fall ergänzen, und die Regel in CLAUDE.md oder KONZEPT.md festhalten.
3. Den Dienst über die UI starten und dabei die Berechtigungen anfragen. Der Debug-Einstieg bleibt nur im Debug-Set.
4. Dateiablage und Space-Verwaltung vor dem Serverausbau entwerfen. Die Spike-Ablage `filesDir/spike/` ist nur ein Platzhalter.

## Alternativen

- **Andere Engines (Netty, Jetty)**: nicht geprüft. Die Wahl von CIO folgt CLAUDE.md (Technik: „Eingebetteter Server: Ktor Server (CIO)“).
- **Upload über `dataSync`**: verworfen, wegen des Sechs-Stunden-Limits (KONZEPT).
- **Dateiname des Clients übernehmen**: verworfen, weil damit Pfad-Angriffe und Datenlecks möglich werden.

## Quellen

- Ktor-Artefakte (Maven Central): [ktor-server-core 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-core-jvm/3.6.0/ktor-server-core-jvm-3.6.0.pom), [ktor-server-cio 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-cio-jvm/3.6.0/ktor-server-cio-jvm-3.6.0.pom)
- Android-Doku, Foreground-Service-Typen (connectedDevice, specialUse, dataSync): [developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Android-Doku, Berechtigung für lokales Netz: [developer.android.com](https://developer.android.com/privacy-and-security/local-network-permission)
