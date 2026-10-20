# Debug-Server für Gerätetests

Stand: 19.10.2026, Paket 3.1a, Nachtrag Korrekturen (Hash-Übergabe). Nachtrag 20.10.2026 (Paket 3.3): Netzwechsel und Pausen, siehe Bekannte Grenzen. Grundlage: [ADR 0014](adr/0014-space-speicher-und-server-api.md), Abschnitt „Debug-Einstieg für Gerätetests“ und „Umsetzung 3.1a“.

Der Einstieg startet den lokalen Server mit einem Test-Space, ohne Oberfläche. Er dient nur dem Gerätetest. Der Quelltext liegt im Source-Set `app/src/devtools`, das nur Debug- und Benchmark-Build einbinden. Der Release-Build enthält ihn nicht. Die Build-Prüfung `checkReleaseManifestDebugEntry` bricht ab, falls das Release-Manifest ihn enthält. Das Manifest des Benchmark-Builds enthält den Empfänger; so prüft R8 den Serverpfad im Benchmark-Build (ADR 0012, Nachtrag 19.10.2026).

Paketnamen für alle Befehle unten:

- Debug-Build: `io.github.labushuya.emberlight.debug` (applicationId mit Suffix `.debug`, siehe `AndroidApplicationConventionPlugin`)
- Benchmark-Build: `io.github.labushuya.emberlight.benchmark` (applicationId mit Suffix `.benchmark`, siehe `app/build.gradle.kts`)
- Der Namespace bleibt `io.github.labushuya.emberlight`. Die Empfängerklasse heißt daher in beiden Builds `io.github.labushuya.emberlight.debugserver.DebugServerReceiver`, ohne Suffix.

Beide Pakete haben denselben Ablauf. Der Benchmark-Build ist nicht debuggable. Der Ablauf braucht deshalb kein `run-as`.

## Was der Einstieg tut

- START legt einen Test-Space an, mit einem Gastgeber (Pflicht) und optional einem Gast.
- Der PC übergibt nur den SHA-256-Hash jedes Tokens, als Extra des Broadcasts. Der Klartext-Token verlässt den PC nur im HTTP-Header `Authorization`. Die App erzeugt keine Tokens und schreibt keine Token-Datei.
- Jeder Hash ist 64 Zeichen `0-9` und `a-f`. Gültig ist nur Kleinschreibung.
- Die App speichert nur den Hash (ADR 0013). Das gilt für den Speicher des Servers und für den Space-Index unter `noBackupFilesDir/spaces`. Klartext-Tokens liegen nirgends auf dem Gerät.
- Hashes und Extras erscheinen nie im Log der App.
- Der Server bindet an die WLAN-Adresse des Geräts und läuft als Vordergrunddienst. Port standardmäßig 8787.
- STOP beendet den Server.

### Ablehnung

Der Start findet nicht statt, wenn:

- `host_token_sha256` fehlt oder kein gültiger Hash ist,
- `guest_token_sha256` angegeben ist, aber kein gültiger Hash ist (auch ein leerer Wert zählt als ungültig),
- `host_token_sha256` und `guest_token_sha256` gleich sind,
- `port` außerhalb von 0 bis 65535 liegt.

Die alten Extras `host_token` und `guest_token` sind entfallen. Sie werden ohne Rückfall ignoriert, und ein Start ohne `host_token_sha256` wird abgelehnt.

Die Ablehnung steht als Fehler im Logcat unter dem Tag `EmberlightServer`, ohne den Wert des Extras.

## Tokens erzeugen und Hashes bilden

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
TOKEN=$(openssl rand -hex 32)
```

Alternativ ohne openssl: `head -c 32 /dev/urandom | xxd -p -c 64`

Die Ausgabe ist genau 64 Zeichen Kleinbuchstaben und Ziffern.

### Hash-Berechnung

Der Server prüft einen Token so: Er nimmt den Wert nach `Bearer ` aus dem Header `Authorization`, bildet den SHA-256 über dessen UTF-8-Bytes (ohne Zeilenende, ohne BOM) und vergleicht ihn mit dem gespeicherten Hash. Die PC-Berechnung muss genau dasselbe tun. Der Hash ist daher die lowercase-Hex-Darstellung des SHA-256 über die UTF-8-Bytes des Token-Strings, 64 Zeichen.

Bash (Git Bash, Linux, macOS), mit `printf` statt `echo`, weil `echo` ein Zeilenende anhängen kann:

```
HOST_HASH=$(printf '%s' "$HOST_TOKEN" | sha256sum | cut -d' ' -f1)
```

PowerShell 5.1 und 7:

```
$hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($token))
$hostHash = -join ($hash | ForEach-Object { $_.ToString('x2') })
```

Beide Varianten ergeben für denselben Token denselben Hash. Das ist geprüft (Testwert `host-test-token`).

**Hinweis zur Sicherheit:** Die Hashes stehen während des Tests kurz in der Shell-Historie und in den `adb`-Argumenten. Ein Hash erlaubt keinen Rückschluss auf den Token, und der Klartext-Token erscheint auf dem Gerät und in den Argumenten nie. Der Empfänger ist mit `android.permission.DUMP` geschützt. Der Einstieg ist nur für Tests gedacht. Nach dem Test die Shell schließen und, falls nötig, die Historie bereinigen. Tokens nie in Dokumente, Logs oder Commits übernehmen.

## Befehle

Platzhalter in spitzen Klammern ersetzen. `<host-hash>` und `<guest-hash>` sind die 64-Zeichen-Hashes von oben. Die Tokens selbst brauchen die Befehle nicht.

Start, nur Gastgeber:

Debug-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.debug/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --es host_token_sha256 <host-hash> --ei port 8787
```

