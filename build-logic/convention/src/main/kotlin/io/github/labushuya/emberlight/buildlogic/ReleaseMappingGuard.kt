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
 * Prüfung der R8-Mapping-Datei der Release-Variante (Paket 3.3, ADR 0017, Nachtrag ADR 0018).
 *
 * Der Server ist im Release über die Oberfläche erreichbar. R8 darf die Server-Pipeline nicht entfernen. R8 darf sie aber
 * zusammenlegen: Eine Klasse kann in eine andere übergehen oder inline ausgeführt werden. Dann fehlt ihre Klassenzeile im
 * Mapping, der Code ist trotzdem da. Deshalb prüft die Funktion nicht auf Klassennamen allein, sondern auf Methoden des
 * Originalcodes. Eine Methode gilt als erhalten, wenn das Mapping sie
 *
 * - als Member ihrer Klasse führt (Zeile unter der Klassenzeile mit `name(`), oder
 * - als Inline-Frame mit Besitzer nennt (`Besitzer.name(`), etwa nach Inlining oder Zusammenlegung.
 *
 * Eine entfernte Methode taucht in keiner dieser Formen auf. Reine Funktion ohne Gradle-Abhängigkeiten.
 */
object ReleaseMappingGuard {
    /**
     * Anforderungen der Server-Pipeline. Format `Klasse` (Klassenzeile) oder `Klasse#methode` (Methode, auch nach
     * Zusammenlegung). Die Route-Installation, die Join-Routen und der Start des Ktor-Servers müssen im Ergebnis vorhanden sein.
     */
    val SERVER_PIPELINE_REQUIREMENTS: List<String> =
        listOf(
            "io.github.labushuya.emberlight.share.server.ServerPipelineKt#installEmberlightModule",
            "io.github.labushuya.emberlight.share.server.SpaceRoutesKt#spaceRoutes",
            "io.github.labushuya.emberlight.share.server.KtorLocalServer#start",
            "io.ktor.server.cio.CIOApplicationEngine",
        )

    /** Liefert die Anforderungen, die das Mapping nicht belegt. Leere Liste heißt: alles erhalten. */
    fun missing(
        mapping: String,
        required: Collection<String>,
    ): List<String> {
        val index = MappingIndex.of(mapping)
        return required.filter { requirement ->
            val (owner, member) = requirement.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
            if (member == null) {
                owner !in index.classes
            } else {
                member !in index.methodsOf(owner)
            }
        }
    }

    /**
     * Zerlegt das Mapping. Klassenzeilen stehen ohne Einrückung (`Original -> Ziel:`). Eingerückte Zeilen sind Member
     * oder Inline-Frames. Inline-Frames nennen den Besitzer selbst, Member gehören zur letzten Klassenzeile.
     */
    private class MappingIndex(
        val classes: Set<String>,
        private val methods: Map<String, Set<String>>,
    ) {
        fun methodsOf(owner: String): Set<String> = methods[owner].orEmpty()

        companion object {
            private val inlineFrame = Regex("""\s([\w.$]+)\.([\w$<>]+)\(""")
            private val simpleMember = Regex("""\s([\w$<>]+)\(""")

            fun of(mapping: String): MappingIndex {
                val classes = HashSet<String>()
                val methods = HashMap<String, MutableSet<String>>()
                var current: String? = null
                for (line in mapping.lineSequence()) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (!line.startsWith(" ")) {
                        val arrow = line.indexOf(" -> ")
                        if (arrow < 0 || !line.endsWith(":")) continue
                        current = line.substring(0, arrow)
                        classes += current
                        continue
                    }
                    inlineFrame.findAll(line).forEach { m ->
                        methods.getOrPut(m.groupValues[1]) { HashSet() }.add(m.groupValues[2])
                    }
                    val owner = current ?: continue
                    simpleMember.find(line)?.let { m -> methods.getOrPut(owner) { HashSet() }.add(m.groupValues[1]) }
                }
                return MappingIndex(classes, methods)
            }
        }
    }
}

/** Bricht ab, wenn die R8-Mapping-Datei eine erforderliche Klasse oder Methode der Server-Pipeline nicht nennt. */
abstract class CheckReleaseMappingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:Input
    abstract val requiredSymbols: ListProperty<String>

    @TaskAction
    fun check() {
        val text = mapping.get().asFile.readText(Charsets.UTF_8)
        val missing = ReleaseMappingGuard.missing(text, requiredSymbols.get())
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Die R8-Prüfung findet Teile der Server-Pipeline im Release nicht (weder als Klasse noch als Methode):",
                    )
                    missing.forEach { appendLine("  - $it") }
                    append("Siehe docs/adr/0018-einladung-und-beitritt.md, Abschnitt Keep-Regel und R8-Prüfung.")
                },
            )
        }
    }
}
