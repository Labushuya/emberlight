# emberlight Netlab

Messwerkzeug für Meilenstein 4 (Paket 4.0, „Netlab-Prototyp“). Es prüft auf Android-Geräten, was mit eigenem Hotspot,
Gast-Verbindung und paralleler Nutzung mobiler Daten tatsächlich geht. Die Grundlage sind die Messpunkte M1–M9 in
[docs/KONZEPT-M4.md](../../docs/KONZEPT-M4.md), Abschnitt 13.

Das Werkzeug ist Messcode, kein Teil der Haupt-App:

- eigene App-ID `io.github.labushuya.emberlight.netlab`, Name „emberlight Netlab“
- keine Abhängigkeit zu `app`, `core`, `feature` oder `share` (Prüfung `checkNetlabIsolation`)
- nicht in der Release-APK der Haupt-App und nicht im Release-Workflow (alle Release-Aufgaben des Moduls sind
  abgeschaltet)
- keine Verbindung zu externen Servern. Die Erreichbarkeit des Internets wird nur über `NetworkCapabilities` bestimmt.
- kein Google-Play-Dienst

## Was das Werkzeug misst

| Bereich | Inhalt |
|---|---|
| Fähigkeiten | Android-Version, Gerät, STA+AP-Betrieb, gleichzeitige lokale Verbindung, 5-GHz-Band, Standortdienst, aktive Netze, Berechtigungen. Nicht verfügbare APIs stehen als „nicht verfügbar“. |
| Gastgeber | LocalOnlyHotspot starten und beenden, SSID, Passwort, Sicherheit, Band, Adresse und Schnittstelle des Hotspots, Zeitlinie der Netze, Testserver (`/ping`, `/upload`, `/browser`), drei QR-Codes, Gästezählung |
| Gast | QR-Code scannen oder Text einfügen, verbinden, gebundener Ping, Upload mit 10 MB und Durchsatz, ungebundener Ping über das Standardnetz (wie ein Browser), Zustand der Netze, VPN-Erkennung |
| Bericht | Text, gegliedert nach M1–M9. Teilen über das Android-Teilen-Menü. Zusätzlich Log mit Tag `EmberlightNetlab`. |

SSID und Passwort stehen im Bericht maskiert (nur erster Buchstabe bzw. Länge). Der Bericht enthält das Gerätemodell,
sonst keine persönlichen Daten.

## Geräte und Rollen

- **Mindestens zwei Android-Geräte mit Android 10 oder neuer** für M1, M2, M4 und M9: eines als Gastgeber, eines
  als Gast.
- Für M3 (Browser-Gast) kommt ein Gerät ohne Netlab-App dazu, etwa ein iPhone oder ein Laptop mit Browser.
- Für M6 (Gerätematrix) reichen Geräte mit Android 8/9, 10/11, 12/13, 14–16 und 17, mindestens drei Hersteller.
  Das Honor Magic V2 gehört dazu.
- Die Netlab-Debug-APK auf jedes Gerät installieren, dafür „Installation aus unbekannten Quellen“ erlauben.

Das Werkzeug läuft nur, solange die App im Vordergrund ist. Ein Dienst im Hintergrund ist nicht umgesetzt. Deshalb ist
M8 (zwei Stunden Laufzeit) nur mit dem Bildschirm an und der App offen messbar.

## Messanleitung je Punkt

Vor jedem Lauf den Bericht nach dem Lauf über „Messbericht erstellen“ erzeugen. Die Abschnitte M1–M9 im Bericht sind die
Ergebnisse. Den Text über „Bericht teilen“ weitergeben, zum Beispiel per Nachricht an die Auswertung.

### M1: Gastgeber mit Hotspot, mobilen Daten und WLAN gleichzeitig

1. Gastgeber-Gerät mit einem WLAN verbinden, das Internet hat. Mobile Daten einschalten.
2. Netlab öffnen, „Gastgeber: Hotspot starten“. Die Berechtigungen bestätigen.
3. 30 Minuten laufen lassen. Die App offen lassen.
4. Parallel mit einer zweiten App (Browser, Kartenapp) eine Seite laden. Das zeigt, ob mobile Daten für fremde Apps
   laufen.
5. Bericht erstellen. Abschnitt M1 lesen:
   - „Hotspot“ steht auf „gestartet“, keine Zeile „fehlgeschlagen“.
   - „Ergebnis“: „WLAN blieb verbunden“ oder „WLAN wurde durch den Hotspot getrennt“.
   - Im Verlauf: „Mobilfunk validiert“ bleibt stehen, sonst „Mobilfunk nicht mehr validiert“.
   - „Laufzeit“ mindestens 30 Minuten.

### M2: Gast-App, Netz-Bindung, mobile Daten parallel

1. Gastgeber startet den Hotspot (Schritte aus M1, ohne 30 Minuten).
2. Gast-Gerät (Android 10 oder neuer): Netlab, „Gast: Mit Hotspot verbinden“, dann „QR-Code scannen“ auf den
   QR-Code „für Netlab-Gäste“ am Gastgeber-Gerät.
3. „Mit Hotspot verbinden“. Android fragt nach. Bestätigen.
4. „Messungen starten“.
5. Den Vorgang zehnmal wiederholen (Verbinden, Messen, Trennen). Die Verbindung zehnmal aufbauen.
6. Bericht erstellen. Abschnitt M2 lesen:
   - „Verbindungsaufbau“: ok und höchstens 30 Sekunden. Zählen, wie oft es klappt (Ziel: 9 von 10).
   - „Gebundener Ping“ und „Upload“: ok.
   - „Ungebundener Ping“: zeigt, was ein Browser ohne Bindung erreicht. Scheitert er bei aktiven mobilen Daten, ist
     das ein Befund für M3.
   - „VPN aktiv“: nein, sofern kein VPN läuft.
