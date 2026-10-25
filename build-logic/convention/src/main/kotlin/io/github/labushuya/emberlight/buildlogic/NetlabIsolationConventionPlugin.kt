package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Build-Sperre für `tools:netlab` (Meilenstein 4, Paket 4.0). Das Messwerkzeug ist von der Haupt-App getrennt: Es darf über
 * keine auflösbare Konfiguration (transitiv) die App, Core-, Feature- oder Share-Module erreichen. Die Prüfung hängt in `check`.
 *
 * Gegenrichtung: Die Haupt-App darf `tools:netlab` nicht erreichen. Das belegt der Release-Lauf ohne Netlab-Tasks (siehe
 * `docs/` bzw. `tools/netlab/README.md`).
 */
class NetlabIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerGraphIsolationCheck("checkNetlabIsolation", FORBIDDEN_PROJECT_PATHS)
    }

    companion object {
        /** Alle Module der Haupt-App und ihrer Bibliotheken. Das Messwerkzeug bleibt davon unabhängig. */
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":app", ":core:*", ":feature:*", ":share:*")
    }
}
