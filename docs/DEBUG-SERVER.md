# Debug-Server für Gerätetests

Stand: 19.10.2026, Paket 3.1a. Grundlage: [ADR 0014](adr/0014-space-speicher-und-server-api.md), Abschnitt „Debug-Einstieg für Gerätetests“ und „Umsetzung 3.1a“.

Der Einstieg startet den lokalen Server mit einem Test-Space, ohne Oberfläche. Er dient nur dem Gerätetest. Der Quelltext liegt im Source-Set `app/src/devtools`, das nur Debug- und Benchmark-Build einbinden. Der Release-Build enthält ihn nicht. Die Build-Prüfung `checkReleaseManifestDebugEntry` bricht ab, falls das Release-Manifest ihn enthält. Das Manifest des Benchmark-Builds enthält den Empfänger; so prüft R8 den Serverpfad im Benchmark-Build (ADR 0012, Nachtrag 19.10.2026).

Paketnamen für alle Befehle unten:

- Debug-Build: `io.github.labushuya.emberlight.debug` (applicationId mit Suffix `.debug`, siehe `AndroidApplicationConventionPlugin`)
- Benchmark-Build: `io.github.labushuya.emberlight.benchmark` (applicationId mit Suffix `.benchmark`, siehe `app/build.gradle.kts`)
- Der Namespace bleibt `io.github.labushuya.emberlight`. Die Empfängerklasse heißt daher in beiden Builds `io.github.labushuya.emberlight.debugserver.DebugServerReceiver`, ohne Suffix. Das gemergte Manifest bestätigt das: `.debugserver.DebugServerReceiver` relativ zum Namespace.

## Was der Einstieg tut

- START legt einen zufälligen Space an, mit einem Gastgeber und optional einem Gast.
- Jeder Teilnehmer bekommt einen zufälligen Token (256 bit). Im Speicher der App liegen nur die SHA-256-Hashes.
- Die Klartext-Tokens stehen in `files/debug-server/tokens.json` im App-internen Speicher. Die Datei ist nur per `run-as` lesbar, und `run-as` gibt es nur für debuggable Builds. Das trifft den Debug-Build, nicht den Benchmark-Build (siehe „Bekannte Grenzen“).
- Tokens erscheinen nie im Log.
- Der Server bindet an die WLAN-Adresse des Geräts und läuft als Vordergrunddienst. Port standardmäßig 8787.
- STOP beendet den Server.

## Sicherheit

Der Empfänger ist im Debug- und Benchmark-Build exportiert, damit `adb` ihn ansprechen kann. Er hat deshalb `android:permission="android.permission.DUMP"`. Diese Berechtigung halten die Shell (also `adb`) und System-Identitäten, normale Apps nicht. Eine normale App kann den Empfänger damit nicht auslösen. Der Schutz gilt nur, solange die Berechtigung nicht an normale Apps vergeben wird. Die Schutzstufe ist laut AOSP-Manifest `signature|privileged|development`.

Restrisiko: Wer `adb`-Zugriff auf das Gerät hat (USB-Debugging), kann den Server starten und stoppen. Das ist beabsichtigt. Deshalb trotzdem nur auf dem Testgerät verwenden, nicht auf einem Alltagsgerät mit Debug- oder Benchmark-Build.

Nicht getestet: das Verhalten gegenüber einer echten Drittapp (ohne Testapp auf dem Gerät nicht belegt).

## Befehle

Platzhalter in spitzen Klammern ersetzen. Echte Tokens gehören nicht in Dokumente, Logs oder Commits.

Start, mit Gast und Port:

Debug-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.debug/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --ei port 8787 --ez guest true
```

Benchmark-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.benchmark/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --ei port 8787 --ez guest true
```

Ohne Gast: `--ez guest true` weglassen.

WLAN-Adresse ermitteln. Der Dienst protokolliert sie im Debug- und Benchmark-Build. Der Befehl ist für beide Pakete gleich, der Logeintrag hängt nicht am Paketnamen:

```
adb logcat -s EmberlightServer:I
```

Die Zeile „Debug: gebunden an `<ip>:<port>`“ nennt Adresse und Port.

Tokens lesen:

Debug-Build:

```
adb shell run-as io.github.labushuya.emberlight.debug cat files/debug-server/tokens.json
```

Benchmark-Build (funktioniert nicht, siehe „Bekannte Grenzen“):

```
adb shell run-as io.github.labushuya.emberlight.benchmark cat files/debug-server/tokens.json
```

Die Datei enthält `spaceId`, `port`, je Teilnehmer `participantId` und `token` unter `host` bzw. `guest`.

Stop:

Debug-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_STOP -n io.github.labushuya.emberlight.debug/io.github.labushuya.emberlight.debugserver.DebugServerReceiver
```

Benchmark-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_STOP -n io.github.labushuya.emberlight.benchmark/io.github.labushuya.emberlight.debugserver.DebugServerReceiver
```

## Testaufrufe vom PC im selben WLAN

Der Host-Header muss exakt `<ip>:<port>` sein. Sonst antwortet der Server mit `403`.

```
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <token>" http://<ip>:<port>/health
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <token>" http://<ip>:<port>/api/v1/space
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <token>" -H "Content-Type: application/octet-stream" -H "X-Emberlight-Filename: test.jpg" --data-binary @test.jpg http://<ip>:<port>/api/v1/media
```

Erwartet: `/health` liefert `200` mit `{"status":"ok","apiVersion":1}`. Der Upload liefert `201` für ein erlaubtes Format. Ohne `Authorization` oder mit fremdem Host kommt `401` bzw. `403`.

## Bekannte Grenzen

- Der Benchmark-Build ist nicht debuggable (`isDebuggable = false`). Nach dem Android-Verhalten verweigert `run-as` dort den Zugriff (am Gerät nicht geprüft, Befehle ohne Gerätetest). Die Datei `tokens.json` ist im Benchmark-Build also nicht per Befehl lesbar. Offener Punkt: entweder Token-Ausgabe anders lösen oder den Benchmark-Build nur mit Tokens aus dem Debug-Build testen. Die Entscheidung liegt beim Nutzer. Der Start und Stopp über den Empfänger funktionieren unabhängig davon.
- Der Start über den Broadcast kann auf Android 12 und neuer scheitern, wenn die App im Hintergrund ist und der Systemstart keine Ausnahme hat. In dem Fall steht der Grund im Logcat (`startForeground verweigert`). Dann die App einmal öffnen und den Befehl wiederholen.
- Der Dienst startet nach einem Prozess-Tod nicht neu. Ein Netzwechsel stoppt den Server, das ist Absicht (ADR 0013).
- Die Gerätedaten (Tokens, Medien) bleiben im App-Speicher, bis die App-Daten gelöscht werden.