7. Während des Gast-Laufs eine zweite App mit mobilen Daten testen. Das ergibt das Ergebnis „mobile Daten für fremde
   Apps aktiv“.

### M3: Browser-Gast im Hotspot mit mobilen Daten (Android und iPhone)

1. Gastgeber startet den Hotspot.
2. Das Gast-Gerät ohne Netlab-App: Den QR-Code „für WLAN-Gäste ohne App“ scannen und dem WLAN beitreten.
3. Den QR-Code „für den Browser-Test“ scannen. Die Seite öffnet sich im Browser.
4. Erwartet: die Seite zeigt „Verbunden“. Auf dem Gast-Gerät erscheint der Hinweis, dass das Netz kein Internet hat.
   Dieser Hinweis ist kein Fehler. Er ist ein Befund für M3 („Verbunden bleiben“).
5. Ergebnis im Bericht eintragen. Die Zeile „Manuell“ im Abschnitt M3 bleibt ein Hinweis. Der Upload von 50 MB im
   Browser ist in diesem Werkzeug nicht umgesetzt (siehe „Nicht enthalten“).

### M4: Maximale Gästezahl

1. Gastgeber startet den Hotspot, bleibt offen.
2. Gäste schrittweise zuschalten: 2, 4, 8, 10, 12, 15 Geräte. Jedes Gerät verbindet sich wie in M2.
3. Der Gastgeber sieht im Bereich „Gäste“ die Zahl der Gäste seit Start und die aktiven Gäste.
4. Bei einem Verbindungsfehler: Den Gast-Bildschirm notieren („Verbindung fehlgeschlagen“, mit Grund).
5. Je Gerät drei Läufe. Die Zahl steht im Bericht im Abschnitt M4. Der Bericht sagt, dass Android die Grenze nicht
   nennt. Das Ergebnis ist die höchste Zahl, bei der alle drei Läufe stabil waren.

### M5: VPN-Apps (zwei bis drei, vom Nutzer gewählt)

1. VPN-App auf dem Gast-Gerät einrichten. Einmal mit Ausnahme für das lokale Netz („LAN erlauben“, Split-Tunneling),
   einmal ohne Ausnahme.
2. Jeden Fall im Hotspot-Modus und im WLAN-Modus testen (WLAN-Modus: Gastgeber im vorhandenen WLAN, wie in M1).
3. Netlab-Gast-Messung starten. Im Bericht den Abschnitt M5 und „VPN aktiv“ lesen. Die Seite lädt, wenn der
   gebundene Ping und der Browser-Test ok sind.
4. Pro App notieren: Ausnahme nötig, Sperre oder kein Effekt. Dieses Werkzeug misst die Ausnahme nicht selbst. Die
   Auswertung ist manuell.

### M6: Gerätematrix

1. Pro Gerät: Gastgeber-Lauf starten („Hotspot starten“).
2. Bericht erstellen. Abschnitt M6 lesen: Gerät, API-Stufe, „LocalOnlyHotspot: gestartet“ oder der Fehlergrund.
3. Die Stufe muss zur Android-Version passen. API 26 bis 28 ist Stufe 2, API 29 und neuer Stufe 1.
4. Die Liste aller Geräte im Auswertungsdokument führen: Hersteller, Modell, Android-Version, Ergebnis.

### M7: Browser-Grenze

1. Auf jedem Browser-Gerät (Chrome 76 und 88, Safari 14, 15.0 und 15.4 bzw. iOS und macOS, Firefox 85 und 89) den
   Browser-QR-Code öffnen.
2. Prüfen, ob die Seite „Verbunden“ zeigt und ob mit dem Tabulator der Fokusring sichtbar ist.
3. Das Werkzeug selbst misst die Browser-Funktionen nicht. Das Ergebnis je Version wird manuell notiert. Der Abschnitt
   M7 im Bericht verweist darauf.

### M8: Lange Laufzeit

1. Gastgeber startet den Hotspot. Akku-Ausnahme für emberlight Netlab einstellen.
2. Zwei Stunden laufen lassen, die App offen und der Bildschirm an.
3. Bericht erstellen. Abschnitt M8 zeigt die Laufzeit, die Zahl der Lebenszeichen (je Minute) und die längste Pause.
   Ziel: keine Pause länger als 2 Minuten.

### M9: Ende und Netzwechsel

1. Gastgeber und Gast verbunden (wie in M2).
2. Gastgeber tippt auf „Hotspot beenden“.
3. Auf dem Gast-Gerät erscheint im Verlauf „Hotspot-Verbindung verloren“.
4. Bericht erstellen. Abschnitt M9 zeigt den Zeitpunkt des Verlusts und die Erklärung für den Gast.

## Nicht enthalten

- Upload und Download im Browser (M3, M7): Die Gäste-Seite der Haupt-App gehört dazu. Netlab liefert nur die
  Testseite „Verbunden“.
- VPN-Ergebnisse je App (M5): Nur die Erkennung, ob ein VPN aktiv ist. Die Ausnahmen prüft der Mensch.
- Hintergrundbetrieb (M8): Der Hotspot läuft nur, solange die App offen ist.
- Browser-Messungen, Geräte-Matrix und Auswertung (M6, M7): manuell.

## Build

```
./gradlew :tools:netlab:assembleDebug
./gradlew :tools:netlab:check
```

Die Debug-APK liegt danach unter `tools/netlab/build/outputs/apk/debug/`.
