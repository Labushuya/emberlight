package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Prüfung des gemergten Release-Manifests (ADR 0014, Umfang 3.1a): Der Release-Build enthält keinen Debug-Einstieg.
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten. Liefert die Tokens, die im Manifest vorkommen.
 */
object ReleaseManifestGuard {
    fun findViolations(
        manifest: String,
        forbiddenTokens: Collection<String>,
    ): List<String> = forbiddenTokens.filter { manifest.contains(it) }.distinct().sorted()
}

/** Bricht ab, wenn das gemergte Release-Manifest einen verbotenen Eintrag enthält. */
abstract class CheckReleaseManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val forbiddenTokens: ListProperty<String>

    @TaskAction
    fun check() {
        val file = manifest.get().asFile
        val violations = ReleaseManifestGuard.findViolations(file.readText(Charsets.UTF_8), forbiddenTokens.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Das Release-Manifest enthält verbotene Einträge (Debug-Einstieg):")
                    violations.forEach { appendLine("  - $it") }
                    append("Siehe docs/adr/0014-space-speicher-und-server-api.md, Debug-Einstieg.")
                },
            )
        }
    }
}
