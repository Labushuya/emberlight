# ADR 0003: Theme und Schriften

Status: angenommen, 15.10.2026

## Kontext

Der Banner (`docs/banner.png`) gibt die Stimmung vor: dunkel, warme Glut, Funken. Die Schriften müssen offline funktionieren. Downloadable Fonts von Google brauchen Google Play Services, und die sind verboten (siehe [CLAUDE.md](../../CLAUDE.md)).

## Entscheidung

- Das Theme ist immer dunkel. Einen hellen Modus gibt es nicht.
- Keine Dynamic Colors. Wallpaper-Farben würden die Markenfarben überlagern.
- Die Palette ist aus dem Banner abgeleitet und liegt in `core/design` (`EmberlightPalette`). Komponenten greifen nur über `MaterialTheme.colorScheme` darauf zu.
- Schriften: Fraunces für Titel, Inter für Text. Sie liegen als Dateien im APK (`core/design/src/main/res/font`).
- Lizenztexte (SIL Open Font License) liegen im APK unter `core/design/src/main/assets/licenses`.
- Ein Unit-Test prüft die Kontraste nach WCAG 2.x: Text mindestens 4.5:1, Umrisse und Grafik mindestens 3:1 (`EmberlightColorContrastTest`).
- Das Basis-Theme steht in Meilenstein 1. Das Design-System entsteht ab Meilenstein 2.

## Konsequenzen

- Keine Abhängigkeit von Play Services. Die Schriften funktionieren auch ohne Netz.
- Das APK ist durch die Schriftdateien größer.
- Jede neue Farbe muss den Kontrasttest bestehen. Das ist gewollt.
- Systemfarben oder ein Hell-Modus sind nicht vorgesehen. Eine Änderung braucht ein neues ADR.

## Alternativen

- Dynamic Color (Android 12+): verworfen, die Markenfarben würden verloren gehen.
- Downloadable Fonts: verworfen, sie setzen Google Play Services voraus.
- Systemschriften: verworfen, die Marke braucht eigene Schriften.
- Hell-Modus neben dem dunklen: nicht vorgesehen, siehe Entscheidung.
