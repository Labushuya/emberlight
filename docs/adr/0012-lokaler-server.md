# ADR 0012: Lokaler Server (Meilenstein 3, Paket 3.0)

Status: angenommen, 18.10.2026. Grundlage sind der Machbarkeits-Spike auf dem Branch `spike/m3-server` (PR #23, Draft) und die Gerätemessungen vom 18.10.2026. Die produktiven Teile stehen auf dem Branch `feature/m3-server-basis` (PR #25). Der Spike-Code bleibt auf seinem Branch und wird nicht gemergt. Nach dem Review vom 18.10.2026 sind die Änderungen in „Änderungen nach dem Review“ festgehalten.

## Kontext

Meilenstein 3 braucht einen Ktor-Server im Foreground-Service, über den Gäste im vorhandenen WLAN Dateien hochladen und herunterladen (siehe [KONZEPT.md](../KONZEPT.md), Abschnitt Spaces, und [CLAUDE.md](../../CLAUDE.md), Struktur `share:server`). Vor dem Bau waren fünf Fragen offen:

- Lässt sich Ktor 3.6.0 mit Kotlin 2.4.21, AGP 9.4.1 und `minSdk` 26 bauen, ohne Google Play Services und ohne R8-Probleme?
- Was kostet der Server in der Release-APK?
- Welche Engine und welche Bibliotheken braucht es, und wie groß ist der Overhead?
- Wie läuft ein Upload mit Limit und ein Range-Download?
- Überlebt der Dienst im Hintergrund auf einem echten Gerät (Doze, Bildschirm aus, Akkubetrieb)?

Die ersten vier Fragen beantwortet der Spike mit Messwerten und Tests. Die letzte beantworten die Gerätemessungen unten. Sie zeigen auf einem Gerät, dass der Hintergrundbetrieb von Herstellereinstellungen abhängen kann.

## Entscheidung

- **Engine**: Ktor 3.6.0, Engine CIO, nur `ktor-server-core`, `ktor-server-cio` und `ktor-server-partial-content`. Ktor 3.6.0 ist laut Maven Central die neueste stabile Version. Das POM nennt `kotlin-stdlib` 2.3.21, aufgelöst wird 2.4.21 (keine Konflikte).
- **Kapselung**: Aufrufer kennen nur das Interface `LocalServer` mit `start(ServerConfig): Int`, `stop()` und `state`. `ServerConfig` enthält nur den Port (0 = frei). Die Implementierung `KtorLocalServer` nimmt die Routen als Lambda entgegen. Dieser Konstruktor verlangt Ktor auf dem Compile-Classpath des Aufrufers. Das ist eine bekannte Einschränkung.
- **Bindung**: `0.0.0.0`, Port frei oder fest vorgegeben. Für Paket 3.0 unkritisch, weil der Server jede Anfrage mit 401 ablehnt (`DenyAll`), keine Route hat und die Oberfläche ihn nicht startet. Vor Paket 3.1 gilt die Pflicht zur Begrenzung auf das WLAN (siehe „Pflicht vor Paket 3.1“).
- **Zugriffsschutz**: Erweiterungspunkt `RequestAuthorizer`. Die Prüfung läuft vor jeder Route, auch vor `/health` und unbekannten Pfaden. Der Standard ist `RequestAuthorizer.DenyAll`: Der Server lehnt alles ab (401, `WWW-Authenticate: Bearer`), wenn keine Prüfung eingesetzt wird. Die konkrete Prüfung mit Zugangsschlüssel und Einladungen kommt mit Paket 3.x. Der Spike hatte einen Token fest verdrahtet. Das ist entfernt.
- **`/health`**: liefert ausschließlich `{"status":"ok","apiVersion":1}`. Keine Prozess-ID, keine Zeitangaben, keine Sperrzustände, keine Gerätedaten.
- **Protokoll-Hook**: `requestLog` ist ein generischer Hook. Er erhält Eingang und Abschluss jeder Anfrage (Methode, Pfad, Status, Dauer). Der Server übergibt den rohen Pfad ohne Query-String. Standardmäßig protokolliert der Server nichts.
- **Logging-Regel**: Tokens und Einladungsschlüssel stehen **nie** im Pfad oder in der Query, nur in Headern. Nur so landen sie nicht im Protokoll und nicht in Zugriffs-Logs. Ab Paket 3.1 protokolliert der Server Route-Templates (z. B. `/files/{id}`) statt roher URIs.
- **Range**: Plugin `PartialContent`. Range-Anfragen liefern 206 mit `Content-Length`.
- **Upload (Entscheidung für Paket 3.1)**:
  - Roher Request-Body, eine Datei pro Anfrage, `Content-Type: application/octet-stream`. Metadaten (Anzeigename, Uploader) stehen in Headern. Kein Multipart.
  - Hartes Byte-Limit beim Streamen, unabhängig von `Content-Length`. Der Code zählt die gelesenen Bytes selbst und bricht bei Überschreitung ab.
  - Ziel ausschließlich über einen Typ `SpaceStorage` unterhalb des app-internen Speichers (`filesDir`), mit Kanonisierungsprüfung des Zielpfads.
  - Client-Dateinamen werden nie als Pfad verwendet. Der Server vergibt den Speichernamen selbst.
- **Dienst**: `SpaceServerService` als Foreground-Service mit Typ `connectedDevice`. `dataSync` scheidet aus, weil es ab Android 15 ein Limit von sechs Stunden pro Tag hat (siehe [KONZEPT.md](../KONZEPT.md), Abschnitt Betrieb). Der Typ `connectedDevice` hat kein Zeitlimit. Der Dienst hat noch keine Routen und keinen Startpunkt in der Oberfläche.
- **Lebenszyklus**: Start und Stopp laufen über ein Mutex in der Reihenfolge ihres Eintreffens. Ein Stopp während des Starts wartet, bis der Start endet, und räumt danach Server und Sperren auf. Sperren werden nur erworben, solange kein Stopp angefordert ist. `onDestroy` gibt die Sperren sofort frei und stoppt den Server danach.
- **Sperren**: PARTIAL_WAKE_LOCK mit dem Tag `emberlight:space-server` und ein WifiLock im Modus `WIFI_MODE_FULL_HIGH_PERF`. Beide gelten ohne Zeitlimit, bis sie in Stopp oder `onDestroy` ausdrücklich freigegeben werden. `WIFI_MODE_LOW_LATENCY` wirkt nach Doku nur bei eingeschaltetem Bildschirm im Vordergrund und scheidet deshalb aus.
- **Vordergrund-Start**: Schlägt `startForeground` fehl (`SecurityException` oder `ForegroundServiceStartNotAllowedException` ab API 31), gibt der Dienst keine Sperren frei, sondern beendet sich ohne Server-Start.
- **Berechtigungen**: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`, `CHANGE_NETWORK_STATE` (eine der laut Doku für `connectedDevice` nötigen Alternativen, normal, zur Installationszeit gewährt) und `ACCESS_LOCAL_NETWORK`. `POST_NOTIFICATIONS` ist deklariert, siehe Abschnitt „Benachrichtigungen“.
- **Lokales Netz**: `ACCESS_LOCAL_NETWORK` ist ab targetSdk 37 Pflicht (eingehend und ausgehend sowie NSD). Die Berechtigung gilt nur, wenn das System API 37 oder neuer ist **und** die App auf targetSdk 37 oder neuer zielt. Die Anfrage zur Laufzeit folgt in Paket 3.3. Der Dienst verweigert den Start in diesem Fall ohne die Berechtigung. Sonst gilt die Prüfung als erfüllt.
- **Benachrichtigungen**: Laufzeit-Anfrage für `POST_NOTIFICATIONS` folgt in Paket 3.3. Siehe Abschnitt „Benachrichtigungen“.
- **Build-Sperre**: Task `checkServerIsolation` (Convention-Plugin `emberlight.server.isolation`) prüft den aufgelösten Abhängigkeitsgraphen von `:share:server`, transitiv, über **alle auflösbaren Konfigurationen** (Compile, Runtime, Unit-Test, AndroidTest, alle Varianten). Verboten sind `:core:media`, `:app` und alle `:feature:*`. Begründung: `:app` und die Feature-Module sind Oberfläche und hängen selbst vom Server ab (Kreisbildung), `:core:media` ist die Galerie. Der Task `checkServerSources` prüft den Quelltext unter `share/server/src` auf Galerie- und Speicherzugriffe (`MediaStore`, `ContentResolver`, `contentResolver`, `READ_MEDIA_`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `getExternalStorage`, `getExternalFilesDir`, `getExternalMediaDirs`). Die Meldung nennt Datei und Zeile. Beide Tasks hängen in `check`. Die Negativfälle laufen per Gradle TestKit gegen ein Fixture-Projekt (`ServerIsolationPluginTest`).
- **Keine TLS-Verschlüsselung**: Der Server spricht HTTP. TLS im fremden WLAN ist für einen späteren Meilenstein vorgesehen (CLAUDE.md, Meilensteine, Punkt 6). App-zu-App-Verbindungen haben laut [KONZEPT.md](../KONZEPT.md), Abschnitt Verschlüsselung, eigene Regeln.
- **R8**: `share/server/consumer-rules.pro` enthält nur zwei `-dontwarn`-Zeilen für `java.lang.management`. Es wird keine Klasse behalten.
- **Einbindung in die App**: `app` bindet `:share:server` in allen Varianten ein, auch im Release. Der Release-Build prüft so R8 mit dem Server. Die APK wächst dadurch (siehe Messwerte).

## Spike-Befund: Multipart-Upload

Der Spike hat einen Upload als Multipart-Formular geprüft. Befund:

- Ohne eigene Angabe von `formFieldLimit` scheitert ein Upload an Ktors Standardgrenze von 50 MiB. Der Spike hat das Limit deshalb auf das Upload-Limit plus 64 KiB gesetzt.
- Das Limit für die Datei zählte der Spike-Code selbst beim Lesen. `formFieldLimit` begrenzt nur den Gesamtrahmen des Formulars.
- Der Spike nutzte 2 GiB als Limit.

Der Multipart-Pfad wird **nicht** übernommen (siehe Entscheidung „Upload“). Der Upload-Baustein `receiveSingleFile` ist mit dem Review aus dem Code entfernt. Er hatte keine Route und keinen Aufrufer.

## Änderungen nach dem Review (18.10.2026)

- **Upload-Baustein entfernt**: `UploadStore.kt` und `UploadStoreTest.kt` sind gelöscht. Upload folgt in Paket 3.1 als roher Request-Body (siehe Entscheidung).
- **Dienst gehärtet**: Start und Stopp sind serialisiert, Sperren werden nur vor Stopp erworben, `startForeground` ist abgefangen, die Berechtigung `ACCESS_LOCAL_NETWORK` gilt nur ab API 37 **und** targetSdk 37. Ein Kommentar zur Wakelock-Unterdrückung ist korrigiert: `connectedDevice` hat kein Zeitlimit.
- **Build-Sperre erweitert**: alle auflösbaren Konfigurationen, verbotene Projekte `:app` und `:feature:*`, neuer Quelltext-Task `checkServerSources`.
- **Logging**: KDoc von `requestLog` korrigiert (roher Pfad), Regel für Tokens und Schlüssel ergänzt.
- **Pflicht-Liste vor Paket 3.1** ergänzt (Abschnitt unten).
- **Honor-Messung**: als Beobachtung auf einem Gerät formuliert, Einschränkungen genannt.
- **POST_NOTIFICATIONS**: Der Befund „Lint verlangt die Deklaration“ ist per Probe bestätigt (Abschnitt „Benachrichtigungen“). Die Deklaration bleibt.

## Benachrichtigungen (POST_NOTIFICATIONS)

Die Deklaration `android.permission.POST_NOTIFICATIONS` bleibt im Manifest von `share:server`. Sie wird für die Laufzeit-Anfrage in Paket 3.3 gebraucht, und Android kann die Berechtigung nur anfragen, wenn sie deklariert ist.

Lint-Probe vom 18.10.2026: Die Deklaration wurde temporär entfernt und `./gradlew :share:server:lintDebug` ausgeführt. Ergebnis, wörtlich:

> Error: Missing permissions required by NotificationManagerCompat.notify: android.permission.POST_NOTIFICATIONS [MissingPermission]

Die Meldung bezieht sich auf den `notify`-Aufruf in `SpaceServerService`. Die `checkSelfPermission`-Prüfung davor hat Lint nicht als Absicherung erkannt. Die Deklaration ist also nötig, damit der Build grün bleibt, und die Laufzeit-Prüfung kommt zusätzlich.

## Änderungen gegenüber dem Entwurf (17.10.2026)

- Der Token ist aus `ServerConfig` und aus dem Server entfernt. Er gehört in ein späteres Paket. Der Standard verweigert alles.
- `/health` enthält nur Status und API-Version. Der Spike-Umfang (Prozess-ID, Startzeit, Sperrzustände, letzter Herzschlag) ist entfernt.
- Die Spike-Routen (`/spike/upload`, `/spike/files/{id}`) sind nicht übernommen. Der Upload-Baustein ist mit dem Review entfernt (siehe oben).
- Der Dienst heißt `SpaceServerService`, die Sperren `ServerLocks`. Der Debug-Einstieg, die Diagnose und das Selbstprotokoll sind nicht übernommen.
- Die Notiz, die Regel „Strikte Trennung von Space und Galerie“ stehe nicht in CLAUDE.md, ist überholt. Die Regel steht jetzt in CLAUDE.md und in KONZEPT.md.

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
| `checkServerIsolation` | grün. Negativfälle als TestKit-Tests: direkte Abhängigkeit auf `:core:media`, transitive Abhängigkeit über `:core:data`, Abhängigkeit auf `:feature:space` |
| `checkServerSources` | grün. Negativfall als TestKit-Test: verbotenes Token in einer Quelldatei bricht den Build mit Datei und Zeile ab |

### Tests

- `:share:server`: 29 Unit-Tests, alle grün (vorher 25).
  - `KtorLocalServerTest` (10, unverändert): `/health` nur mit Berechtigung, Standard verweigert alles, unbekannte Pfade geschützt, Request-Log, echter Socket auf Port 0 mit Start, 401, 200 und Stopp, zweiter Start abgelehnt, Port-Bereich.
  - `ServerLocksTest` (6, unverändert): Erwerb, Idempotenz, Freigabe, Freigabe ohne Erwerb, doppelte Freigabe, Rückabwicklung bei fehlgeschlagenem WLAN-Erwerb.
  - `SpaceServerServiceTest` (13, Robolectric, Server und Sperren als Fakes): Stopp-Intent; Start ohne `ACCESS_LOCAL_NETWORK` auf API 37 mit targetSdk 37 abgelehnt; Start ohne Berechtigung auf API 36 erlaubt; Start mit Berechtigung; unbekannter Aufruf und `null`-Intent liefern `START_NOT_STICKY` und beenden den Dienst; `onDestroy` gibt beide Sperren frei; fehlgeschlagener Serverstart und fehlgeschlagener WLAN-Erwerb räumen auf; Fehlerpfad von `startForeground` (`SecurityException`, `ForegroundServiceStartNotAllowedException`, unbekannte Ausnahme wird weitergereicht); Stopp während des Starts lässt keine Sperre hängen.
  - `UploadStoreTest` (6) entfällt mit dem Upload-Baustein.
- `:convention` (Build-Logik): 36 Tests, alle grün (vorher 23).
  - `ProjectIsolationGuardTest` (8, mit Präfix-Regel `:feature:*`), `SourceIsolationGuardTest` (5).
  - `ServerIsolationPluginTest` (5, Gradle TestKit gegen ein Java-Fixture-Projekt): sauberer Graph und saubere Quelltexte bestehen; direkte Abhängigkeit auf `:core:media`, transitive Abhängigkeit über `:core:data` und Abhängigkeit auf `:feature:space` brechen den Build; verbotenes Token im Quelltext bricht ihn mit Datei und Zeile.
  - `EmberlightVersionTest` (13), `PlayServicesGuardTest` (5), unverändert.
- Kover-Zeilenabdeckung gesamt 91,2 % (3734 von 4094 Zeilen), `share:server` 86,9 % (172 von 198). Untergrenze 70 %.
- Gesamtlauf `./gradlew check koverXmlReport assembleDebug assembleBenchmark assembleRelease` (lokal): grün, inklusive Spotless, Lint, Roborazzi-Verify, `testViewerGoldens`, Kover-Verify, `checkNoPlayServices`, `checkServerIsolation` und `checkServerSources`.

### APK-Größe (unsigniert, Release)

| Stand | Größe |
|---|---|
| Baseline, `main` (Code unverändert seit 8d04693, Messung des Spike-ADR) | 2 993 877 Byte |
| Mit Server, `feature/m3-server-basis` (vor dem Review) | 3 652 833 Byte (+658 956 Byte, +22,0 %) |
| Mit Server, nach dem Review | 3 652 833 Byte (gleiche Größe wie vorher. Der neue Code ist in der APK nachweislich enthalten, die Gleichheit der Größe ist nicht erklärt) |

Der Spike hatte gemessen: Der Server wächst die Release-APK um rund 0,66 MB (+22 %). Eine Aufschlüsselung nach Paketen fehlt weiterhin.

### R8

- Ohne Regel bricht der Release-Build ab: Ktor referenziert `java.lang.management.ManagementFactory` und `RuntimeMXBean` (Debugger-Erkennung in `IntellijIdeaDebugDetector`). Das Paket gibt es auf Android nicht.
- Eingetragen sind nur die zwei `-dontwarn`-Zeilen. Offen: Der Laufzeitpfad der Debugger-Erkennung ist auf Android nicht belegt.

## Gerätemessung (18.10.2026)

Gerät: Honor Magic V2 (VER-N49), Android 16, MagicOS, WLAN. **Ein Gerät, je ein Lauf.** Der Spike war in beiden Läufen mit einem Dienst im Typ `connectedDevice` aktiv, mit Akku-Ausnahme (deviceidle-Whitelist), PARTIAL_WAKE_LOCK und WifiLock. Herzschlag der App alle 5 s, Anfragen aus dem Netz.

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

**Einschränkungen**

- n = 1 Gerät, je ein Lauf. Eine Wiederholung und ein zweites Gerät fehlen.
- Lauf 2 dauerte 22 min. Das ist kürzer als die längste Lücke in Lauf 1 (37 min). Die erste Lücke in Lauf 1 begann aber schon 1 s nach dem Abstecken. Der Unterschied zwischen den Läufen ist deshalb nicht allein die Dauer.
- Der Dialog „App-Start“ hat einen Schalter „Automatisch verwalten“ und drei Unterschalter (Automatischer Start, Sekundärer Start, Im Hintergrund ausführen). Die Messung hat die Unterschalter nicht einzeln geprüft. Welcher Schalter wirkt, ist offen.

**Deutung (Hypothese, keine belegte Ursache):** MagicOS hat Apps im Akkubetrieb trotz Foreground-Service, Wakelock und Akku-Ausnahme eingefroren, solange „App-Start“ automatisch verwaltet wird. Belegt ist nur die Beobachtung der beiden Läufe. Ob die App-Start-Einstellung allein reicht, ist offen.

## Konsequenzen

- Der Server mit Ktor CIO ist machbar. Build, Lint, Tests und R8 sind grün.
- Auf dem Honor-Gerät blieben die Anfragen mit Akku-Ausnahme allein im Akkubetrieb aus (Lauf 1). Mit zusätzlicher Hintergrund-Freigabe kamen sie durch (Lauf 2). Das ist eine Beobachtung auf einem Gerät mit je einem Lauf, siehe Einschränkungen. Andere Hersteller können eigene Sperren haben.
- Paket 3.3 bekommt einen Einrichtungs-Ablauf:
  - Akku-Ausnahme
  - herstellerspezifische Hintergrund-Freigabe, Honor zuerst. Huawei, Xiaomi und weitere als Ausbaustufe
  - Ladegerät-Empfehlung
  - erneute Prüfung beim Space-Start
  - Laufzeitanfragen für `ACCESS_LOCAL_NETWORK` und `POST_NOTIFICATIONS`
- **Pausen-Erkennung**: Der Dienst führt einen eigenen Herzschlag und erkennt Lücken (Laufzeit gegen Erwartung). Erkennt er eine Pause, zeigt er einen Hinweis, dass das System emberlight pausiert hat, mit Weg zur Einstellung. Hersteller-Einstellungen lassen sich nicht per API abfragen, deshalb läuft die Erkennung über die Laufzeit.
- Die Build-Sperre schützt die Modulgrenze im Build, transitiv, und den Quelltext des Servers.

## Pflicht vor Paket 3.1

Diese Punkte müssen erfüllt und getestet sein, bevor Paket 3.1 Routen einführt.

- **Erreichbarkeit nur über WLAN bzw. Hotspot**: Der Server bindet an die Adresse der WLAN- bzw. Hotspot-Schnittstelle, oder er lehnt Verbindungen aus fremden Netzen und über Mobilfunk ab. Heute bindet er auf `0.0.0.0`. In Paket 3.0 ist das unkritisch: `DenyAll`, keine Route, kein Startpunkt in der Oberfläche.
- **Explizite CIO-Grenzen**: maximale gleichzeitige Verbindungen, Header- und Request-Timeouts sowie Body-Limits werden gesetzt und getestet.
- **Kein Ktor-Plugin vor der Zugriffsprüfung**: Kein Plugin darf in den Phasen `Plugins` oder `Fallback` antworten, bevor die Zugriffsprüfung gelaufen ist. Test: eine unautorisierte Anfrage liefert auch dann 401, wenn ein Plugin sonst antworten würde.
- **Downloads**: Antworten mit `X-Content-Type-Options: nosniff`, einem bereinigten Dateinamen in `Content-Disposition` und einem korrekten `Content-Type`.
- **Mehrfache Authorization-Header**: Der Server lehnt die Anfrage ab (401), statt den ersten Wert zu nehmen.
- **Stopp in der App**: Vor dem Start aus der Oberfläche (Paket 3.3) gibt es einen Stopp-Weg in der App. Die Benachrichtigung allein genügt nicht.
- **Logging**: Route-Templates statt roher URIs, Tokens nur in Headern (siehe Entscheidung).

### Pflicht-Tests für den Upload (Paket 3.1)

- Upload bei `DenyAll` liefert 401, es entsteht keine Datei.
- Chunked Upload ohne `Content-Length` wird am Byte-Limit abgebrochen.
- Zu kleines `Content-Length` bei großem Körper wird am Byte-Limit abgebrochen, nicht am angegebenen Wert.
- Verbindungsabbruch mitten im Upload: die Teildatei wird gelöscht.
- Ein Ziel außerhalb von `SpaceStorage` (z. B. über `..` oder einen Symlink) wird abgewiesen.

## Offene Punkte und Risiken

- **Langzeitverhalten**: Dauerbetrieb über Stunden, Nacht und Deep Doze ist nicht gemessen. Die Prüfung folgt mit der echten Space-Funktion auf dem Gerät.
- **Gerätemessung**: n = 1 Gerät, je ein Lauf (siehe Einschränkungen).
- **Android 8 (API 26)**: Ktor und seine Abhängigkeiten sind dort zur Laufzeit nicht geprüft. Lint erkennt fehlende Java-APIs nur zum Teil, ein Fehlen zeigt sich also erst zur Laufzeit.
- **Engine-Kompatibilität**: Eine ausdrückliche Android-Freigabe für CIO habe ich nicht gefunden. Die Laufzeit auf Android ist nur auf dem Honor-Gerät getestet.
- **Android 17 Laufzeitanfrage**: Der Ablauf zu `ACCESS_LOCAL_NETWORK` ist nicht am Gerät getestet, weil die Oberfläche fehlt. Der Start ohne Berechtigung auf API 37 ist per Robolectric geprüft, nicht am Gerät.
- **Prozess-Tod**: Der Dienst startet nach einem Prozess-Tod nicht neu (`START_NOT_STICKY`). Ein Gast verliert dann die Verbindung ohne Hinweis.
- **WifiLock-Modus**: `WIFI_MODE_FULL_HIGH_PERF` ist als veraltet markiert. Die Warnung ist mit `@Suppress("DEPRECATION")` unterdrückt. Ein Ersatz ohne Einschränkung ist nicht bekannt.
- **Schutz gegen Zugriff**: Ohne Zugriffsprüfung antwortet der Server nur mit 401. Vor dem ersten Routen-Einsatz muss Paket 3.x die Prüfung liefern.
- **TLS**: Der Server spricht HTTP (siehe Entscheidung).
- **Gerätetest**: Der Hintergrundbetrieb wird im Gerätetest mit der echten Space-Funktion geprüft, nicht mit dem Spike.
- **Core-Data-Grenze**: `:core:data` ist nicht gesperrt. Es darf den Server nur über `:core:media` erreichen, was die Sperre abfängt. Ob `:core:data` selbst Galerie-Daten hält, prüft Paket 3.1.

## Alternativen

- **Andere Engines (Netty, Jetty)**: nicht geprüft. Die Wahl von CIO folgt CLAUDE.md (Technik: „Eingebetteter Server: Ktor Server (CIO)“).
- **Upload über `dataSync`**: verworfen, wegen des Sechs-Stunden-Limits (KONZEPT).
- **Upload als Multipart-Formular**: verworfen für Paket 3.1. Das Limit hängt an Ktors `formFieldLimit`, und der Parser gehört nicht zur eigenen Zählung (siehe Spike-Befund).
- **Dateiname des Clients übernehmen**: verworfen, weil damit Pfad-Angriffe und Datenlecks möglich werden.
- **Standardmäßig offener Zugang**: verworfen. Der Server verweigert ohne Prüfung alles.

## Quellen

- Spike-Stand: Branch `spike/m3-server`, PR #23 (Draft).
- Ktor-Artefakte (Maven Central): [ktor-server-core 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-core-jvm/3.6.0/ktor-server-core-jvm-3.6.0.pom), [ktor-server-cio 3.6.0 POM](https://repo1.maven.org/maven2/io/ktor/ktor-server-cio-jvm/3.6.0/ktor-server-cio-jvm-3.6.0.pom)
- Android-Doku, Foreground-Service-Typen (connectedDevice, specialUse, dataSync): [developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Android-Doku, Berechtigung für lokales Netz: [developer.android.com](https://developer.android.com/privacy-and-security/local-network-permission)
