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

## Nachtrag 20.10.2026: Vorab-Versionen

### Kontext

Vorab-Versionen wie `v0.3.0-alpha.1` sollen zum Testen verteilt werden, ohne die finale Version zu belegen. Das Schema `X * 10000 + Y * 100 + Z` kennt keine Vorab-Stufe. Android verlangt bei Updates einen streng steigenden `versionCode`.

### Entscheidung

- Tag-Form: `vX.Y.Z` (final) oder `vX.Y.Z-alpha.N`, `vX.Y.Z-beta.N`, `vX.Y.Z-rc.N`. `versionName` ist der Tag ohne `v`.
- `versionCode = (X * 10000 + Y * 100 + Z) * 100 + Stufe`. Stufen: alpha.N = N (1 bis 19), beta.N = 20 + N (21 bis 39), rc.N = 40 + N (41 bis 59), final = 99. Die Werte 60 bis 98 bleiben frei.
- Grenzen: Y und Z höchstens 99, N höchstens 19, X höchstens 2099. Der größte Code ist 2 099 999 999 und bleibt unter der Google-Play-Grenze 2 100 000 000 für den versionCode.
- Vorab-Tags werden als GitHub-Prerelease veröffentlicht, nicht als „Latest“. Die Release-Notes weisen darauf hin, dass eine Rückkehr zu einer älteren stabilen Version nur per Deinstallation möglich ist. Die Space-Daten gehen dabei verloren.
- Der Probelauf (`workflow_dispatch`) nimmt beide Formen an.

### Begründung

Die Stufe steht in den letzten zwei Stellen. Dadurch ist der Code über die SemVer-Reihenfolge monoton: `0.3.0-alpha.1` (30001) < `0.3.0-alpha.19` (30019) < `0.3.0-beta.1` (30021) < `0.3.0-rc.19` (30059) < `0.3.0` (30099) < `0.3.1-alpha.1` (30101). Zwei Stellen reichen für Art und Nummer, und der Code bleibt aus dem Tag ableitbar und reproduzierbar.

### Alternativen

- Vorab-Builds ohne Tag verteilen: verworfen, Tester bekämen keine nachvollziehbare Versionsnummer.
- Ein fortlaufender Zähler für den `versionCode`: verworfen, er ist nicht aus dem Tag ableitbar.
- Mehr Stellen für den Code: verworfen, die Grenze für X würde deutlich sinken.

### Migration

- Veröffentlicht sind `v0.1.0` (Code 100) und `v0.2.0` (Code 200) nach dem alten Schema. Beide bleiben unverändert. Das Beispiel `0.2.0` = 20099 in der Formel zeigt nur das neue Schema, `v0.2.0` wird nicht neu gebaut.
- Ab `0.2.0` liegt jeder Code des neuen Schemas über 200. Installierte Versionen lassen sich also ohne Deinstallation auf neue Versionen aktualisieren.
- Monotonie: Ein Tag mit kleinerem Code als das zuletzt veröffentlichte Release, z. B. `v0.2.1` nach `v0.3.0-alpha.1`, würde Installationen nicht aktualisieren. Eine automatische Prüfung dafür gibt es noch nicht, siehe [RELEASE.md](../RELEASE.md).

### Quellen

- GitHub Actions, Filtermuster für Tags (Cheat-Sheet „Filter pattern“, `*` und `+`): https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions
- Android-Doku, Versionierung (Google Play erlaubt höchstens 2100000000, Downgrades werden über den versionCode verhindert): https://developer.android.com/studio/publish/versioning
