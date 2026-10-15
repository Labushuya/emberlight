# ADR 0002: Navigation

Status: angenommen, 15.10.2026

## Kontext

Meilenstein 1 hat zwei Top-Level-Ziele: Galerie und Spaces. Ziele sollen typsicher adressiert werden, die Navigation soll sich auf Handy und großem Bildschirm passend verhalten.

## Entscheidung

- Navigation Compose 2.10.2 mit typsicheren Routen: `@Serializable`-Klassen wie `GalleryRoute` und `SpacesRoute`, Ziele über `composable<...>`, Prüfung über `hasRoute`.
- Top-Level-Navigation über `NavigationSuiteScaffold` (Material 3 Adaptive Navigation Suite). Auf dem Handy erscheint die Leiste unten, auf großen Bildschirmen die Seitenleiste.
- Jedes Feature-Modul liefert seine eigenen Routen und Ziele. Die Zusammenstellung bleibt in `app`.

## Konsequenzen

- Ein Tippfehler in einer Route ist ein Compiler-Fehler, keine Laufzeitüberraschung.
- Neue Ziele brauchen eine `@Serializable`-Route. Argumente werden ebenfalls typisiert.
- Die Navigation ist durch Robolectric-Tests abgedeckt (siehe ADR 0005).

## Alternativen

- Navigation 3: nicht gewählt. Die offizielle Navigation-Doku sagt für Apps, die vollständig mit Jetpack Compose gebaut sind: „If your app is built entirely with Jetpack Compose, use Navigation Compose.“ In Meilenstein 1 bleibt es deshalb bei Navigation Compose 2.10. Navigation 3 wurde geprüft. Ein Wechsel wird in einem Folge-ADR geprüft, sobald die Navigation größer wird.
- Stringbasierte Routen: verworfen, fehleranfällig und ohne Typprüfung.
- Eigene Navigations-Logik ohne Bibliothek: verworfen, unnötiger Aufwand für den Umfang.

## Quellen

- Android-Doku, Navigation (Empfehlung für reine Compose-Apps): https://developer.android.com/guide/navigation
