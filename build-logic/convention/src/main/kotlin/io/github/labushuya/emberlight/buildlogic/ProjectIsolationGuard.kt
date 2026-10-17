package io.github.labushuya.emberlight.buildlogic

/**
 * Build-Sperre für Modulgrenzen (ADR 0012): Ein Modul darf über seine gesamte aufgelöste Abhängigkeitskette keine
 * verbotenen Projekte erreichen.
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten, damit sie unit-testbar bleibt. Eingabe ist die Liste der Projektpfade
 * aus dem aufgelösten Graphen (transitiv), nicht nur die direkten Abhängigkeiten.
 */
object ProjectIsolationGuard {
    /** Liefert die verbotenen Projektpfade, die im aufgelösten Graphen vorkommen, sortiert und ohne Dubletten. */
    fun findViolations(
        resolvedProjectPaths: Collection<String>,
        forbiddenProjectPaths: Collection<String>,
    ): List<String> {
        val forbidden = forbiddenProjectPaths.toSet()
        return resolvedProjectPaths.filter { it in forbidden }.distinct().sorted()
    }
}
