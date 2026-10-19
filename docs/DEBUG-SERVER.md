# Debug-Server für Gerätetests

Stand: 19.10.2026, Paket 3.1a. Grundlage: [ADR 0014](adr/0014-space-speicher-und-server-api.md), Abschnitt „Debug-Einstieg für Gerätetests“ und „Umsetzung 3.1a“.

Der Einstieg startet den lokalen Server mit einem Test-Space, ohne Oberfläche. Er dient nur dem Gerätetest. Der Quelltext liegt im Source-Set `app/src/devtools`, das nur Debug- und Benchmark-Build einbinden. Der Release-Build enthält ihn nicht. Die Build-Prüfung `checkReleaseManifestDebugEntry` bricht ab, falls das Release-Manifest ihn enthält. Das Manifest des Benchmark-Builds enthält den Empfänger; so prüft R8 den Serverpfad im Benchmark-Build (ADR 0012, Nachtrag 19.10.2026).

Paketnamen für alle Befehle unten:

- Debug-Build: `io.github.labushuya.emberlight.debug` (applicationId mit Suffix `.debug`, siehe `AndroidApplicationConventionPlugin`)
- Benchmark-Build: `io.github.labushuya.emberlight.benchmark` (applicationId mit Suffix `.benchmark`, siehe `app/build.gradle.kts`)
- Der Namespace bleibt `io.github.labushuya.emberlight`. Die Empfängerklasse heißt daher in beiden Builds `io.github.labushuya.emberlight.debugserver.DebugServerReceiver`, ohne Suffix.

Beide Pakete haben denselben Ablauf. Der Benchmark-Build ist nicht debuggable. Der Ablauf braucht deshalb kein `run-as` mehr.

## Was der Einstieg tut

- START legt einen Test-Space an, mit einem Gastgeber (Pflicht) und optional einem Gast.
- Die Tokens kommen vom PC, als Extras des Broadcasts. Die App erzeugt keine Tokens und schreibt keine Token-Datei.
- Jeder Token ist ein Zugangsschlüssel aus 256 bit, als 64 Zeichen `0-9` und `a-f`. Gültig ist nur Kleinschreibung.
- Die App speichert nur den SHA-256-Hash jedes Tokens (ADR 0013). Das gilt für den Speicher des Servers und für den Space-Index unter `noBackupFilesDir/spaces`. Klartext-Tokens liegen nirgends auf dem Gerät.
- Tokens und Extras erscheinen nie im Log der App.
- Der Server bindet an die WLAN-Adresse des Geräts und läuft als Vordergrunddienst. Port standardmäßig 8787.
- STOP beendet den Server.

### Ablehnung

Der Start findet nicht statt, wenn:

- `host_token` fehlt oder kein gültiger Token ist,
- `guest_token` angegeben ist, aber kein gültiger Token ist (auch ein leerer Wert zählt als ungültig),
- `host_token` und `guest_token` gleich sind,
- `port` außerhalb von 0 bis 65535 liegt.

Die Ablehnung steht als Fehler im Logcat unter dem Tag `EmberlightServer`, ohne den Wert des Extras.

## Tokens auf dem PC erzeugen

Pro Teilnehmer ein eigener Token. Die Erzeugung muss kryptografisch sein.

PowerShell (Windows PowerShell 5.1 und PowerShell 7):

```
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
```

Bash (Git Bash, Linux, macOS):

```
openssl rand -hex 32
```

Alternativ ohne openssl: `head -c 32 /dev/urandom | xxd -p -c 64`

Die Ausgabe ist genau 64 Zeichen Kleinbuchstaben und Ziffern. Mit einer Zeile Länge oder anderen Zeichen lehnt die App den Start ab.

**Hinweis zur Sicherheit:** Die Tokens stehen während des Tests kurz in der Shell-Historie und in der Prozessliste des PCs (`adb`-Argumente). Der Empfänger ist mit `android.permission.DUMP` geschützt. Der Einstieg ist nur für Tests gedacht. Nach dem Test die Tokens verwerfen, also die Shell schließen und, falls nötig, die Historie bereinigen. Nie in Dokumente, Logs oder Commits übernehmen.

