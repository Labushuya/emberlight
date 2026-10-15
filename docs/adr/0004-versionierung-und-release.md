# ADR 0004: Versionierung und Release

Status: angenommen, 15.10.2026

## Kontext

emberlight wird per Sideloading über GitHub verteilt (siehe [KONZEPT](../KONZEPT.md)). Installierte Versionen lassen sich nur mit demselben Signaturschlüssel aktualisieren. Das Android-System vergleicht beim Update die Zertifikate, und ein anderes Zertifikat ergibt eine neue, separate App (siehe Quellen). Der Schlüssel darf deshalb nie verloren gehen oder wechseln. Das Repository ist privat (GitHub Free), dort gibt es keine technische Branch Protection.

## Entscheidung

- Die Version kommt aus dem Git-Tag `vX.Y.Z` und wird dem Build als `-Pemberlight.version=X.Y.Z` übergeben. `versionCode = X * 10000 + Y * 100 + Z`, mit Y und Z kleiner als 100.
- Kein release-please. Die Version folgt der Vorgabe aus dem Tag, nicht aus der Commit-Analyse.
- Signierung über Umgebungsvariablen (`EMBERLIGHT_KEYSTORE_FILE`, `EMBERLIGHT_KEYSTORE_PASSWORD`, `EMBERLIGHT_KEY_ALIAS`, `EMBERLIGHT_KEY_PASSWORD`). Im Workflow kommen sie aus den Repository-Secrets. Mit `-Pemberlight.requireReleaseSigning=true` bricht der Release-Build ab, wenn eine fehlt.
- Probelauf per `workflow_dispatch`: baut, signiert und prüft wie beim Tag, legt aber kein Release und keinen Tag an.
- Zertifikats-Pinning: Die Repository-Variable `RELEASE_CERT_SHA256` enthält den erwarteten Fingerprint. Weicht die APK davon ab, bricht der Job ab.
- Die applicationId ist `io.github.labushuya.emberlight`, entschieden am 15.10.2026. Begründung: Die bisherigen Repos folgen keinem einheitlichen Schema, `labushuya.de` existiert nicht, und der Namensraum `io.github.<Nutzer>` ist über GitHub nachweisbar.
- Das Repository bleibt privat. Eine technische Branch Protection gibt es nicht. Stattdessen gilt als Regel: Jede Änderung läuft über Branch und Pull Request, der Merge erfolgt nur bei grüner CI (siehe [CLAUDE.md](../../CLAUDE.md)).

## Konsequenzen

- Verliert man den Keystore, gibt es keine Updates mehr für installierte Apps. Der Schlüssel wird nie rotiert.
- Einmal veröffentlichte Tags werden nicht überschrieben. Ein Fehler wird mit einer neuen Version behoben.
- Der Release-Job bleibt im privaten Repo. Ein späterer Umzug nach `emberlight-releases` ändert nur Ziel und Job, nicht den Schlüssel.

## Alternativen

- release-please oder Versionierung aus Commits: verworfen, die Vorgabe lautet, die Version kommt aus dem Tag.
- Ein Schlüssel pro Release: verworfen, Updates würden brechen.
- applicationId `com.labushuya...`: verworfen, die Domain `labushuya.de` existiert nicht.
- Ein eigener Schlüssel für Debug-Builds: nicht nötig, Debug-Builds werden nicht verteilt.

## Quellen

- Android-Doku, App-Signatur (Updates nur mit demselben Zertifikat): https://developer.android.com/studio/publish/app-signing
