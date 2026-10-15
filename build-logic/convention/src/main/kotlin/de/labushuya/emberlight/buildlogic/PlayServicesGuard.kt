package de.labushuya.emberlight.buildlogic

/**
 * Harte Grenze aus CLAUDE.md: keine Abhängigkeit von Google Play Services.
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten, damit sie unit-testbar bleibt.
 */
object PlayServicesGuard {
    val FORBIDDEN_GROUPS: List<String> =
        listOf(
            "com.google.android.gms",
            "com.google.firebase",
            "com.google.mlkit",
            "com.google.android.play",
        )

    /** Liefert alle Koordinaten im Format `group:name:version`, deren Group verboten ist. */
    fun findViolations(coordinates: Collection<String>): List<String> =
        coordinates.filter { coordinate -> isForbiddenGroup(coordinate.substringBefore(':')) }

    fun isForbiddenGroup(group: String): Boolean =
        FORBIDDEN_GROUPS.any { prefix -> group == prefix || group.startsWith("$prefix.") }
}
