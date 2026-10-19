package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Build-Sperre für `share:space` (ADR 0014, Modul-Abschnitt). `share:space` ist Domäne und Speicher des Space und
 * hängt nicht von Oberfläche, Galerie oder Server ab:
 *
 * - `checkSpaceIsolation`: kein Zugriff, transitiv, auf `:app`, `:feature:*`, `:core:media` und `:share:server`.
 * - `checkSpaceSources`: kein Galerie- oder Speicherzugriff im Quelltext (gleiche Tokens wie der Server).
 */
class SpaceIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerGraphIsolationCheck("checkSpaceIsolation", FORBIDDEN_PROJECT_PATHS)
        target.registerSourceIsolationCheck("checkSpaceSources", GALLERY_ACCESS_TOKENS)
    }

    companion object {
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":app", ":core:media", ":feature:*", ":share:server")
    }
}
