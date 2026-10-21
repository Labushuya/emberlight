# ADR 0017: Einrichtung und Betrieb (Meilenstein 3, Paket 3.3)

Status: Umgesetzt im Paket 3.3 (Stand 20.10.2026), nachgebessert in 3.3b am 20.10.2026: Ein Prozess-Tod oder Abbruch durch das System beendet einen Space nicht mehr (Abschnitt „Neustart nach Prozess-Tod“). Gerätetest offen, siehe Abschnitt „Offen“. Grundlage sind [ADR 0012](0012-lokaler-server.md) (Dienst, Stopp in der App), [ADR 0013](0013-schutz-gegen-manipulation.md) (Netz, Pflicht-Tests) und [ADR 0016](0016-space-verwaltung.md) (Entwürfe, Voreinstellungen).

Nachtrag 21.10.2026 (Hotfix 2): Die Medienansicht des Gastgebers zeigt bei fehlgeschlagener Vorschau das Original, verkleinert und ohne Plattencache. Scheitert auch das, steht dort „Keine Vorschau“. Die Videoansicht in der Gastgeber-App ist eigenständig (kein Galerie-Code): Player mit Audio-Attributen, Abspielen nur über den Knopf, Pause bei ON_STOP, Freigabe beim Schließen. Die Fläche ist eine TextureView, weil die Ansicht in einem Dialog liegt; eine SurfaceView kann dort schwarz bleiben. Fehler erscheinen als Text und im Log `EmberlightSpaceVideo`. Die Serverantworten mit Fehlercode werden im Log `EmberlightServer` festgehalten (siehe `docs/DEBUG-SERVER.md`).

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
- `START_STICKY` (Nachbesserung 3.3b, ersetzt `START_NOT_STICKY`). Nach einem Prozess-Tod startet das System den Dienst neu und entscheidet über den aktiven Space (Abschnitt „Neustart nach Prozess-Tod“). Der Space wird dadurch nicht beendet.

### Hersteller-Freigabe

- Nicht abfragbar. Die Einrichtung zeigt eine eigene Anleitung je Hersteller (`space_setup_oem_*` in `strings.xml`). Die Texte sind eigene Formulierungen (siehe Quellen, Prüfung auf wörtliche Übernahme). Sie beschreiben die Menüs in allgemeiner Form und sagen „je nach Version“.
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
- Prüfung bei jeder Anfrage (`SpaceClosure.isClosed`): Die Antwort lautet `410 {"error":"space_closed"}`. Danach beendet der Verarbeiter den Space nach dem Nachlauf (siehe Nachtrag 21.10.2026). Außerdem beim Herzschlag, beim Start des Dienstes und der App und über einen Wecker.
- Der Wecker ist ungenau (`setAndAllowWhileIdle`). Keine exakten Alarme, deshalb kein Sonderrecht.
- Plausibilisierung: Wand-Uhr und `elapsedRealtime` seit dem Start gelten beide. Das frühere Ergebnis zählt (`SpaceRunPolicy.isClosed`). Eine vorgestellte Uhr beendet den Space nicht später, eine zurückgestellte nicht früher als die gemessene Laufzeit. Die Tests decken beide Richtungen ab.
- Nach jedem Ende bleibt der Server 60 s offen (`CLOSE_GRACE_MILLIS`, siehe Nachtrag 21.10.2026). Die 410-Antwort kommt so beim Gast an, bevor der Server schließt.
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

### Neustart nach Prozess-Tod (Nachbesserung 3.3b)

Ein Prozess-Tod, ein Abbruch durch das System (etwa ein Hersteller-Freezer oder Kill) oder das Wegwischen der App durch die Nutzerin bzw. den Nutzer beendet einen Space **nicht**. Bis 3.3 blieb der Space nach einem Prozess-Tod `ACTIVE`, ohne laufenden Server. Dieser Abbruch-Pfad war für den Neustart der App gedacht (`SpaceServerService.closeOrphanedSpaces`), er wurde aber nirgends aufgerufen und war totes Code. Die Nachbesserung entfernt ihn und ersetzt ihn durch den Neustart unten.

