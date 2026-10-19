package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gegenrichtung der Trennung (ADR 0014, Umfang 3.1a): Galerie-Module dürfen nicht von `share:*` abhängen.
 *
 * Gilt für `:core:media` und `:feature:gallery`. `:app` ist ausgenommen, weil die App den Server einbindet (Einstieg).
 * Die Prüfung `checkGalleryNoSpaceDependency` hängt in `check`.
 */
class GalleryIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerGraphIsolationCheck("checkGalleryNoSpaceDependency", FORBIDDEN_PROJECT_PATHS)
    }

    companion object {
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":share:*")
    }
}
