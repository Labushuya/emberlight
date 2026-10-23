package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Build-Sperre für `share:export` (ADR 0022, Modulstruktur). Das Modul schreibt die Kopien in den Medienspeicher und hängt
 * nur von der Domäne `share:space` ab. Die Regel ist eine Erlaubnisliste: Jedes andere Projekt des Builds ist verboten, auch
 * ein künftiges. Die Galerie darf umgekehrt nicht von `share:*` abhängen (`checkGalleryNoSpaceDependency`).
 *
 * Begründung der Erlaubnisliste:
 * - `:share:space` ist erlaubt, weil dort die Regeln der Übernahme, die Vermerke und die Sperre liegen.
 * - `:share:server`, `:share:network` und die Oberflächen (`:feature:*`, `:app`) sind verboten. Der Server darf keine
 *   Medienspeicher-Schreibseite erreichen, und die Oberfläche hängt nur oben an der Übernahme.
 * - `:core:media` ist verboten. Die Galerie wird nicht über die Übernahme erreicht, die Auswahl kommt aus dem Space.
 *
 * `checkExportIsolation` hängt in `check`.
 */
class ExportIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerGraphIsolationCheck(
            taskName = "checkExportIsolation",
            forbiddenProjects = forbiddenOutside(target.rootProject.allprojects.map { it.path }, target.path),
        )
    }

    companion object {
        /** Projekte, die `share:export` erreichen darf. Alles andere im Build ist verboten. */
        val ALLOWED_PROJECT_PATHS: List<String> = listOf(":share:space")

        /**
         * Die verbotenen Pfade: alle Projekte des Builds außer dem Modul selbst und der Erlaubnisliste. Reine Funktion,
         * damit sie ohne Gradle prüfbar ist.
         */
        fun forbiddenOutside(
            allProjectPaths: Collection<String>,
            guardedPath: String,
            allowed: Collection<String> = ALLOWED_PROJECT_PATHS,
        ): List<String> =
            allProjectPaths
                .filterNot { it == guardedPath || it in allowed }
                .distinct()
                .sorted()
    }
}
