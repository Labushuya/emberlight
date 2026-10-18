package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.kotlin.dsl.register

/**
 * Build-Sperre für `share:server` (ADR 0012). Zwei Prüfungen hängen in `check`:
 *
 * - `checkServerIsolation`: Der Server darf über keine auflösbare Konfiguration (Compile, Runtime, Unit-Test,
 *   AndroidTest, alle Varianten) ein verbotenes Modul erreichen. Verboten sind die Galerie (`:core:media`,
 *   `:feature:*`) und die App.
 * - `checkServerSources`: Der Quelltext des Servers (`src/`) enthält keine Medien- oder Speicherzugriffe der Galerie.
 *
 * Damit bleibt die Trennung aus [docs/KONZEPT.md] auch im Build erzwungen.
 */
class ServerIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Die Varianten-Konfigurationen (debugRuntimeClasspath, debugUnitTestRuntimeClasspath …) legt AGP erst nach der
        // Auswertung an. Dieser Hook läuft nach AGP, weil AGP vorher angewendet wird.
        target.afterEvaluate {
            val resolvable = target.configurations.filter { it.isCanBeResolved }
            val isolation =
                target.tasks.register<CheckProjectIsolationTask>("checkServerIsolation") {
                    group = "verification"
                    description =
                        "Bricht ab, wenn share:server über eine auflösbare Konfiguration ein verbotenes Modul erreicht."
                    guardedProject.set(target.path)
                    resolvedProjectPaths.set(target.provider { projectPathsOf(resolvable) })
                    forbiddenProjectPaths.set(FORBIDDEN_PROJECT_PATHS)
                    outputs.upToDateWhen { false }
                }
            val sources =
                target.tasks.register<CheckSourceIsolationTask>("checkServerSources") {
                    group = "verification"
                    description =
                        "Bricht ab, wenn der Quelltext von share:server Medien- oder Speicherzugriffe der Galerie enthält."
                    forbiddenTokens.set(FORBIDDEN_SOURCE_TOKENS)
                    sourceFiles.from(target.fileTree("src"))
                    baseDirectory.set(target.layout.projectDirectory)
                }
            target.tasks.named("check") { it.dependsOn(isolation, sources) }
        }
    }

    private fun projectPathsOf(configurations: Collection<Configuration>): List<String> =
        configurations
            .flatMap { configuration ->
                configuration.incoming.resolutionResult.allComponents
                    .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
            }.distinct()
            .sorted()

    companion object {
        /** Projekte, die share:server nie erreichen darf. `:feature:*` sind die Oberflächen-Module. */
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":app", ":core:media", ":feature:*")

        /** Tokens, die im Quelltext des Servers nicht vorkommen dürfen (Galerie- und Speicherzugriffe). */
        val FORBIDDEN_SOURCE_TOKENS: List<String> =
            listOf(
                "MediaStore",
                "ContentResolver",
                "contentResolver",
                "READ_MEDIA_",
                "READ_EXTERNAL_STORAGE",
                "WRITE_EXTERNAL_STORAGE",
                "MANAGE_EXTERNAL_STORAGE",
                "getExternalStorage",
                "getExternalFilesDir",
                "getExternalMediaDirs",
            )
    }
}
