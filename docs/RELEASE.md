# Release

Dieses Dokument beschreibt, wie emberlight als signierte APK über GitHub Releases erscheint. Die Entscheidungen dahinter stehen in [ADR 0004](adr/0004-versionierung-und-release.md).

## 1. Überblick

Der Workflow `.github/workflows/release.yml` hat zwei Auslöser:

- **Tag `vX.Y.Z`** veröffentlicht ein GitHub-Release mit `emberlight-vX.Y.Z.apk` und `emberlight-vX.Y.Z.apk.sha256`. Der Tag muss auf einem Commit in `main` liegen.
- **Manueller Lauf** (`workflow_dispatch`) mit der Eingabe `version` ist immer ein Probelauf. Er baut, signiert und prüft die APK und legt das Artefakt `release-dry-run` ab. Es entstehen kein Release und kein Tag.

Der Lauf bricht ab, wenn die Signierung nicht eingerichtet ist, die Version ungültig ist oder die APK die Prüfung nicht besteht. Geprüft werden Signatur, Paketname, `versionName`, `versionCode` und, wenn gesetzt, das Zertifikat.

## 2. Signierschlüssel

Benötigt werden vier Repository-Secrets und eine Repository-Variable:

| Name | Art | Inhalt |
|---|---|---|
| `KEYSTORE_BASE64` | Secret | Base64 der Keystore-Datei `emberlight-release.p12` |
| `KEYSTORE_PASSWORD` | Secret | Passwort des Keystores |
| `KEY_ALIAS` | Secret | Alias des Schlüssels, Standard `emberlight-signing` |
| `KEY_PASSWORD` | Secret | Passwort des Schlüssels. Bei PKCS12 identisch mit `KEYSTORE_PASSWORD` |
| `RELEASE_CERT_SHA256` | Variable | SHA-256-Fingerprint des Zertifikats, Großbuchstaben ohne Doppelpunkte |

**Einmalig erzeugen** (Windows PowerShell 5.1, im Repository-Ordner):

```powershell
.\scripts\new-release-keystore.ps1 -OutputDir H:\DEV\_keys\emberlight
```

Das Skript erzeugt im Zielordner die Keystore-Datei und eine `LIESMICH.txt` mit Passwort, Alias und Fingerprint. Danach setzt es die Secrets und die Variable im Repository `Labushuya/emberlight`. Es bricht ab, wenn dort bereits `KEYSTORE_BASE64` existiert oder wenn der Zielordner in einem Git-Repository liegt. Mit `-SkipGitHub` erzeugt es nur die Dateien.

**Sichern:**

- Keystore-Datei und `LIESMICH.txt` in einem Passwort-Manager ablegen.
- Zusätzlich eine Offline-Kopie an einem anderen Ort anlegen, z. B. auf einem verschlüsselten USB-Stick.
- Der Ordner ist nur für den aktuellen Benutzer lesbar. Backup-Programme, die als System-Dienst laufen, können ihn deshalb nicht sichern. Also manuell sichern.
- Nichts davon committen, in Chats, Tickets oder Mails einfügen oder mit anderen teilen.

**Folgen eines Verlusts:** Android aktualisiert eine installierte App nur mit demselben Signaturschlüssel. Ohne den Schlüssel gibt es keine Updates mehr. Ein neuer Schlüssel zwingt alle Nutzer zur Deinstallation, dabei gehen die lokalen Daten verloren. Das Skript überschreibt deshalb nie einen bestehenden Schlüssel.

**Nie rotieren.** Den Schlüssel auch nicht aus Sicherheitsgründen wechseln.

## 3. Probelauf

```powershell
gh workflow run release.yml -R Labushuya/emberlight --ref main -f version=0.1.0
```

Alternativ im Browser: Actions, Workflow „Release“, „Run workflow“, Version eintragen.

Das Ergebnis liegt im Lauf unter „Artifacts“ als `release-dry-run` (7 Tage): APK, `.sha256` und Release-Notes. Die Zusammenfassung des Laufs zeigt Version, versionCode, SHA-256 und Zertifikat. Vor jedem Release gehört ein Probelauf mit derselben Version.

Der Artefakt-Upload ist Komfort. Ist das Speicherkontingent des Accounts erschöpft, scheitert nur der Upload, und der Lauf bleibt grün. Die Zusammenfassung meldet das in der Zeile „Artefakt hochgeladen“. Fehlt das Artefakt, sind die Werte in der Job-Zusammenfassung maßgeblich.

## 4. Release veröffentlichen

**Voraussetzungen:**

- Alle Änderungen sind in `main`, die CI auf diesem Stand ist grün.
- Die Secrets und die Variable sind gesetzt, und ein Probelauf mit derselben Version war grün.
- Für die Version gibt es noch kein Release.

**Ablauf:**

```bash
git checkout main && git pull
git tag -a v0.1.0 -m "emberlight v0.1.0"
git push origin v0.1.0
```

