package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.kotlin.dsl.register

/** Verweis auf die Entscheidungen, die die Build-Sperren begründen. Steht in den Meldungen der Prüfungen. */
internal const val ISOLATION_ADR_REFERENCE = "docs/adr/0012 und 0014"

/**
 * Registriert `checkXyzIsolation`: Das Modul darf über keine auflösbare Konfiguration (transitiv) eines der
 * [forbiddenProjects] erreichen. Die Prüfung hängt an `check`.
 *
 * Die Varianten-Konfigurationen legt AGP erst nach der Auswertung an. Deshalb läuft die Registrierung in
 * `afterEvaluate`.
 */
internal fun Project.registerGraphIsolationCheck(
    taskName: String,
    forbiddenProjects: List<String>,
) {
    afterEvaluate {
        val resolvable = configurations.filter { it.isCanBeResolved }
        val check =
            tasks.register<CheckProjectIsolationTask>(taskName) {
                group = "verification"
                description = "Bricht ab, wenn $path über eine auflösbare Konfiguration ein verbotenes Modul erreicht."
                guardedProject.set(path)
                resolvedProjectPaths.set(provider { projectPathsOf(resolvable) })
                forbiddenProjectPaths.set(forbiddenProjects)
                outputs.upToDateWhen { false }
            }
        tasks.named("check") { it.dependsOn(check) }
    }
}

/** Registriert `checkXyzSources`: Der Quelltext des Moduls enthält keines der [forbiddenTokens]. Hängt an `check`. */
internal fun Project.registerSourceIsolationCheck(
    taskName: String,
    forbiddenTokens: List<String>,
) {
    afterEvaluate {
        val check =
            tasks.register<CheckSourceIsolationTask>(taskName) {
                group = "verification"
                description = "Bricht ab, wenn der Quelltext von $path verbotene Medien- oder Speicherzugriffe enthält."
                this.forbiddenTokens.set(forbiddenTokens)
                sourceFiles.from(fileTree("src"))
                baseDirectory.set(layout.projectDirectory)
            }
        tasks.named("check") { it.dependsOn(check) }
    }
}

/** Alle Projektpfade im aufgelösten Graphen (transitiv), über die angegebenen Konfigurationen. */
internal fun projectPathsOf(configurations: Collection<Configuration>): List<String> =
    configurations
        .flatMap { configuration ->
            configuration.incoming.resolutionResult.allComponents
                .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
        }.distinct()
        .sorted()

/** Tokens, die im Quelltext eines Moduls ohne Galerie-Zugriff nicht vorkommen dürfen (ADR 0012, 0014). */
internal val GALLERY_ACCESS_TOKENS: List<String> =
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