Benchmark-Build:

```
adb shell am broadcast -a io.github.labushuya.emberlight.DEBUG_SERVER_START -n io.github.labushuya.emberlight.benchmark/io.github.labushuya.emberlight.debugserver.DebugServerReceiver --es host_token_sha256 <host-hash> --ei port 8787
```

Start, mit Gast: zusätzlich `--es guest_token_sha256 <guest-hash>` angeben. Die beiden Hashes müssen verschieden sein. Ohne `guest_token_sha256` gibt es keinen Gast.

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

## Ablauf in einer Sitzung

1. Zwei Tokens erzeugen und in Shell-Variablen halten (`HOST_TOKEN`, `GUEST_TOKEN`).
2. Die Hashes bilden (`HOST_HASH`, `GUEST_HASH`, Abschnitt oben).
3. Den Start-Broadcast mit `--es host_token_sha256 $HOST_HASH` und `--es guest_token_sha256 $GUEST_HASH` senden.
4. Die WLAN-Adresse aus dem Logcat lesen.
5. Tests mit dem Klartext-Token im Header `Authorization` ausführen (nächster Abschnitt).
6. Stop-Broadcast senden, Shell schließen.

Die Klartext-Tokens verlassen den PC nur im HTTP-Header. Sie gehen nicht in `adb`-Befehle, nicht in Dateien und nicht ins Gerät.

## Testaufrufe vom PC im selben WLAN

Der Host-Header muss exakt `<ip>:<port>` sein. Sonst antwortet der Server mit `403`. Als Bearer-Token den Klartext-Token des Teilnehmers verwenden, den man testen will: `<host-token>` für den Gastgeber, `<guest-token>` für den Gast. Der Token passt nur zu seinem Hash, den der Start übergeben hat.

```
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" http://<ip>:<port>/health
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" http://<ip>:<port>/api/v1/space
curl -i -H "Host: <ip>:<port>" -H "Authorization: Bearer <host-token>" -H "Content-Type: application/octet-stream" -H "X-Emberlight-Filename: test.jpg" --data-binary @test.jpg http://<ip>:<port>/api/v1/media
```

Erwartet: `/health` liefert `200` mit `{"status":"ok","apiVersion":1}`. Der Upload liefert `201` für ein erlaubtes Format. Ohne `Authorization`, mit falschem Token oder mit fremdem Host kommt `401` bzw. `403`.

Ein Upload ohne erlaubte Signatur wird nach den ersten Bytes mit `415` abgelehnt. Die Antwort trägt `Connection: close`. Der Client muss dann eine neue Verbindung öffnen.

## Sicherheit

Der Empfänger ist im Debug- und Benchmark-Build exportiert, damit `adb` ihn ansprechen kann. Er hat deshalb `android:permission="android.permission.DUMP"`. Diese Berechtigung halten die Shell (also `adb`) und System-Identitäten, normale Apps nicht. Eine normale App kann den Empfänger damit nicht auslösen. Der Schutz gilt nur, solange die Berechtigung nicht an normale Apps vergeben wird. Die Schutzstufe ist laut AOSP-Manifest `signature|privileged|development`.

Restrisiko: Wer `adb`-Zugriff auf das Gerät hat (USB-Debugging), kann den Server starten und stoppen. Das ist beabsichtigt. Deshalb trotzdem nur auf dem Testgerät verwenden, nicht auf einem Alltagsgerät mit Debug- oder Benchmark-Build.

Nicht getestet: das Verhalten gegenüber einer echten Drittapp (ohne Testapp auf dem Gerät nicht belegt). Die App selbst loggt weder Hashes noch Extras. Ob Android die Extras von Broadcasts in seinen eigenen Systemlogs ausgibt, ist am Gerät zu messen (Ergebnis siehe Abschnitt „Gerätetest“ unten, sobald vorhanden).

## Bekannte Grenzen

- Der Start über den Broadcast kann auf Android 12 und neuer scheitern, wenn die App im Hintergrund ist und der Systemstart keine Ausnahme hat. In dem Fall steht der Grund im Logcat (`startForeground verweigert`). Dann die App einmal öffnen und den Befehl wiederholen.
- Der Dienst startet nach einem Prozess-Tod nicht neu. Ein Netzwechsel pausiert den Server (ADR 0013). Dasselbe Netz mit derselben IPv4-Adresse setzt ihn automatisch fort. Ein anderes Netz braucht die Bestätigung in der App (ADR 0017, Paket 3.3).
- Die Hashes der Test-Teilnehmer bleiben im Space-Index, bis die App-Daten gelöscht werden. Klartext-Tokens gibt es auf dem Gerät nicht. Wer einen Token verliert, startet den Test mit einem neuen.
- Die Medien im Test-Space bleiben im App-Speicher, bis die App-Daten gelöscht werden.
