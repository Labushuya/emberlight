package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Bricht ab, sobald der Release-Classpath eine Abhängigkeit aus Google Play Services enthält. */
abstract class CheckNoPlayServicesTask : DefaultTask() {
    /** Aufgelöste Koordinaten im Format `group:name:version`. */
    @get:Input
    abstract val coordinates: ListProperty<String>

    @TaskAction
    fun check() {
        val violations = PlayServicesGuard.findViolations(coordinates.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Verbotene Abhängigkeiten von Google Play Services im Release-Classpath:")
                    violations.forEach { appendLine("  - $it") }
                    append("emberlight darf nicht von Google Play Services abhängen, siehe CLAUDE.md (Harte Grenzen).")
                },
            )
        }
    }
}
