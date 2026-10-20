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
 * Prüfung der R8-Mapping-Datei der Release-Variante (Paket 3.3, ADR 0017). Der Server ist im Release über die Oberfläche
 * erreichbar. R8 darf die Server-Pipeline deshalb nicht entfernen. Die Mapping-Datei nennt jede erhaltene oder umbenannte
 * Klasse mit ihrem Originalnamen, eine entfernte Klasse fehlt dort.
 *
 * Reine Funktion ohne Gradle-Abhängigkeiten. Liefert die Klassen, die fehlen.
 */
object ReleaseMappingGuard {
    /** Originalnamen, die im Release erhalten bleiben müssen: die Pipeline, der Server und die Ktor-Engine. */
    val SERVER_PIPELINE_CLASSES: List<String> =
        listOf(
            "io.github.labushuya.emberlight.share.server.ServerPipelineKt",
            "io.github.labushuya.emberlight.share.server.KtorLocalServer",
            "io.ktor.server.cio.CIOApplicationEngine",
        )

    fun missing(
        mapping: String,
        required: Collection<String>,
    ): List<String> {
        val classLines = mapping.lineSequence().filter { !it.startsWith(" ") }.toSet()
        return required.filter { name -> classLines.none { it.startsWith("$name -> ") } }
    }
}

/** Bricht ab, wenn die R8-Mapping-Datei eine erforderliche Klasse der Server-Pipeline nicht nennt. */
abstract class CheckReleaseMappingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:Input
    abstract val requiredClasses: ListProperty<String>

    @TaskAction
    fun check() {
        val text = mapping.get().asFile.readText(Charsets.UTF_8)
        val missing = ReleaseMappingGuard.missing(text, requiredClasses.get())
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Die R8-Prüfung findet Klassen der Server-Pipeline nicht im Release (R8 hat sie entfernt):")
                    missing.forEach { appendLine("  - $it") }
                    append("Siehe docs/adr/0017-einrichtung-und-betrieb.md, Abschnitt Release-Einstieg.")
                },
            )
        }
    }
}
