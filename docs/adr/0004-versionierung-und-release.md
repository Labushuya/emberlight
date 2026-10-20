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
- Monotonie: Ein Tag mit kleinerem Code als das bisher höchste veröffentlichte Release, z. B. `v0.2.1` nach `v0.3.0-alpha.1`, würde Installationen nicht aktualisieren. Dafür gibt es die Prüfung unten.

### Monotonie-Prüfung

- Der Release-Lauf vergleicht vor dem Build den versionCode der neuen Version mit allen veröffentlichten Releases (`gh release list --exclude-drafts`, inkl. Prereleases). Verglichen wird strikt größer. Gleich oder kleiner bricht den Tag-Lauf ab, bevor ein Release angelegt wird.
- Der Vergleichswert jedes Tags entsteht mit derselben Formel wie für die neue Version. Die Alt-Tags `v0.1.0` und `v0.2.0` ergeben so 10099 bzw. 20099. Veröffentlicht wurden 100 bzw. 200 nach dem alten Schema. Für den Vergleich ist die neue Formel richtig, weil jede neue Version darüber liegen soll.
- Entwurfs-Releases und Tags, die nicht dem Schema entsprechen, bleiben außen vor.
- Im Probelauf wird der Vergleich nur berechnet und in der Zusammenfassung gemeldet. Abgebrochen wird dort nicht.
- Umsetzung: `scripts/release-monotonie.sh`, Aufruf aus `release.yml`. Die Schritte lassen sich lokal in Bash nachvollziehen.

### Quellen

- GitHub Actions, Filtermuster für Tags (Cheat-Sheet „Filter pattern“, `*` und `+`): https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions
- Android-Doku, Versionierung (Google Play erlaubt höchstens 2100000000, Downgrades werden über den versionCode verhindert): https://developer.android.com/studio/publish/versioning

## Nachtrag 20.10.2026 (Teil 2): Zwei Jobs im Release-Workflow

### Kontext

Der Probelauf (Run 39500171156) hat gezeigt: Der Release-Job führte `./gradlew check` aus. Der Test `ExternalToolCrossCheckTest` (share:space) verlangt in der CI ExifTool, ffmpeg, libheif und libavif. Diese installierte nur `ci.yml`. Jedes echte Release wäre daran gescheitert. Die Signier-Secrets sollen zugleich nur in dem Job liegen, der tatsächlich signiert, und dort keine zusätzlichen Werkzeuge laufen lassen.

### Entscheidung

- Job `test` ohne Secrets, Permissions `contents: read`: Version bestimmen, Tag- und Monotonie-Prüfung, Werkzeuge installieren, `./gradlew check`.
- Job `release` mit `needs: test`, `contents: write` und den Signier-Secrets: Secrets prüfen, `./gradlew assembleRelease` (ohne `check`), APK prüfen, Release-Dateien, Zusammenfassung, `gh release create` nur bei `push`, Probelauf-Artefakt, Keystore entfernen.
- Die apt-Installation der Prüfwerkzeuge steht in der Composite-Action `.github/actions/install-cross-check-tools`. `ci.yml` und der Test-Job nutzen sie gemeinsam, damit die Paketliste nur an einer Stelle steht. Die Action enthält keine `uses:`-Zeilen, daher gilt für sie keine SHA-Pflicht.
- Die Version bestimmt nur der Test-Job. Der Release-Job übernimmt Version, versionCode, Vorab-Kennzeichen und Monotonie-Ergebnis über Job-Outputs.
- Der Release-Job baut die APK neu, statt ein Artefakt aus dem Test-Job zu übernehmen. Ein Artefakt aus einem Job ohne Secrets würde ungeprüft in den Job mit Signierschlüsseln wandern. Es wird auch kein Cache übergeben.
- Gradle-Cache: Der Test-Job liest nur, ohne `cache-encryption-key`, weil er kein Secret bekommt. Der Release-Job liest nur und behält den Schlüssel für den Configuration Cache. `setup-gradle` schreibt nur auf dem Standardbranch, Läufe von anderen Branches lesen also nur Einträge von `main`.
- Der Robolectric-Cache liegt nur noch im Test-Job (Restore). Der Release-Job führt keine Robolectric-Tests aus.

### Konsequenzen

- Monotonie und Tag-Prüfung laufen nur im Test-Job. Legt zwischen Test-Ende und Veröffentlichung ein anderer Lauf dasselbe Release an, verweigert `gh release create` das Überschreiben. Tags werden nie überschrieben.
- Der Test-Job ohne Verschlüsselungsschlüssel lädt den Configuration Cache nicht. Der Lauf dauert dadurch etwas länger, das Ergebnis ändert sich nicht.
- Der Release-Job wartet auf den Test-Job. Ein Release dauert damit länger, als wenn nur der Release-Job läuft.

### Alternativen

- Ein Job mit Werkzeugen und Signier-Secrets: verworfen. Die Werkzeuge liefen dann im Job mit den Signierschlüsseln.
- Die apt-Schritte in beiden Workflows duplizieren: verworfen, die Paketlisten würden auseinanderlaufen.
- Ein wiederverwendbarer Workflow statt Composite-Action: verworfen, dafür wäre eine eigene Workflow-Datei nötig, der Aufwand ist unverhältnismäßig.

### Quellen

- GitHub Actions, Composite-Actions und `needs`/Job-Outputs: https://docs.github.com/en/actions/sharing-automations/creating-actions/creating-a-composite-action
