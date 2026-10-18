package io.github.labushuya.emberlight.buildlogic

/**
 * Quelltext-Sperre für `share:server` (ADR 0012): Der Server darf keine Medien- oder Speicherzugriffe der Galerie
 * enthalten, auch nicht ohne Abhängigkeit (z. B. über `ContentResolver` oder Berechtigungen auf geteilte Medien).
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten. Eingabe: Dateiname (relativ) zu seinen Zeilen. Ausgabe: je Treffer eine
 * Zeile im Format `datei:zeile: Token`. Ein Treffer ist ein Vorkommen des Tokens als Teilstring der Zeile.
 */
object SourceIsolationGuard {
    fun findViolations(
        filesWithLines: Map<String, List<String>>,
        forbiddenTokens: Collection<String>,
    ): List<String> =
        buildList {
            for ((file, lines) in filesWithLines.toSortedMap()) {
                lines.forEachIndexed { index, line ->
                    forbiddenTokens
                        .filter { line.contains(it) }
                        .distinct()
                        .sorted()
                        .forEach { token -> add("$file:${index + 1}: $token") }
                }
            }
        }
}
