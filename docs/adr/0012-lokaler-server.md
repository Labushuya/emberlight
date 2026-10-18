# ADR 0012: Lokaler Server (Meilenstein 3, Paket 3.0)

Status: angenommen, 18.10.2026. Grundlage sind der Machbarkeits-Spike auf dem Branch `spike/m3-server` (PR #23, Draft) und die Gerätemessungen vom 18.10.2026. Die produktiven Teile stehen auf dem Branch `feature/m3-server-basis`. Der Spike-Code bleibt auf seinem Branch und wird nicht gemergt.

## Kontext

Meilenstein 3 braucht einen Ktor-Server im Foreground-Service, über den Gäste im vorhandenen WLAN Dateien hochladen und herunterladen (siehe [KONZEPT.md](../KONZEPT.md), Abschnitt Spaces, und [CLAUDE.md](../../CLAUDE.md), Struktur `share:server`). Vor dem Bau waren fünf Fragen offen:

- Lässt sich Ktor 3.6.0 mit Kotlin 2.4.21, AGP 9.4.1 und `minSdk` 26 bauen, ohne Google Play Services und ohne R8-Probleme?
- Was kostet der Server in der Release-APK?
- Welche Engine und welche Bibliotheken braucht es, und wie groß ist der Overhead?
- Wie läuft ein Upload mit Limit und ein Range-Download?
- Überlebt der Dienst im Hintergrund auf einem echten Gerät (Doze, Bildschirm aus, Akkubetrieb)?

Die ersten vier Fragen beantwortet der Spike mit Messwerten und Tests. Die letzte beantworten die Gerätemessungen unten. Sie zeigen, dass der Hintergrundbetrieb von Herstellereinstellungen abhängt.

## Entscheidung

- **Engine**: Ktor 3.6.0, Engine CIO, nur `ktor-server-core`, `ktor-server-cio` und `ktor-server-partial-content`. Ktor 3.6.0 ist laut Maven Central die neueste stabile Version. Das POM nennt `kotlin-stdlib` 2.3.21, aufgelöst wird 2.4.21 (keine Konflikte).
- **Kapselung**: Aufrufer kennen nur das Interface `LocalServer` mit `start(ServerConfig): Int`, `stop()` und `state`. `ServerConfig` enthält nur den Port (0 = frei). Die Implementierung `KtorLocalServer` nimmt die Routen als Lambda entgegen. Dieser Konstruktor verlangt Ktor auf dem Compile-Classpath des Aufrufers. Das ist eine bekannte Einschränkung.
- **Bindung**: `0.0.0.0`, Port frei oder fest vorgegeben.
- **Zugriffsschutz**: Erweiterungspunkt `RequestAuthorizer`. Die Prüfung läuft vor jeder Route, auch vor `/health` und unbekannten Pfaden. Der Standard ist `RequestAuthorizer.DenyAll`: Der Server lehnt alles ab (401, `WWW-Authenticate: Bearer`), wenn keine Prüfung eingesetzt wird. Die konkrete Prüfung mit Zugangsschlüssel und Einladungen kommt mit Paket 3.x. Der Spike hatte einen Token fest verdrahtet. Das ist entfernt.
- **`/health`**: liefert ausschließlich `{"status":"ok","apiVersion":1}`. Keine Prozess-ID, keine Zeitangaben, keine Sperrzustände, keine Gerätedaten.
- **Protokoll-Hook**: `requestLog` ist ein generischer Hook. Er erhält Eingang und Abschluss jeder Anfrage (Methode, Pfad, Status, Dauer). Standardmäßig protokolliert der Server nichts.
- **Range**: Plugin `PartialContent`. Range-Anfragen liefern 206 mit `Content-Length`.
- **Upload-Baustein**: `ApplicationCall.receiveSingleFile(targetDir, maxBytes)`, ohne Routen.
  - `formFieldLimit` wird auf `maxBytes` plus 64 KiB gesetzt. Ohne diesen Parameter scheitert ein Upload an Ktors Standardgrenze von 50 MiB.
  - Das Limit zählt der Code beim Lesen selbst. Ein `Content-Length` über dem Limit wird vor dem Lesen abgelehnt.
  - Der Dateiname des Clients wird nie übernommen. Das Ziel ist eine zufällige UUID mit Endung `.bin`.
  - Überschreitet der Strom das Limit oder bricht das Schreiben ab, wird die Teildatei gelöscht.
  - Das Limit legt der Aufrufer fest. Der Spike nutzte 2 GiB.
- **Dienst**: `SpaceServerService` als Foreground-Service mit Typ `connectedDevice`. `dataSync` scheidet aus, weil es ab Android 15 ein Limit von sechs Stunden pro Tag hat (siehe [KONZEPT.md](../KONZEPT.md), Abschnitt Betrieb). Der Dienst hat noch keine Routen und keinen Startpunkt in der Oberfläche.
- **Sperren**: PARTIAL_WAKE_LOCK mit dem Tag `emberlight:space-server` und ein WifiLock im Modus `WIFI_MODE_FULL_HIGH_PERF`. Beide gelten ohne Zeitlimit, bis der Server stoppt. Stopp über Intent oder Benachrichtigung gibt beide frei, ebenso `onDestroy`. `WIFI_MODE_LOW_LATENCY` wirkt nach Doku nur bei eingeschaltetem Bildschirm im Vordergrund und scheidet deshalb aus.
- **Berechtigungen**: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`, `CHANGE_NETWORK_STATE` (eine der laut Doku für `connectedDevice` nötigen Alternativen, normal, zur Installationszeit gewährt) und `ACCESS_LOCAL_NETWORK`.
- **Lokales Netz**: `ACCESS_LOCAL_NETWORK` ist ab targetSdk 37 Pflicht (eingehend und ausgehend sowie NSD). Die Anfrage zur Laufzeit folgt in Paket 3.3. Der Dienst verweigert den Start auf API 37 und höher ohne diese Berechtigung. Darunter gilt die Prüfung als erfüllt.
- **Build-Sperre**: Task `checkServerIsolation` (Convention-Plugin `emberlight.server.isolation`) prüft den aufgelösten Abhängigkeitsgraphen von `:share:server`, transitiv, auf `:core:media` und `:feature:gallery`. Der Task hängt in `check`. Damit läuft er in CI und lokal.
- **Keine TLS-Verschlüsselung**: Der Server spricht HTTP. TLS im fremden WLAN ist für einen späteren Meilenstein vorgesehen (CLAUDE.md, Meilensteine, Punkt 6). App-zu-App-Verbindungen haben laut [KONZEPT.md](../KONZEPT.md), Abschnitt Verschlüsselung, eigene Regeln.
- **R8**: `share/server/consumer-rules.pro` enthält nur zwei `-dontwarn`-Zeilen für `java.lang.management`. Es wird keine Klasse behalten.
- **Einbindung in die App**: `app` bindet `:share:server` in allen Varianten ein, auch im Release. Der Release-Build prüft so R8 mit dem Server. Die APK wächst dadurch (siehe Messwerte).

## Änderungen gegenüber dem Entwurf (17.10.2026)

- Der Token ist aus `ServerConfig` und aus dem Server entfernt. Er gehört in ein späteres Paket. Der Standard verweigert alles.
- `/health` enthält nur Status und API-Version. Der Spike-Umfang (Prozess-ID, Startzeit, Sperrzustände, letzter Herzschlag) ist entfernt.
- Die Spike-Routen (`/spike/upload`, `/spike/files/{id}`) sind nicht übernommen. Übernommen ist nur der Upload-Baustein.
- Der Dienst heißt `SpaceServerService`, die Sperren `ServerLocks`. Der Debug-Einstieg, die Diagnose und das Selbstprotokoll sind nicht übernommen.
- Die Notiz, die Regel „Strikte Trennung von Space und Galerie“ stehe nicht in CLAUDE.md, ist überholt. Die Regel steht jetzt in CLAUDE.md und in KONZEPT.md.
- `POST_NOTIFICATIONS` ist nicht deklariert. Mit der Laufzeitanfrage in Paket 3.3 kommt die Deklaration dazu.

## Messwerte

### Abhängigkeiten und Build

| Prüfung | Ergebnis |
|---|---|
| Kompatibilität Kotlin 2.4.21 | Build ohne Compilerwarnungen, `allWarningsAsErrors` aktiv |
| `minSdk` 26, Lint | `lintDebug` für `:share:server` und `:app` grün. Lint hat die Ktor-Bytecodes im Debug-Classpath geprüft, dort kein NewApi-Befund |
| Desugaring | Nicht eingerichtet, Lint verlangt es nicht. Das Laufzeitverhalten auf API 26 ist nicht getestet (offen) |
| `checkNoPlayServices` | grün |
| SLF4J | Kein eigener Eintrag im Version Catalog. Ktor bringt `slf4j-api` mit. Ohne Provider greift der No-op-Logger |
| Spotless und ktlint | grün |
| `checkServerIsolation` | grün. Negativprobe: `implementation(project(":core:media"))` in `share/server/build.gradle.kts` bricht den Task ab und nennt `:core:media`. Die Zeile wurde danach entfernt, der Task läuft wieder grün. |

### Tests

- `:share:server`: 25 Unit-Tests, alle grün.
  - `KtorLocalServerTest` (10): `/health` nur mit Berechtigung, Standard verweigert alles, unbekannte Pfade geschützt, Request-Log, echter Socket auf Port 0 mit Start, 401, 200 und Stopp, zweiter Start abgelehnt, Port-Bereich.
  - `UploadStoreTest` (6): Zufallsname, Limit beim Lesen, Grenzwert, `Content-Length`-Vorprüfung, Upload über 50 MiB, Formular ohne Datei.
  - `ServerLocksTest` (6): Erwerb, Idempotenz, Freigabe, Freigabe ohne Erwerb, doppelte Freigabe, Rückabwicklung bei fehlgeschlagenem WLAN-Erwerb.
  - `SpaceServerServiceTest` (3, Robolectric): Stopp-Intent, Start ohne `ACCESS_LOCAL_NETWORK` auf API 37 wird abgelehnt, unbekannter Aufruf beendet den Dienst.
- `:convention`: `ProjectIsolationGuardTest` (5), zusammen mit den übrigen Tests der Convention-Plugins grün.
- Gesamte Kover-Zeilenabdeckung: 90,9 % (3730 von 4104 Zeilen). `share:server` allein: 80,8 % (168 von 208 Zeilen). Untergrenze 70 %.
- Gesamtlauf `./gradlew check koverXmlReport assembleDebug assembleRelease`: grün (BUILD SUCCESSFUL, Exit 0), inklusive Spotless, Lint, Roborazzi-Verify, Kover-Verify, `checkNoPlayServices`, `checkServerIsolation`.

### APK-Größe (unsigniert, Release)

| Stand | Größe |
|---|---|
| Baseline, `main` (Code unverändert seit 8d04693, Messung des Spike-ADR) | 2 993 877 Byte |
| Mit Server, `feature/m3-server-basis` | 3 652 833 Byte (+658 956 Byte, +22,0 %) |

Der Spike hatte gemessen: Der Server wächst die Release-APK um rund 0,66 MB (+22 %). Die Messung oben gilt für den Stand dieses Branchs mit eingebundenem Server. Eine Aufschlüsselung nach Paketen fehlt weiterhin.

### R8

- Ohne Regel bricht der Release-Build ab: Ktor referenziert `java.lang.management.ManagementFactory` und `RuntimeMXBean` (Debugger-Erkennung in `IntellijIdeaDebugDetector`). Das Paket gibt es auf Android nicht.
- Eingetragen sind nur die zwei `-dontwarn`-Zeilen. Offen: Der Laufzeitpfad der Debugger-Erkennung ist auf Android nicht belegt.

## Gerätemessung (18.10.2026)

Gerät: Honor Magic V2 (VER-N49), Android 16, MagicOS, WLAN. Der Spike war in beiden Läufen mit einem Dienst im Typ `connectedDevice` aktiv, mit Akku-Ausnahme (deviceidle-Whitelist), PARTIAL_WAKE_LOCK und WifiLock. Herzschlag der App alle 5 s, Anfragen aus dem Netz.

**Lauf 1: nur Akku-Ausnahme**

- Angesteckt, Bildschirm aus: 34 von 34 Anfragen ok.
- Abgesteckt: 0 von 107 Anfragen ok.
- Herzschlag der App ab 1 s nach dem Abstecken ausgesetzt: 5 Lücken von 12 bis 37 min. Auch in Wachphasen des Systems blieben Herzschläge aus.
- Anfragen wurden erst etwa 18 min verspätet gelesen.
- Kein WLAN-Verlust, nie eine Netz-Blockade gemeldet.

**Lauf 2: zusätzlich Honor „App-Start“**

Einstellungen: „Automatisch verwalten“ aus, Automatischer Start, Sekundärer Start und Im Hintergrund ausführen an.

- 22 min abgesteckt, Bildschirm aus: 45 von 45 Anfragen ok, Mittel 0,12 s.
- 268 von 268 Herzschläge, keine Lücke.
- Server-Verzögerung 1 bis 2 s. Light-Idle trat auf, Deep Doze nicht.

**Deutung (Schluss aus den Messungen, keine belegte Ursache):** MagicOS friert Apps im Akkubetrieb trotz Foreground-Service, Wakelock und Akku-Ausnahme ein, solange „App-Start“ automatisch verwaltet wird. Belegt ist nur die Kombination aus beiden Einstellungen. Ob die App-Start-Einstellung allein reicht, ist offen.

## Konsequenzen

- Der Server mit Ktor CIO ist machbar. Build, Lint, Tests und R8 sind grün.
- Auf dem Honor-Gerät hat die Akku-Ausnahme allein (Lauf 1) nicht ausgereicht. Zusätzlich war die Hintergrund-Freigabe nötig (Lauf 2). Andere Hersteller können eigene Sperren haben.
- Paket 3.3 bekommt einen Einrichtungs-Ablauf:
  - Akku-Ausnahme
  - herstellerspezifische Hintergrund-Freigabe, Honor zuerst. Huawei, Xiaomi und weitere als Ausbaustufe
  - Ladegerät-Empfehlung
  - erneute Prüfung beim Space-Start
  - Laufzeitanfragen für `ACCESS_LOCAL_NETWORK` und `POST_NOTIFICATIONS`
- **Pausen-Erkennung**: Der Dienst führt einen eigenen Herzschlag und erkennt Lücken (Laufzeit gegen Erwartung). Erkennt er eine Pause, zeigt er einen Hinweis, dass das System emberlight pausiert hat, mit Weg zur Einstellung. Hersteller-Einstellungen lassen sich nicht per API abfragen, deshalb läuft die Erkennung über die Laufzeit.
- Die Build-Sperre schützt die Modulgrenze im Build, transitiv.
- Der Upload-Baustein ist ohne Route getestet. Die Route, die Dateiablage und die Space-Verwaltung folgen in Paket 3.1 und 3.2.

## Offene Punkte und Risiken

- **Langzeitverhalten**: Dauerbetrieb über Stunden, Nacht und Deep Doze ist nicht gemessen. Die Prüfung folgt mit der echten Space-Funktion auf dem Gerät.
- **Android 8 (API 26)**: Ktor und seine Abhängigkeiten sind dort zur Laufzeit nicht geprüft. Lint erkennt fehlende Java-APIs nur zum Teil, ein Fehlen zeigt sich also erst zur Laufzeit.
- **Engine-Kompatibilität**: Eine ausdrückliche Android-Freigabe für CIO habe ich nicht gefunden. Die Laufzeit auf Android ist nur auf dem Honor-Gerät getestet.
- **Android 17 Laufzeitanfrage**: Der Ablauf zu `ACCESS_LOCAL_NETWORK` ist nicht am Gerät getestet, weil die Oberfläche fehlt.
- **Prozess-Tod**: Der Dienst startet nach einem Prozess-Tod nicht neu (`START_NOT_STICKY`). Ein Gast verliert dann die Verbindung ohne Hinweis.
- **WifiLock-Modus**: `WIFI_MODE_FULL_HIGH_PERF` ist als veraltet markiert. Die Warnung ist mit `@Suppress("DEPRECATION")` unterdrückt. Ein Ersatz ohne Einschränkung ist nicht bekannt.
- **Schutz gegen Zugriff**: Ohne Zugriffsprüfung antwortet der Server nur mit 401. Vor dem ersten Routen-Einsatz muss Paket 3.x die Prüfung liefern.
- **TLS**: Der Server spricht HTTP (siehe Entscheidung).
- **Gerätetest**: Der Hintergrundbetrieb wird im Gerätetest mit der echten Space-Funktion geprüft, nicht mit dem Spike.

## Alternativen

- **Andere Engines (Netty, Jetty)**: nicht geprüft. Die Wahl von CIO folgt CLAUDE.md (Technik: „Eingebetteter Server: Ktor Server (CIO)“).
- **Upload über `dataSync`**: verworfen, wegen des Sechs-Stunden-Limits (KONZEPT).
- **Dateiname des Clients übernehmen**: verworfen, weil damit Pfad-Angriffe und Datenlecks möglich werden.
- **Standardmäßig offener Zugang**: verworfen. Der Server verweigert ohne Prüfung alles.

## Quellen

- Spike-Stand: Branch `spike/m3-server`, PR #23 (Draft).
- Ktor-Artefakte (Maven Central): [ktor-server-core 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-core-jvm/3.6.0/ktor-server-core-jvm-3.6.0.pom), [ktor-server-cio 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-cio-jvm/3.6.0/ktor-server-cio-jvm-3.6.0.pom)
- Android-Doku, Foreground-Service-Typen (connectedDevice, specialUse, dataSync): [developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Android-Doku, Berechtigung für lokales Netz: [developer.android.com](https://developer.android.com/privacy-and-security/local-network-permission)
