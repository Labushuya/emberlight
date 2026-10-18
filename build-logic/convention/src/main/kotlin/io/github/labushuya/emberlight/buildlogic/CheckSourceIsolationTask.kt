package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Bricht ab, sobald eine Quelldatei des geschützten Moduls ein verbotenes Token enthält. */
abstract class CheckSourceIsolationTask : DefaultTask() {
    /** Tokens, die im Quelltext des geschützten Moduls nicht vorkommen dürfen. */
    @get:Input
    abstract val forbiddenTokens: ListProperty<String>

    /** Alle zu prüfenden Dateien (Quelltext, Ressourcen, Manifest). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** Wurzel für die relativen Pfade in der Meldung, das Projektverzeichnis. */
    @get:Internal
    abstract val baseDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val base = baseDirectory.get().asFile.toPath()
        val files =
            sourceFiles.files
                .filter { it.isFile }
                .associate { file ->
                    val relative = base.relativize(file.toPath()).joinToString("/")
                    relative to file.readLines(Charsets.UTF_8)
                }
        val violations = SourceIsolationGuard.findViolations(files, forbiddenTokens.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Verbotener Quelltext im geschützten Modul (Datei:Zeile: Token):")
                    violations.forEach { appendLine("  - $it") }
                    append(
                        "Der Server darf nicht auf Medien oder Speicher der Galerie zugreifen, siehe docs/adr/0012-lokaler-server.md.",
                    )
                },
            )
        }
    }
}
