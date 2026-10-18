package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.kotlin.dsl.register

/**
 * Build-Sperre für `share:server` (ADR 0012): Der Server darf weder `core:media` noch die Galerie erreichen. Die
 * Trennung aus [docs/KONZEPT.md] bleibt damit auch im Build erzwungen. Der Task `checkServerIsolation` hängt in `check`.
 */
class ServerIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Die Varianten-Konfigurationen (debugRuntimeClasspath) legt AGP erst nach der Auswertung an.
        target.afterEvaluate {
            val resolvedPaths =
                listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").map { configName ->
                    target.configurations.named(configName).map { configuration ->
                        configuration.incoming.resolutionResult.allComponents
                            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
                    }
                }
            val isolation =
                target.tasks.register<CheckProjectIsolationTask>("checkServerIsolation") {
                    group = "verification"
                    description =
                        "Bricht ab, wenn share:server über den aufgelösten Graphen core:media oder die Galerie erreicht."
                    guardedProject.set(target.path)
                    resolvedProjectPaths.set(
                        resolvedPaths[0].zip(resolvedPaths[1]) { a, b -> (a + b).distinct().sorted() },
                    )
                    forbiddenProjectPaths.set(FORBIDDEN_PROJECT_PATHS)
                    outputs.upToDateWhen { false }
                }
            target.tasks.named("check") { it.dependsOn(isolation) }
        }
    }

    private companion object {
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":core:media", ":feature:gallery")
    }
}
