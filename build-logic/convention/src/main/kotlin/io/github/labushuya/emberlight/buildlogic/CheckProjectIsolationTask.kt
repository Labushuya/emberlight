package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Bricht ab, sobald das geschützte Modul über den aufgelösten Graphen ein verbotenes Projekt erreicht. */
abstract class CheckProjectIsolationTask : DefaultTask() {
    /** Projekt, dessen Abhängigkeiten geprüft werden, z. B. `:share:server`. Nur für die Meldung. */
    @get:Input
    abstract val guardedProject: Property<String>

    /** Alle Projektpfade im aufgelösten Graphen (transitiv). */
    @get:Input
    abstract val resolvedProjectPaths: ListProperty<String>

    /** Projektpfade, die das geschützte Modul nicht erreichen darf. */
    @get:Input
    abstract val forbiddenProjectPaths: ListProperty<String>

    @TaskAction
    fun check() {
        val violations =
            ProjectIsolationGuard.findViolations(
                resolvedProjectPaths = resolvedProjectPaths.get(),
                forbiddenProjectPaths = forbiddenProjectPaths.get(),
            )
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Verbotene Modulabhängigkeit in ${guardedProject.get()} (aufgelöster Graph, transitiv):")
                    violations.forEach { appendLine("  - $it") }
                    append("Das Modul darf diese Projekte nicht erreichen, siehe docs/adr/0012-lokaler-server.md.")
                },
            )
        }
    }
}