Der Lauf startet automatisch. Verfolgen:

```bash
gh run list -R Labushuya/emberlight --workflow release.yml --limit 3
gh run watch -R Labushuya/emberlight <run-id>
```

Nach Erfolg steht das Release unter `https://github.com/Labushuya/emberlight/releases`.

Tags werden nicht verschoben und nicht überschrieben.

## 5. Versionsschema

- SemVer `X.Y.Z`. Der Tag trägt ein `v` davor, die Version selbst nicht.
- Y und Z liegen zwischen 0 und 99, X höchstens bei 214747 (Grenze des Android-`versionCode`).
- `versionCode = X * 10000 + Y * 100 + Z`. Beispiele: `0.1.0` ergibt 100, `1.2.3` ergibt 10203.
- Vorabversionen wie `1.0.0-rc1` werden abgewiesen.
- Commits folgen Conventional Commits, siehe [CLAUDE.md](../CLAUDE.md).

## 6. APK prüfen

Aus dem Release `emberlight-vX.Y.Z.apk` und `emberlight-vX.Y.Z.apk.sha256` herunterladen.

**Prüfsumme** (Git Bash, Linux, macOS):

```bash
sha256sum -c emberlight-vX.Y.Z.apk.sha256
```

**Prüfsumme** (Windows PowerShell):

```powershell
Get-FileHash .\emberlight-vX.Y.Z.apk -Algorithm SHA256
```

**Signatur und Zertifikat** (Android-Build-Tools, z. B. `apksigner.bat` im Ordner `build-tools`):

```powershell
apksigner verify --print-certs emberlight-vX.Y.Z.apk
```

Der Wert hinter „Signer #1 certificate SHA-256 digest“ muss mit dem Fingerprint in den Release-Notes übereinstimmen. Groß- und Kleinschreibung spielen keine Rolle, Doppelpunkte schon. Stimmt er nicht, nicht installieren.

## 7. Fehlerbilder

| Meldung | Ursache | Lösung |
|---|---|---|
| „Signierung nicht eingerichtet“ | Ein oder mehrere Secrets fehlen | Secrets setzen, siehe Abschnitt 2, dann den Lauf wiederholen |
| „Tag zeigt nicht auf main“ | Der Tag liegt auf einem Commit außerhalb von `main` | Tag lokal und auf origin löschen: `git tag -d vX.Y.Z` und `git push origin :refs/tags/vX.Y.Z`. Dann den richtigen Commit in `main` taggen und pushen. Solange kein Release existiert, ist das zulässig |
| „Release existiert bereits“ | Zu dieser Version gibt es schon ein Release | Nichts überschreiben. Eine neue Version wählen, z. B. `0.1.1` |
| „Release-Status unklar“ | `gh` konnte den Status nicht prüfen, etwa wegen Rechten oder Netz | Logausgabe des Schritts prüfen und den Lauf neu starten |
| „Falscher Signaturschlüssel“ | Die APK ist mit einem anderen Schlüssel signiert als in `RELEASE_CERT_SHA256` hinterlegt | Nicht weitermachen. Prüfen, ob die Secrets zum gesicherten Keystore gehören, Fingerprint aus `LIESMICH.txt`. Niemals einen neuen Schlüssel erzeugen, um den Fehler zu umgehen |
| „Ungültige Version“ | Eingabe oder Tag entspricht nicht `X.Y.Z`, oder Y bzw. Z sind 100 oder größer | Version korrigieren. Im Tag ein `v` davor, in der Eingabe nicht |
| „Falsches Paket“, „Falsche Version“, „Falscher versionCode“ | Die gebaute APK passt nicht zur Eingabe | Logausgabe des Schritts „APK prüfen“ ansehen, meist stimmt Tag oder Eingabe nicht |
| Warnung „Artefakt nicht hochgeladen“ (CI) oder „Artefakt hochgeladen: nein“ (Probelauf) | Der Upload scheitert, meist wegen des Speicherkontingents des Accounts. Der Build selbst ist erfolgreich | Nichts zu tun. Die Prüfwerte in der Job-Zusammenfassung sind maßgeblich. Speicher aufräumen, wenn Artefakte gebraucht werden |
| Warnung „RELEASE_CERT_SHA256 fehlt“ | Die Variable ist nicht gesetzt | Variable im Browser setzen: Settings, Secrets and variables, Actions, Variables. Den Fingerprint aus `LIESMICH.txt` ohne Doppelpunkte eintragen. Die Prüfung läuft erst dann gegen den Sollwert |

## 8. Später

Das öffentliche Repository `emberlight-releases` ist nur für Downloads gedacht. Dorthin soll der Release-Job später umziehen. Dieses Repository bleibt privat. Der Signierschlüssel bleibt derselbe. In Meilenstein 1 ist der Umzug nicht umgesetzt.