## Befehle

Platzhalter in spitzen Klammern ersetzen. `<host-token>` und `<guest-token>` sind die 64-Zeichen-Werte von oben.

Start, nur Gastgeber:

Debug-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.debug/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --es host_token <host-token> --ei port 8787
```

Benchmark-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.benchmark/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --es host_token <host-token> --ei port 8787
```

Start, mit Gast: zusätzlich `--es guest_token <guest-token>` angeben. Die beiden Tokens müssen verschieden sein. Ohne `guest_token` gibt es keinen Gast.

Der Port ist optional (`--ei port`, Standard 8787).

WLAN-Adresse ermitteln. Der Dienst protokolliert sie im Debug- und Benchmark-Build. Der Befehl ist für beide Pakete gleich, der Logeintrag hängt nicht am Paketnamen:

```
adb logcat -s EmberlightServer:I
```

Die Zeile „Debug: gebunden an `<ip>:<port>`“ nennt Adresse und Port. Ablehnungen stehen als Fehler unter `EmberlightServer:E`.

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

Der Host-Header muss exakt `<ip>:<port>` sein. Sonst antwortet der Server mit `403`. Als Bearer-Token den Wert des Teilnehmers verwenden, den man testen will: `<host-token>` für den Gastgeber, `<guest-token>` für den Gast.

```
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" http://<ip>:<port>/health
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" http://<ip>:<port>/api/v1/space
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" -H "Content-Type: application/octet-stream" -H "X-Emberlight-Filename: test.jpg" --data-binary @test.jpg http://<ip>:<port>/api/v1/media
```

Erwartet: `/health` liefert `200` mit `{"status":"ok","apiVersion":1}`. Der Upload liefert `201` für ein erlaubtes Format. Ohne `Authorization` oder mit fremdem Host kommt `401` bzw. `403`.

## Sicherheit

Der Empfänger ist im Debug- und Benchmark-Build exportiert, damit `adb` ihn ansprechen kann. Er hat deshalb `android:permission="android.permission.DUMP"`. Diese Berechtigung halten die Shell (also `adb`) und System-Identitäten, normale Apps nicht. Eine normale App kann den Empfänger damit nicht auslösen. Der Schutz gilt nur, solange die Berechtigung nicht an normale Apps vergeben wird. Die Schutzstufe ist laut AOSP-Manifest `signature|privileged|development`.

Restrisiko: Wer `adb`-Zugriff auf das Gerät hat (USB-Debugging), kann den Server starten und stoppen und die Test-Tokens setzen. Das ist beabsichtigt. Deshalb trotzdem nur auf dem Testgerät verwenden, nicht auf einem Alltagsgerät mit Debug- oder Benchmark-Build.

Nicht getestet: das Verhalten gegenüber einer echten Drittapp (ohne Testapp auf dem Gerät nicht belegt). Ebenfalls nicht am Gerät geprüft: ob Android die Extras des Broadcasts in seinen eigenen Systemlogs ausgibt. Die App selbst loggt sie nicht.

## Bekannte Grenzen

- Der Start über den Broadcast kann auf Android 12 und neuer scheitern, wenn die App im Hintergrund ist und der Systemstart keine Ausnahme hat. In dem Fall steht der Grund im Logcat (`startForeground verweigert`). Dann die App einmal öffnen und den Befehl wiederholen.
- Der Dienst startet nach einem Prozess-Tod nicht neu. Ein Netzwechsel stoppt den Server, das ist Absicht (ADR 0013).
- Die Hashes der Test-Teilnehmer bleiben im Space-Index, bis die App-Daten gelöscht werden. Klartext-Tokens gibt es auf dem Gerät nicht. Wer einen Token verliert, startet den Test mit einem neuen.
- Die Medien im Test-Space bleiben im App-Speicher, bis die App-Daten gelöscht werden.