- **Dienst mit `START_STICKY`.** Das System startet den Dienst nach einem Prozess-Tod neu, mit einem Intent ohne Auftrag. `START_NOT_STICKY` aus ADR 0012 und ADR 0014 ist damit ersetzt (Nachträge dort).
- **Gespeicherter Lauf.** Bei jedem Herzschlag und beim Start schreibt der Dienst den letzten Stand in den `RunBeatStore`: Space, Start (Wand-Uhr und `elapsedRealtime`), letzter Herzschlag, Port. Der Speicher ist die Einstellungs-Datei `emberlight.space-run`, keine Datenbankmigration. Ohne gespeicherten Lauf stoppt der Neustart sofort, ohne in den Vordergrund zu gehen.
- **Entscheidung (`RestartPolicy.decide`, ohne Android-Typen):**

  | Zustand nach dem Neustart | Entscheidung | Ergebnis |
  |---|---|---|
  | kein aktiver Space | `NothingToDo` | Dienst endet |
  | aktiv, Frist um (Wand-Uhr, oder gemessene Laufzeit auf demselben Gerät) | `Expired` | Space `ENDED` zum Zeitpunkt der Frist, die Unterbrechung bis dahin ist eine Pause |
  | aktiv, nicht abgelaufen, keine Akku-Ausnahme | `Interrupted` | Dienst endet, Space bleibt `ACTIVE` |
  | aktiv, nicht abgelaufen, Akku-Ausnahme | `Resume` | Dienst geht in den Vordergrund, der Server startet am selben Port |

- **Vordergrund-Start.** `startForeground` läuft in try/catch. `ForegroundServiceStartNotAllowedException` (ab API 31) und `SecurityException` enden den Dienst. Der Space bleibt `ACTIVE`, er ist unterbrochen. Ein Neustart ohne Vordergrund ist damit kein Fehler für den Space.
- **Wiederaufnahme.** Scheitert der Server bei der Wiederaufnahme (kein WLAN, Bindung, fehlende Berechtigung `ACCESS_LOCAL_NETWORK`), bleibt der Space `ACTIVE`. Ein frischer Start geht dagegen weiter auf Entwurf zurück. Der Befehl trägt dafür das Merkmal `resume` (`RunCommand.Start`, Extra `RESUME` am Intent).
- **Frist.** Die Frist gilt wie im Lauf (`SpaceRunPolicy.isClosed`). Die gemessene Laufzeit zählt, solange dasselbe Gerät läuft. Nach einem Geräte-Neustart (`elapsedRealtime` ist kleiner als beim letzten Herzschlag) gilt nur die Wand-Uhr.
- **Unterbrochen ist ein abgeleiteter Zustand**, kein eigener Status in der Datenbank: `ACTIVE`, kein Server läuft (`RunState.Idle`) und die Frist ist nicht um (`SpaceRunningUiState.interrupted`). Ein gespeicherter Zustand könnte beim App-Start veralten. Der abgeleitete nicht.
- **Banner „Space wurde unterbrochen“ mit „Fortsetzen“** in der Gastgeber-Ansicht (`SpaceRunningScreen`). „Fortsetzen“ startet den Dienst aus der sichtbaren Oberfläche neu (`SpaceStarter.resume`). Scheitert das (kein WLAN, Dienst verweigert), bleibt der Space aktiv und das Banner nennt den Grund. Der Statustext lautet „Unterbrochen · Fortsetzen nötig“.
- **Ablauf beim Öffnen.** Ist ein aktiver Space nach der Uhr abgelaufen und läuft kein Server, schickt die Gastgeber-Ansicht den Wecker-Befehl. Der Dienst beendet den Space zur Frist. Die Ansicht zeigt dann „Beendet“, nicht „unterbrochen“.
- **Pausen (Punkt c).** Die Unterbrechung ist ein Pause-Ereignis. Beginn ist die Wand-Uhr des letzten Herzschlags. Die Dauer misst `elapsedRealtime` (ohne Messung: Wand-Uhr). Das Ereignis entsteht bei der Wiederaufnahme, also mit dem Ende der Unterbrechung. Endet der Space in einer Unterbrechung, reicht die Pause bis zur Frist. Das Pausen-Banner und die Statistik zeigen sie danach wie jede andere Pause.
- **Benachrichtigung (Punkt d).** Feuert der Wecker, während der Space `ACTIVE`, nicht abgelaufen und ohne Server ist, erscheint „Space unterbrochen – zum Fortsetzen öffnen“. Der Hinweis öffnet die App, er ist nicht mit „Beenden“ verbunden. Bei laufendem Server gibt es ihn nicht, ein früher Wecker dort wird ignoriert. Der Wecker feuert zum geplanten Ende, ein solcher Fall tritt also nur bei einer zurückgestellten Wand-Uhr auf. Der Hinweis ist ein Schutz für diesen Fall, kein Hauptweg.
- **Akku-Ausnahme als Voraussetzung.** Ohne sie startet der Dienst nach einem Prozess-Tod nicht selbst. Der Space bleibt dann unterbrochen, bis der Gastgeber die App öffnet und fortsetzt. Die Einrichtung zeigt die Ausnahme weiterhin als Punkt.
- **Bekannte Lücke.** Stirbt der Prozess zwischen dem Start in der Datenbank und dem ersten Speichern des Laufs, fehlt der gespeicherte Lauf. Der Neustart greift dann nicht. Der Space ist trotzdem `ACTIVE` und erscheint als unterbrochen, die Fortsetzung funktioniert. Das Fenster ist kurz: Es liegt zwischen dem Start in der Datenbank und dem ersten gespeicherten Herzschlag.
- **Nicht am Gerät gemessen.** Das Verhalten des Systems beim Neustart (START_STICKY mit Akku-Ausnahme, Hersteller-Freezer) ist auf dem Gerät nicht geprüft. Der Testkatalog markiert das als Entwickler-Prüfung, weil es ein Prozess-Tod per adb braucht.

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

