# ADR 0017: Einrichtung und Betrieb (Meilenstein 3, Paket 3.3)

Status: Umgesetzt im Paket 3.3 (Stand 20.10.2026). Gerätetest offen, siehe Abschnitt „Offen“. Grundlage sind [ADR 0012](0012-lokaler-server.md) (Dienst, Stopp in der App), [ADR 0013](0013-schutz-gegen-manipulation.md) (Netz, Pflicht-Tests) und [ADR 0016](0016-space-verwaltung.md) (Entwürfe, Voreinstellungen).

## Kontext

Bis Paket 3.2 gab es Entwürfe, aber keinen Start. Paket 3.3 bringt den Betrieb: Der Gastgeber richtet das Gerät ein, startet den Space, sieht den laufenden Space und beendet ihn. Der Dienst muss dabei auf Geräten laufen, die Hintergrundprozesse hart beenden. Das Gerät kann pausieren, das WLAN kann wechseln, und die Frist muss auch dann stimmen, wenn die Uhr verstellt wird.

## Entscheidungen

### Akku-Ausnahme

- Prüfung über `PowerManager.isIgnoringBatteryOptimizations`.
- Anfrage über `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit dem Manifest-Recht `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`app/src/main/AndroidManifest.xml`). Die Begründung steht in der Einrichtung, nicht in einem Dialog der Plattform.
- Fallback: `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
- Eine erteilte Ausnahme ist zugleich eine Ausnahme für den Hintergrundstart eines Vordergrunddienstes (ab Android 12). Der Start aus der sichtbaren Oberfläche bleibt ohnehin erlaubt.
- Kein Play-Store-Umzug vorgesehen (CLAUDE.md, harte Grenzen). Die Begründung der Freigabe ist deshalb in der App ausgeschrieben.

### Lokales Netzwerk (`ACCESS_LOCAL_NETWORK`)

- Nur bei `SDK_INT >= 37` und `targetSdk >= 37` als Laufzeit-Berechtigung. Darunter ist der Punkt ausgeblendet (`SetupChecks.localNetworkApplies`).
- Ohne die Berechtigung blockiert Android auch eingehende Verbindungen. Der Punkt ist deshalb **Pflicht**: Ohne Erteilung startet der Space nicht.
- Zustände: nicht gefragt (offen), abgelehnt (erneut fragbar), „Nicht mehr fragen“ (dauerhaft abgelehnt). Für den letzten Zustand zeigt die Einrichtung den Weg zu den App-Einstellungen. Die Unterscheidung braucht die Speicherung „wurde schon gefragt“ (`SetupStore.markAsked`).
- Die Berechtigung ist im Manifest von `share:server` deklariert (ADR 0013, Android-API-Fakten).

### Benachrichtigungen (`POST_NOTIFICATIONS`, ab Android 13)

- Empfohlen, nicht blockierend. Ohne Erlaubnis läuft der Dienst weiter, die Dauermeldung bleibt unsichtbar. Die Einrichtung sagt das und bietet „Trotzdem starten“ an.

### Vordergrunddienst

- `connectedDevice` mit `CHANGE_NETWORK_STATE` (ADR 0012). `dataSync` scheidet aus, weil es ein Zeitlimit hat.
- Start aus der sichtbaren Activity. Verweigert Android den Vordergrund-Start (`ForegroundServiceStartNotAllowedException`, `SecurityException`), ist der Space wieder Entwurf (`SpaceStarter.launch`, `SpaceRunRepository.abortStart`).
- `START_NOT_STICKY`. Nach einem Prozess-Tod startet der Dienst nicht neu. Der aktive Space wird beim nächsten App-Start abgeschlossen (`SpaceServerService.closeOrphanedSpaces`).

### Hersteller-Freigabe

- Nicht abfragbar. Die Einrichtung zeigt eine eigene Anleitung je Hersteller (`space_setup_oem_*` in `strings.xml`). Die Texte stammen nicht aus fremden Quellen; sie beschreiben die Menüs in allgemeiner Form und sagen „je nach Version“.
- Erkennung über `Build.MANUFACTURER` und `Build.BRAND`, kleingeschrieben, mit Teilwörtern (`OemFamily.detect`).
- Fallback-Kette (`OemSettingsLauncher`):
  1. Kandidaten des Herstellers, nur wenn `resolveActivity` sie findet. `startActivity` in try/catch (`ActivityNotFoundException`, `SecurityException`).
  2. `ACTION_APPLICATION_DETAILS_SETTINGS` mit `package:<eigenes Paket>`.
  3. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
  4. Schlägt alles fehl, sagt die Oberfläche das und der Nutzer geht von Hand hin.
- Selbstbestätigung „Erledigt“, gespeichert (`SetupStore`). Der Punkt ist dadurch nicht mehr offen, gilt aber nicht als geprüft.
- Pakete der Hersteller stehen unter `<queries>` im App-Manifest (Paket-Sichtbarkeit ab API 30), gleiche Liste wie `OemFamily.PACKAGES`.

#### Herstellerliste (Komponenten-Namen)

Die Namen sind Tatsachen über die Einstellungs-Aktivitäten und stammen aus den unten genannten Quellen. Die Reihenfolge ist die Versuchsreihenfolge.

| Familie | Kandidaten (Paket/Klasse) |
|---|---|
| Honor | `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` (zuerst, nur bei Honor-Paket belegt), `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity`, `com.huawei.systemmanager/.optimize.process.ProtectActivity`, `com.huawei.systemmanager/.power.ui.HwPowerManagerActivity` |
| Huawei | die Huawei-Kandidaten aus der Zeile oben |
| Xiaomi, Redmi, POCO | `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` |
| Samsung | `com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity` |
| OnePlus | `com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity` |
| Oppo, Realme | `com.coloros.safecenter/.permission.startup.StartupAppListActivity`, `com.coloros.safecenter/.startupapp.StartupAppListActivity`, `com.oppo.safe/.permission.startup.StartupAppListActivity` |
| Vivo, iQOO | `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`, `com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager`, `com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity` |
| Asus | `com.asus.mobilemanager/.autostart.AutoStartActivity`, `com.asus.mobilemanager/.powersaver.PowerSaverSettings` |
| Nokia, HMD | `com.evenwell.powersaving.g3/.exception.PowerSaverExceptionActivity` |
| Motorola, Sony, Pixel, unbekannt | keine Kandidaten, nur Fallback mit Text |

Honor und Huawei erhalten den Anleitungstext „Akku → App-Start: emberlight auf ‚Manuell verwalten‘, alle drei Schalter an (Automatischer Start, Sekundärer Start, Im Hintergrund ausführen)“, mit dem Zusatz „je nach Version“.

### Hintergrundnutzung und Standby

- `ActivityManager.isBackgroundRestricted()` (true): Punkt mit Link zu den App-Infos.
- `UsageStatsManager.getAppStandbyBucket()` (`RESTRICTED`): Hinweis. Der Punkt erscheint nur, wenn eines davon zutrifft (`SetupChecks`).

### Pausen-Erkennung

- Herzschlag im Dienst alle 30 s (`SpaceRunPolicy.HEARTBEAT_INTERVAL_MS`). Er misst `elapsedRealtime`.
- Schwelle 2 min (`PAUSE_THRESHOLD_MS`). Begründung: Ein Herzschlag kommt alle 30 s. Im Doze-Modus wacht das Gerät je nach Wartung verzögert auf. Vier verpasste Herzschläge (120 s) sind mehr als ein normales Aufwachen und weniger als eine Nutzung, die bemerkt werden sollte. Die Zahl ist ein Vorschlag, auf dem Gerät zu messen.
- Eine Lücke über der Schwelle ergibt ein Pause-Ereignis mit Beginn (Wand-Uhr des letzten Herzschlags) und Dauer (`elapsedRealtime`). Die Differenz zu `uptimeMillis` ist nur Zusatzinfo.
- Banner im laufenden Space („Android hat emberlight ca. X Min. pausiert – Gäste konnten dich in der Zeit nicht erreichen“) mit Weg zur Einrichtung. Dazu eine Benachrichtigung, sofern erlaubt. Der Hinweis gilt als gesehen, wenn der Gastgeber ihn bestätigt (`acknowledgePauses`).

### Automatisches Ende

- Die Frist ist ein Wand-Uhr-Zeitpunkt (`expiresAt`). Eine Dauer wird beim Start in einen Zeitpunkt umgerechnet (`SpaceRunPolicy.expiresAt`).
- Prüfung bei jeder Anfrage (`SpaceClosure.isClosed`): Die Antwort lautet `410 {"error":"space_closed"}`, danach beendet der Verarbeiter den Space. Außerdem beim Herzschlag, beim Start des Dienstes und der App und über einen Wecker.
- Der Wecker ist ungenau (`setAndAllowWhileIdle`). Keine exakten Alarme, deshalb kein Sonderrecht.
- Plausibilisierung: Wand-Uhr und `elapsedRealtime` seit dem Start gelten beide. Das frühere Ergebnis zählt (`SpaceRunPolicy.isClosed`). Eine vorgestellte Uhr beendet den Space nicht später, eine zurückgestellte nicht früher als die gemessene Laufzeit. Die Tests decken beide Richtungen ab.
- Beim Ablauf bleibt die Frist eine Sekunde gesetzt (`CLOSE_GRACE_MILLIS`), damit die 410-Antwort beim Gast ankommt, bevor der Server schließt.
- Endezeit: Beim Ablauf ist es die Frist, bei einem Ende durch den Gastgeber der Zeitpunkt des Stopps.

### WLAN und Netzwechsel

- „Dasselbe Netz“ heißt: gleiche IPv4-Adresse, gleiches Präfix und gleiches Standard-Gateway (`NetworkPolicy.sameNetwork`, `WifiAddress` aus `LinkProperties`). Ohne Standortrecht lässt sich der Name des WLAN nicht prüfen, deshalb zählt die Adresse.
- Verlust des WLAN: Der Server pausiert (`NetworkAction.Lost`). Der Space bleibt `ACTIVE`.
- Dasselbe Netz kommt zurück: automatisch fortsetzen, an derselben Adresse (`NetworkAction.Resume`). Die Zugangsschlüssel bleiben gültig.
- Ein anderes Netz: nicht automatisch. Hinweis und Benachrichtigung „Neues WLAN erkannt – Space hier fortsetzen?“, Bestätigung in der App (`ConfirmNetwork`). Erst dann bindet der Server an die neue Adresse. Die URL und der QR-Code ändern sich dann, deshalb die Bestätigung.
- Fällt beim Start kein WLAN mit Adresse an, bleibt der Space Entwurf (`abortStart`).

### Serieller Betrieb

- Start, Stopp, Pause, Fortsetzen, Herzschlag und Ablauf laufen über einen unbegrenzten `Channel` in `SpaceRunner`. Ein Verarbeiter führt die Befehle nacheinander aus. Damit überholt kein Stopp einen Start, und ein Start kurz nach einem Stopp geht nicht verloren (ADR 0012, ADR 0014). Die Mutex-Lösung ist entfallen.
- Die Klasse kennt keine Android-Typen. Der Dienst liefert die Umgebung über `RunHost`. Tests setzen Fakes für Uhr, Netz, Server und Speicher ein. Dispatcher und Wartezeiten sind injiziert, Tests laufen ohne echte Pausen.
- Der Zustand für die Oberfläche (`RunState`) ist prozessweit, weil der Dienst nur einmal läuft.

### Gemeinsame Datenbank

- `SpacesDatabase.shared(context)` ist die eine Instanz für App und Server. Die Hilt-Bindung in `feature:space` liefert dieselbe Instanz, der Server nutzt sie über `SpaceRuntime` und `SpaceServerService`. Zwei Instanzen auf `spaces.db` gelten nicht als sauber (ADR 0016, Vormerkung 1).
- Migration 3 → 4: Spalten `started_at`, `expires_at`, `ended_at` an `space` und Tabelle `pause_event`. Additiv, bestehende Daten bleiben.
- Ein aktiver Space hat einen Eintrag in `space`. Die Bereinigung verwaister Verzeichnisse (ADR 0016) sieht ihn deshalb nicht als verwaist (Vormerkung 3).
- Das Speicherlimit wird beim Start geprüft: Limit ≤ freier Speicher − 2 GiB (`SpaceRunPolicy.startProblem`). Das Limit ist keine Reservierung (Vormerkung 2).

### Start und Stopp in der Oberfläche

- „Space starten“ im Detail eines Entwurfs öffnet die Einrichtung im Start-Modus. Der Start-Knopf ist erst aktiv, wenn die Pflicht-Punkte erfüllt sind. Empfohlene Punkte lassen sich mit „Trotzdem starten“ überspringen.
- Vorbedingungen in der Reihenfolge: WLAN mit Adresse, nur ein aktiver Space, Speicherlimit, fester Ende in der Zukunft, Status Entwurf. Die Fehlermeldung nennt den Grund und bietet bei Bedarf den Weg zum Bearbeiten.
- Nach dem Start wechselt die Oberfläche in den laufenden Space. Die Einrichtung fliegt aus dem Stapel.
- „Space beenden“ fragt immer nach. Die Benachrichtigung „Beenden“ öffnet die App mit der Bestätigung, sie beendet nie direkt.
- Im laufenden Space ist nichts am Space zu bearbeiten.

### Release-Einstieg

- Der Start aus der Oberfläche ist auch im Release-Build verfügbar. Der Debug-Einstieg (`src/devtools`) bleibt auf Debug und Benchmark beschränkt, `checkReleaseManifestDebugEntry` prüft das weiter.
- R8 darf die Server-Pipeline nicht entfernen. Die Prüfung `checkReleaseServerPipeline` liest die Mapping-Datei der Release-Variante und verlangt die Klassen `ServerPipelineKt` (hält `installEmberlightModule`), `KtorLocalServer` und `io.ktor.server.cio.CIOApplicationEngine`. Sie hängt an `check`.
- `consumer-rules.pro` braucht für den Serverpfad keine eigenen `-keep`-Regeln. Der Pfad ist von der Oberfläche aus erreichbar, R8 behält ihn durch die Erreichbarkeit. Die frühere Notiz, der Serverpfad werde im Release entfernt, war mit diesem Schritt überholt.
- Die Vorab-Version `v0.3.0-alpha` nach 3.5 ist nur geplant (siehe [PLAN-M3.md](../PLAN-M3.md)).

## Quellen

Recherche am 20.10.2026. Ein Teil der Lizenzangaben konnte nicht bestätigt werden, das ist unten vermerkt.

- Android-Referenz (Stand des lokalen SDK, Android 37): `PowerManager`, `Settings`, `ActivityManager.isBackgroundRestricted`, `UsageStatsManager.getAppStandbyBucket`, `ACCESS_LOCAL_NETWORK`, Paket-Sichtbarkeit: <https://developer.android.com/training/package-visibility>, <https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)>, <https://developer.android.com/reference/android/app/usage/UsageStatsManager#getAppStandbyBucket()>. Die Permission-Annotationen im lokalen SDK, siehe ADR 0013, Quelle [Q19].
- Doze und Standby: <https://developer.android.com/training/monitoring-device-state/doze-standby>.
- Vordergrunddienste und Typen: <https://developer.android.com/develop/background-work/services/fgs/service-types>.
- Komponenten-Namen der Hersteller-Einstellungen: aus den Projekten AutoStarter (`judemanutd/AutoStarter`, <https://github.com/judemanutd/AutoStarter>), dont-kill-me-lib von Briar (GPL-3.0) und dem DontKillMyApp-Verzeichnis (<https://dontkillmyapp.com/apidoc>). Die Komponenten-Namen sind Fakten. Anleitungstexte sind eigene Formulierungen.
- **Lizenzen, nicht bestätigt:** Der Auftrag nennt AutoStarter als MIT, dont-kill-me-lib als GPL-3.0 und DontKillMyApp als CC BY 4.0. Die Recherche konnte die MIT-Angabe nicht im Quelltext belegen. Für die Website dontkillmyapp.com fand sich keine CC-BY-4.0-Angabe. Die Android-App DontKillMyApp ist laut F-Droid Apache-2.0, ein anderes Projekt. Vor dem Release müssen die Lizenzen am Quelltext geprüft werden. Bis dahin verwendet das Projekt keinen Code dieser Quellen, nur Komponenten-Namen.

## Restrisiken

- Hersteller ändern Menüs und Komponenten-Namen ohne Vorwarnung. Dann greift der Fallback.
- Die Freigabe der Hersteller lässt sich nicht prüfen. „Erledigt“ ist eine Selbstbestätigung.
- Die Schwelle von 2 min ist eine Annahme. Auf Geräten mit aggressivem Doze kann sie zu eng oder zu weit sein.
- Der Wecker ist ungenau. Das Ende kann bis zur nächsten Anfrage oder bis zum nächsten Herzschlag auf sich warten lassen. Die Prüfung bei jeder Anfrage fängt das ab, solange der Server läuft. Läuft der Dienst nicht mehr, greift `closeWithoutService` beim nächsten App-Start.
- Eine erkannte Pause gilt nur, solange der Dienst lebt. Wird der Prozess getötet, gibt es keine Pausenerkennung und keinen Neustart (`START_NOT_STICKY`).
- Die Erkennung „dasselbe Netz“ über Adresse, Präfix und Gateway kann bei sehr kurzem Netzwechsel mit gleicher Adresse fälschlich „dasselbe Netz“ melden. Dann fehlt die Bestätigung, der Server bindet an dieselbe Adresse. Das ist akzeptiert, weil die Zugangsschlüssel gültig bleiben.
- Das Speicherlimit ist keine Reservierung. Ein Gerät kann nach dem Start weniger Speicher haben als vor dem Start.

## Konsequenzen

- Der Betrieb ist in Zustände und Übergänge zerlegt, die ohne Android-Typen getestet werden.
- Die Hersteller-Anleitung ist eine Pflege-Aufgabe. Sie braucht bei jedem Gerätetest einen Blick.
- Die R8-Prüfung schützt den Release-Pfad, aber nur über die Klassenliste in `ReleaseMappingGuard`.

## Offen

- Gerätetest für 3.3 (Testkatalog in der PR-Beschreibung).
- Lizenzen der Quellen am Quelltext prüfen (siehe Quellen).
- Schwelle für Pausen auf dem Gerät messen.
- Lingering close (Pflicht vor 3.5, siehe PLAN-M3.md) ist nicht Teil von 3.3.

## Teststrategie (Paket 3.3)

- Zustandsmaschine und Vorbedingungen: `SpaceRunPolicyTest`, `SpaceRunRepositoryTest` (Migration und Transaktionen in `share:space`).
- Ablauf mit Fake-Uhren, inklusive verstellter Wand-Uhr in beide Richtungen: `SpaceRunPolicyTest`.
- Kanal-Verarbeitung und schnelle Folgen von Start und Stopp ohne verlorenen Start: `SpaceRunnerTest` mit Fakes für Uhr, Netz, Server und Speicher. Ohne echte Pausen.
- 410 und Ende bei Anfrage: `SpaceClosureSocketTest`.
- WLAN-Entscheidung (gleiche Adresse, Präfix und Gateway; Verlust, Fortsetzen, Wechsel): `NetworkPolicyTest`.
- Pausen-Logik (Schwelle, Beginn, Dauer): `SpaceRunPolicyTest` (`PauseDetector`).
- Robolectric-Dienst-Integration: `SpaceServerServiceTest`.
- Hersteller-Fallback mit Fake-Paketzugriff (`OemPort`): `OemSettingsLauncherTest`, einschließlich der Erkennung und der Kandidatenliste.
- Berechtigungszustände für API 36 und 37 sowie „Nicht mehr fragen“: `SetupChecksTest`.
- Gemeinsame Datenbank: `SharedSpacesDatabaseTest`.
- Release: `ReleaseMappingGuardTest` (Build-Logik) prüft die Mapping-Auswertung, die Aufgabe `checkReleaseServerPipeline` die Realität im R8-Lauf.
- Goldens (`SpaceRunScreenshotTest`, `SpacesScreenshotTest`): Einrichtung, Start-Fehler, laufender Space (normal, Warnung, Pause, Netzwechsel), beendeter Space, Telefon de und en, großes Display.

### Leere Aufnahmen im gemeinsamen Prozess

Beim Aufnehmen der Goldens entstanden leere Bilder (`#FAFAFA`, ca. 3,4 KB). Eine Verifikation im selben Prozess hätte sie gegen sich selbst als grün gewertet. ADR 0006 beschreibt dieselbe Erscheinung. Deshalb laufen in `feature:space` alle Test-Aufgaben mit `forkEvery = 1`. Nach dem Aufnehmen wird jede Aufnahme auf Größe und Inhalt geprüft, bevor sie in den Commit geht.

## Gerätetest

Der Testkatalog für die Nutzerin bzw. den Nutzer steht in der PR-Beschreibung. Dieser ADR listet nur die Prüfpunkte, die die Architektur betreffen: Start der Hersteller-Einstellung, Pause bei Bildschirm aus, Netzwechsel mit gleicher und anderer Adresse, Ende über die Frist bei verstellter Uhr.
