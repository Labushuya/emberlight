# Debug-Server für Gerätetests

Stand: 18.10.2026, Paket 3.1a. Grundlage: [ADR 0014](adr/0014-space-speicher-und-server-api.md), Abschnitt „Debug-Einstieg für Gerätetests“.

Der Einstieg startet den lokalen Server mit einem Test-Space, ohne Oberfläche. Er dient nur dem Gerätetest. Es gibt ihn ausschließlich im Debug-Build (Source-Set `app/src/debug`). Release und Benchmark enthalten ihn nicht. Die Build-Prüfung `checkReleaseManifest` bricht ab, falls das Release-Manifest ihn enthält.

## Was der Einstieg tut

- START legt einen zufälligen Space an, mit einem Gastgeber und optional einem Gast.
- Jeder Teilnehmer bekommt einen zufälligen Token (256 bit). Im Speicher der App liegen nur die SHA-256-Hashes.
- Die Klartext-Tokens stehen in `files/debug-server/tokens.json` im App-internen Speicher. Die Datei ist nur per `run-as` lesbar, und nur in einem Debug-Build.
- Tokens erscheinen nie im Log.
- Der Server bindet an die WLAN-Adresse des Geräts und läuft als Vordergrunddienst. Port standardmäßig 8787.
- STOP beendet den Server.

## Sicherheit

Der Empfänger ist im Debug-Build exportiert, damit `adb` ihn ansprechen kann. Jede App auf demselben Gerät könnte ihn dann ebenfalls auslösen. Deshalb nur auf dem Testgerät verwenden, nicht auf einem Alltagsgerät mit Debug-Build.

## Befehle

Platzhalter in spitzen Klammern ersetzen. Echte Tokens gehören nicht in Dokumente, Logs oder Commits.

Start, mit Gast und Port:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --ei port 8787 --ez guest true
```

Ohne Gast: `--ez guest true` weglassen.

WLAN-Adresse ermitteln. Der Dienst protokolliert sie im Debug-Build:

```
adb logcat -s EmberlightServer:I
```

Die Zeile „Debug: gebunden an `<ip>:<port>`“ nennt Adresse und Port.

Tokens lesen:

```
adb shell run-as io.github.labushuya.emberlight cat files/debug-server/tokens.json
```

Die Datei enthält `spaceId`, `port`, je Teilnehmer `participantId` und `token` unter `host` bzw. `guest`.

Stop:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_STOP -n io.github.labushuya.emberlight/io.github.labushuya.emberlight.debugserver.DebugServerReceiver
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

- Der Start über den Broadcast kann auf Android 12 und neuer scheitern, wenn die App im Hintergrund ist und der Systemstart keine Ausnahme hat. In dem Fall steht der Grund im Logcat (`startForeground verweigert`). Dann die App einmal öffnen und den Befehl wiederholen.
- Der Dienst startet nach einem Prozess-Tod nicht neu. Ein Netzwechsel stoppt den Server, das ist Absicht (ADR 0013).
- Die Gerätedaten (Tokens, Medien) bleiben im App-Speicher, bis die App-Daten gelöscht werden.
