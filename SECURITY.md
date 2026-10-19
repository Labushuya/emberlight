# Sicherheitsrichtlinie

## Sicherheitslücke melden

Bitte melde Sicherheitslücken **nicht** als öffentliches Issue oder Pull Request. Nutze stattdessen die private Meldung über GitHub:

1. Im Repository auf **Security** gehen.
2. **Report a vulnerability** wählen (Private vulnerability reporting).
3. Beschreibung, betroffene Version bzw. Commit, Android-Version und Gerät sowie die Schritte zur Reproduktion angeben.

Das ist ein Hobbyprojekt. Ich bemühe mich, eine Meldung innerhalb von sieben Tagen zu bestätigen. Eine Belohnung für Meldungen gibt es nicht.

Bitte veröffentliche Details erst, wenn eine Korrektur verfügbar ist, damit Nutzerinnen und Nutzer Zeit haben, zu aktualisieren.

## Unterstützte Versionen

Unterstützt wird das jeweils neueste GitHub-Release (siehe [Releases](https://github.com/Labushuya/emberlight/releases)) sowie der Stand auf `main`. Ältere Versionen erhalten keine Sicherheitskorrekturen.

## Schutzbereich

emberlight ist bewusst klein gehalten: keine Konten, keine Cloud, kein Tracking, keine Abhängigkeit von Google Play Services. Sicherheitsrelevant sind vor allem:

- **Lokaler Space-Server.** Er läuft nur, solange ein Space aktiv ist. Er soll nur über das lokale Netz erreichbar sein und Zugriffe nur mit Zugangsschlüssel zulassen (siehe [ADR 0013](docs/adr/0013-schutz-gegen-manipulation.md)).
- **Trennung von Space und Galerie.** Medien eines Space liegen im app-internen Space-Speicher. Die persönliche Galerie ist für Server und Teilnehmer nicht erreichbar (siehe [KONZEPT](docs/KONZEPT.md)).
- **Standortdaten.** Beim Teilen werden Standortangaben aus Fotos entfernt (siehe [ADR 0015](docs/adr/0015-entfernung-von-standortdaten.md)).

## English summary

Please report security vulnerabilities privately through GitHub's **Report a vulnerability** function under the repository's **Security** tab. Do not open public issues or pull requests for them. Only the latest release and the `main` branch are supported. Please wait for a fix before disclosing details.
