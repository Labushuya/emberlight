package io.github.labushuya.emberlight.buildlogic

/**
 * Build-Sperre für Modulgrenzen (ADR 0012): Ein Modul darf über seine gesamte aufgelöste Abhängigkeitskette keine
 * verbotenen Projekte erreichen.
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten, damit sie unit-testbar bleibt. Eingabe ist die Liste der Projektpfade
 * aus dem aufgelösten Graphen (transitiv), nicht nur die direkten Abhängigkeiten.
 *
 * Ein verbotener Eintrag ist entweder ein exakter Projektpfad (`:core:media`) oder ein Präfix mit Stern am Ende
 * (`:feature:*` erfasst alle Projekte unter `:feature:`). Ein Stern mitten im Pfad hat keine Bedeutung.
 */
object ProjectIsolationGuard {
    /** Liefert die verbotenen Projektpfade, die im aufgelösten Graphen vorkommen, sortiert und ohne Dubletten. */
    fun findViolations(
        resolvedProjectPaths: Collection<String>,
        forbiddenProjectPaths: Collection<String>,
    ): List<String> {
        val exact = forbiddenProjectPaths.filterNot { it.endsWith(WILDCARD) }.toSet()
        val prefixes = forbiddenProjectPaths.filter { it.endsWith(WILDCARD) }.map { it.dropLast(1) }
        return resolvedProjectPaths
            .filter { path -> path in exact || prefixes.any { path.startsWith(it) } }
            .distinct()
            .sorted()
    }

    private const val WILDCARD = "*"
}