Recherche am 20.10.2026. Die Lizenzangaben sind unten am Quelltext der Quellen belegt.

- Android-Referenz (Stand des lokalen SDK, Android 37): `PowerManager`, `Settings`, `ActivityManager.isBackgroundRestricted`, `UsageStatsManager.getAppStandbyBucket`, `ACCESS_LOCAL_NETWORK`, Paket-Sichtbarkeit: <https://developer.android.com/training/package-visibility>, <https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)>, <https://developer.android.com/reference/android/app/usage/UsageStatsManager#getAppStandbyBucket()>. Die Permission-Annotationen im lokalen SDK, siehe ADR 0013, Quelle [Q19].
- Doze und Standby: <https://developer.android.com/training/monitoring-device-state/doze-standby>.
- Vordergrunddienste und Typen: <https://developer.android.com/develop/background-work/services/fgs/service-types>.
- Komponenten-Namen der Hersteller-Einstellungen: aus den Projekten AutoStarter (`judemanutd/AutoStarter`, <https://github.com/judemanutd/AutoStarter>), dont-kill-me-lib von Briar (GPL-3.0) und dem DontKillMyApp-Verzeichnis (<https://dontkillmyapp.com/apidoc>). Die Komponenten-Namen sind Fakten. Anleitungstexte sind eigene Formulierungen.
- **Lizenzen, am 20.10.2026 in den Quellen selbst nachgelesen:**
  - AutoStarter: **MIT**. Belegt durch <https://github.com/judemanutd/AutoStarter/blob/master/LICENSE.txt> („MIT License, Copyright (c) 2018 Jude Fernandes“).
  - dont-kill-me-lib (Briar): **GPL-3.0** (GNU General Public License, Version 3 oder später). Belegt durch <https://github.com/briar/dont-kill-me-lib/blob/main/LICENSE.txt>, dem Spiegel des Projekts. GitHub zeigt die Lizenz nur als „NOASSERTION“, der Text selbst ist die GPLv3. Das Original liegt bei code.briarproject.org.
  - dontkillmyapp.com (Urbandroid Team): **CC BY 4.0**. Belegt durch die Datei LICENCE (<https://github.com/urbandroid-team/dont-kill-my-app/blob/master/LICENCE>) und den Abschnitt „Licence“ im README desselben Repos. Danach steht der Inhalt der Website unter CC BY.
  - Korrektur: Die frühere Angabe „keine CC-BY-4.0-Angabe für die Website“ war falsch. Die Android-App DontKillMyApp ist laut F-Droid ein anderes Projekt (Apache-2.0). Für sie gilt die Angabe nicht.
- **Klarstellung zur Übernahme:** Übernommen wurden nur Paket- und Komponentennamen. Das sind Fakten über die Einstellungs-Aktivitäten. Kein Code und keine Texte aus den Quellen. Die Anleitungstexte in `strings.xml` (de und en) sind eigene Formulierungen.
- **Prüfung auf wörtliche Übernahme (20.10.2026):** Die Anleitungstexte (`space_setup_oem_*`) wurden gegen die Herstellerseiten im Quellverzeichnis des dontkillmyapp-Repos geprüft (`_vendors-content`: Huawei, Xiaomi, Samsung, OnePlus, OPPO, vivo, ASUS, Nokia und allgemein). Kein Satz stimmt wörtlich überein. Übereinstimmend sind nur Menübezeichnungen in Anführungszeichen und allgemeine Wörter. Die Honor-Anleitung nennt dieselben Menüpunkte wie die Vorlage, der Wortlaut ist eigen. Die Prüfung umfasst das Quellverzeichnis, nicht die Live-Seite.

## Restrisiken

- Hersteller ändern Menüs und Komponenten-Namen ohne Vorwarnung. Dann greift der Fallback.
- Die Freigabe der Hersteller lässt sich nicht prüfen. „Erledigt“ ist eine Selbstbestätigung.
- Die Schwelle von 2 min ist eine Annahme. Auf Geräten mit aggressivem Doze kann sie zu eng oder zu weit sein.
- Der Wecker ist ungenau. Das Ende kann bis zur nächsten Anfrage oder bis zum nächsten Herzschlag auf sich warten lassen. Die Prüfung bei jeder Anfrage fängt das ab, solange der Server läuft. Läuft der Dienst nicht mehr, übernimmt der Neustart nach Prozess-Tod (Abschnitt „Neustart nach Prozess-Tod“). Ohne Akku-Ausnahme bleibt der Space unterbrochen, bis er fortgesetzt wird.
- Eine erkannte Pause gilt nur, solange der Dienst lebt. Wird der Prozess getötet, gibt es keine Herzschlag-Erkennung, bis der Dienst neu startet. Die Unterbrechung dazwischen hält der Neustart als Pause fest (Abschnitt „Neustart nach Prozess-Tod“).
- Ohne Akku-Ausnahme startet der Dienst nach einem Prozess-Tod nicht selbst neu. Der Space bleibt dann unterbrochen, bis der Gastgeber ihn fortsetzt.
- Die Erkennung „dasselbe Netz“ über Adresse, Präfix und Gateway kann bei sehr kurzem Netzwechsel mit gleicher Adresse fälschlich „dasselbe Netz“ melden. Dann fehlt die Bestätigung, der Server bindet an dieselbe Adresse. Das ist akzeptiert, weil die Zugangsschlüssel gültig bleiben.
- Das Speicherlimit ist keine Reservierung. Ein Gerät kann nach dem Start weniger Speicher haben als vor dem Start.

## Konsequenzen

- Der Betrieb ist in Zustände und Übergänge zerlegt, die ohne Android-Typen getestet werden.
- Die Hersteller-Anleitung ist eine Pflege-Aufgabe. Sie braucht bei jedem Gerätetest einen Blick.
- Die R8-Prüfung schützt den Release-Pfad, aber nur über die Klassenliste in `ReleaseMappingGuard`.

## Offen

- Gerätetest für 3.3 (Testkatalog in der PR-Beschreibung).
- Lizenzen am 20.10.2026 geprüft (siehe Quellen). Bei einem Wechsel der Quellen erneut prüfen.
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
- Neustart nach Prozess-Tod (Nachbesserung 3.3b): `RestartPolicyTest` (aktiv, nicht abgelaufen oder abgelaufen, mit und ohne Akku-Ausnahme, Messung nach Geräte-Neustart), `SpaceRunnerTest` (Wiederaufnahme am selben Port mit Pause, Ende in der Unterbrechung, früher Wecker, Start ohne WLAN und ohne Berechtigung), `SpaceServerServiceTest` (Neustart mit Intent ohne Auftrag, Robolectric), `SpaceRunningViewModelTest` und `SpaceStarterTest` (Fortsetzen, Vordergrund verweigert), `SpaceRunningUiStateTest` (Ableitung „unterbrochen“, Speicherwarnung).
- Hersteller-Fallback mit Fake-Paketzugriff (`OemPort`): `OemSettingsLauncherTest`, einschließlich der Erkennung und der Kandidatenliste.
- Berechtigungszustände für API 36 und 37 sowie „Nicht mehr fragen“: `SetupChecksTest`.
- Gemeinsame Datenbank: `SharedSpacesDatabaseTest`.
- Release: `ReleaseMappingGuardTest` (Build-Logik) prüft die Mapping-Auswertung, die Aufgabe `checkReleaseServerPipeline` die Realität im R8-Lauf.
- Goldens (`SpaceRunScreenshotTest`, `SpacesScreenshotTest`): Einrichtung, Start-Fehler, laufender Space (normal, Warnung, Pause, Netzwechsel, unterbrochen mit Banner), beendeter Space, Telefon de und en, großes Display.

### Leere Aufnahmen im gemeinsamen Prozess

Beim Aufnehmen der Goldens entstanden leere Bilder (`#FAFAFA`, ca. 3,4 KB). Eine Verifikation im selben Prozess hätte sie gegen sich selbst als grün gewertet. ADR 0006 beschreibt dieselbe Erscheinung. Deshalb laufen in `feature:space` alle Test-Aufgaben mit `forkEvery = 1`. Nach dem Aufnehmen wird jede Aufnahme auf Größe und Inhalt geprüft, bevor sie in den Commit geht.

## Gerätetest

Der Testkatalog für die Nutzerin bzw. den Nutzer steht in der PR-Beschreibung. Dieser ADR listet nur die Prüfpunkte, die die Architektur betreffen: Start der Hersteller-Einstellung, Pause bei Bildschirm aus, Netzwechsel mit gleicher und anderer Adresse, Ende über die Frist bei verstellter Uhr.

## Nachtrag (20.10.2026): fester Port und Einladung im Betrieb

- **Fester Port pro Space**: Der Port wird beim ersten Start gewählt und in `space.port` gespeichert. Jeder spätere Start, auch nach einer Pause oder einem Prozess-Neustart, versucht diesen Port zuerst. Ist er belegt, wählt der Server einen freien Port. Der Betrieb speichert den neuen Port und meldet die Adressänderung (`RunState.Running.addressChanged`). Die Oberfläche zeigt dann „Neue Adresse. Gäste brauchen den neuen QR-Code.“.
- **Einladung im Betrieb**: Die Einladung bleibt über Pausen im selben Prozess gleich. Nach einem Prozess-Neustart entsteht eine neue Einladung, weil nur ihr Hash gespeichert ist (ADR 0018, Abweichungen). Die Gäste bleiben angemeldet.
- **Ablauf nach der Authentifizierung**: Ein abgelaufener Space antwortet ohne gültigen Schlüssel mit `401`, mit gültigem Schlüssel mit `410` (ADR 0018, Abweichungen). Diese Reihenfolge gilt seit 3.4.

## Nachtrag (Paket 3.4b): R8-Prüfung auf Methoden, keine Keep-Regel

- Die Keep-Regel für `KtorLocalServer` aus Paket 3.4 ist entfernt. R8 hatte die Klasse nicht entfernt, sondern zusammengelegt. Ihr Code steht im Mapping als Inline-Frame und in der dex (`api/v1/join`, `invite_invalid`). Die Prüfung `checkReleaseServerPipeline` sucht deshalb Methoden der Pipeline (Member oder Inline-Frame mit Besitzer) und nicht nur Klassenzeilen. Belege und Begründung in ADR 0018, Abweichungen.
- Die Aussage „keine Keep-Regeln für den Serverpfad“ bleibt gültig.

## Nachtrag (21.10.2026): Nachlauf nach dem Ende 60 s (Paket 3.5a, Korrektur 3.5b)

- Der Nachlauf ist 60 s statt 1 s (`SpaceRunner.CLOSE_GRACE_MILLIS`). Er gilt nach **jedem** Ende: Ablauf, Beenden durch den Gastgeber (in der App mit Bestätigung oder über die Benachrichtigung) und Wecker. Der Verarbeiter nutzt dafür einen einzigen Ende-Pfad (`SpaceRunner.stop`).
- In dieser Zeit antworten authentifizierte Anfragen mit `410 space_closed` und `endedAt` (Stoppzeit des Gastgebers bzw. Frist beim Ablauf). Keine Medien, keine Vorschauen, keine Uploads, kein Beitritt. Die Gäste-Seite selbst bleibt erreichbar, damit die Ende-Ansicht erscheint. Danach stoppt der Server.
- **Space-Status sofort `ENDED`.** Die Datenbank wird beim Ende geschrieben, nicht erst nach dem Nachlauf. Die Oberfläche zeigt den Betrieb als beendet (`RunState.Idle`).
- **Sperren und Dienst.** Die CPU- und WLAN-Sperre bleiben bis zum Ende des Nachlaufs, weil der Server bis dahin antwortet. Die Dauermeldung zeigt „Space beendet – Gäste werden informiert“, ohne Schaltfläche „Beenden“. Der Dienst bleibt im Vordergrund.
- **Pausiert** (WLAN weg): Der Server ist schon aus, der Betrieb endet sofort ohne Nachlauf. **Dienst endet** (System, Prozess-Tod, `DESTROYED`): kein Nachlauf, wie bisher. Der Space bleibt dann `ACTIVE` und unterbrochen, der Neustart entscheidet (Paket 3.3).
- **Neuer Space während des Nachlaufs: wartet.** Ein Start im Nachlauf wird vorgemerkt (ein Platz, der neueste Start gewinnt) und startet, sobald der Nachlauf vorbei ist. Die Meldung „Der vorherige Space ist noch kurz im Nachlauf …“ sagt das. Begründung: Die Oberfläche setzt den neuen Space vor dem Dienststart in der Datenbank auf `ACTIVE` (`SpaceStarter`). Eine Ablehnung ließe einen aktiven Space ohne Server zurück. Ein Abbruch des Nachlaufs für den Start kam nicht in Frage, weil die Gäste die 60 s erhalten sollen.
- **Dauer im Hinweis.** Der Hinweis nennt die Nachlaufdauer aus `SpaceServerService.closeGraceSeconds`, die aus `SpaceRunner.CLOSE_GRACE_MILLIS` abgeleitet ist (Plural in `strings.xml`). Ändert sich die Dauer, ändert sich der Text mit.
- **Bekannte Schwäche: Beenden des wartenden Space.** `Stop(USER)` für den wartenden Start während des Nachlaufs ignoriert der Runner (nur `DESTROYED` bricht den Nachlauf ab). Über die Oberfläche ist das derzeit nicht erreichbar, weil „Space beenden“ im Wartezustand deaktiviert ist. Eine Lösung, die das Beenden des Wartenden zulässt, ist offen und wird mit einem späteren Paket entschieden.
- **Prozess-Tod im Nachlauf.** Kein Neustart des Nachlaufs. Das Ende steht in der Datenbank, der gemerkte Lauf ist gelöscht, und ein vorgemerkter Start geht verloren. Der neue Space bleibt dann `ACTIVE` ohne Lauf und wird als unterbrochen angeboten („Fortsetzen“).
- **Tests.** `SpaceRunnerTest`: jeder Auslöser mit Fake-Uhr (Gastgeber, Herzschlag, Wecker, Anfrage), pausiert, Dienst endet, Wiederholungen, Warteschlange für den Start. `SpaceEndGraceSocketTest`: echter Socket nach manuellem Ende (410 mit `endedAt`, keine Medien, danach geschlossen). `SpaceServerServiceTest`: Sperren bleiben bis zum Nachlauf. Browser-Test `closed.test.mjs`: Stoppzeit der Ende-Ansicht liegt im Fenster des Endes.
- **Korrektur.** Der Nachlauf war bisher nur für den Ablauf umgesetzt. Beim manuellen Beenden schloss der Server seit Paket 3.3 sofort. Den Befund dazu hat 3.5b festgehalten (ADR 0014, Nachtrag endedAt). Die Entscheidung oben gilt für alle Enden.

## Nachtrag (21.10.2026, Paket 3.5a-3): Benachrichtigungen zum Ende und Hinweis im Beenden-Dialog

- **Vorwarnung.** Zehn Minuten vor dem festen Ende meldet der Dienst „Space ‚<Name>‘ endet in 10 Minuten.“. Die Prüfung läuft im Herzschlag (alle 30 s, `SpaceRunPolicy.HEARTBEAT_INTERVAL_MS`). Ein eigener Wecker ist nicht nötig, der Herzschlag läuft während des Betriebs im Vordergrunddienst. Die Vorlaufzeit steht in `EndNotices.WARNING_LEAD_MILLIS`.
- **Meldung beim Ende.** „Space ‚<Name>‘ wurde beendet – Aufbewahrung bis <Datum>“. Das Datum ist das Ende plus die Aufbewahrung in Tagen (`retentionDays`), im Gerätelocale formatiert. Die Meldung kommt bei jedem Ende: Ablauf, Beenden durch den Gastgeber und Ende nach einem Neustart des Dienstes.
- **Keine Doppelten.** Die gesendeten Meldungen merkt sich `PrefsEndNoticeStore` in einer eigenen Einstellungsdatei (`emberlight.end-notices`). Ein Neustart des Dienstes oder der App sendet sie deshalb nicht erneut. Mit dem Ende wird das Merkmal der Vorwarnung gelöscht.
- **Ohne Benachrichtigungsrecht** entfällt die Meldung, die Statuszeile im laufenden Space zeigt die Restzeit.
- **Beenden-Dialog.** Der Dialog nennt jetzt, was Gäste sehen: Wer die Seite offen hat, bekommt den Hinweis sofort. Wer sie gerade nicht offen hat, sieht beim nächsten Versuch nur, dass der Space nicht mehr erreichbar ist. Goldens `SpaceRunEndDialog_phone_de` und `_en`.
- **Abweichung.** Ohne Benachrichtigungsrecht gibt es im Space keinen eigenen Banner. Die Restzeit in der Statuszeile ist der Hinweis in der App.
- Tests: `EndNoticesTest` (Zeitpunkte, Aufbewahrung, Gesendetes), `PrefsEndNoticeStoreTest` (überlebt einen Neustart), Golden des Dialogs.

## Nachtrag (21.10.2026, Paket 3.6a): Verlängern

- **Umfang.** Der Gastgeber verlängert einen laufenden Space um eine Stunde, um einen Tag oder auf ein eigenes Ende. Die Schaltfläche steht nur bei einem aktiven Space (`ACTIVE`), nicht im Nachlauf (dort ist der Space schon `ENDED`). Auch ein unterbrochener oder pausierter Space bleibt aktiv und lässt sich verlängern.
- **Regel.** `SpaceRunPolicy.extendProblem` gilt in drei Stufen: Oberfläche (Fehlertext im Dialog), Verarbeiter und Datenbank (`SpaceRunRepository.extend`). Erlaubt ist nur ein aktiver Space, dessen neue Endzeit in der Zukunft und nach dem bisherigen Ende liegt. Die Gesamtlaufzeit ab Start bleibt bei höchstens 30 Tagen (`MAX_TOTAL_MILLIS`).
- **Uhrenregel.** Der Laufzeit-Anker (Start in Wand-Uhr und `elapsedRealtime`) bleibt unverändert. Nur die Frist ändert sich. Die Prüfung bleibt das frühere von Wand-Uhr und gemessener Laufzeit (`SpaceRunPolicy.isClosed`). Die geplante Dauer ist danach `neues Ende − Start`. Die Tests decken auch eine zurückgestellte Wand-Uhr ab.
- **Serieller Kanal.** `RunCommand.Extend` läuft wie Start und Stopp nacheinander im Verarbeiter. Ein Stopp direkt davor oder danach gilt mit dem jeweils aktuellen Stand. Im Nachlauf wird eine Verlängerung verworfen.
- **Wecker.** Der Wecker wird auf die neue Endzeit gesetzt (`cancelExpiry`, dann `scheduleExpiry`), auch im Leerlauf eines unterbrochenen Space.
- **Gastgeber-Benachrichtigungen.** Die Vorwarnung wird für die neue Endzeit neu geplant. `EndNoticeStore.clearWarned` nimmt die Merkung weg, die alte Vorwarnung wird aus der Benachrichtigungsleiste entfernt. Die Meldung „beendet“ kommt erst mit dem Ende und ist davon nicht berührt.
- **Gäste.** `/space` liefert die neue `endsAt`. `SpaceAccess.extendEnd` erhöht die `revision`, die Gäste-Seite lädt die Liste nicht neu, wenn nur das Ende sich ändert. Beim Abruf erkennt die Seite eine spätere `endsAt` und zeigt das Banner „Der Space wurde bis … verlängert.“ (Live-Region `role="status"`, de und en). Eine offene Vorwarnung von 10 Minuten oder 1 Minute schließt sich, die Warnungen gelten für das neue Ende neu, und der Countdown rechnet mit dem neuen Wert. Die Seite setzt nur `textContent`, ohne Inline-Stile.
- **Speicherung.** Nur die Spalte `expires_at` der Tabelle `space` wird geändert, in einer Transaktion. Keine Migration.
- **Tests.** `SpaceRunPolicyTest` (30-Tage-Grenze, Zukunft, kein Kürzen, nur aktive Spaces), `SpaceRunRepositoryTest` (Persistenz, Grenze, Nachlauf), `SpaceRunnerTest` (Anker, Uhrenregel, Ablehnung, Stopp und Verlängern in schneller Folge, Verlängern im Nachlauf, pausiert, Leerlauf), `EndNoticesTest` (neue Vorwarnung nach Verlängern). Goldens `SpaceExtendDialog_phone_de/en` und `SpaceExtendCustom_phone_de/en`, die neun Lauf-Goldens mit dem Knopf. Browser-Test `extension.test.mjs`: Verlängern während der Ein-Minuten-Warnung.
- **Abweichungen.** (1) Der Auftrag sagt nichts zum Kürzen. Verlängern verlangt deshalb, dass die neue Endzeit nach dem bisherigen Ende liegt. (2) Ohne festes Ende wird Verlängern nicht angeboten. (3) Liegt das neue Ende weniger als 10 Minuten entfernt, meldet die Vorwarnung sofort „endet in 10 Minuten“. Der Text ist fest und ungenau in diesem Fall.
- **Offen.** Gerätetest mit dem Testkatalog, Abschnitt 10 (Punkte 10.11 bis 10.15). Der Dienstpfad (`ServiceHost`) ist nicht per Robolectric geprüft, nur über den Verarbeiter mit Fakes und den Browser-Test.
