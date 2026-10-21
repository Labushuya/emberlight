package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architekturprüfung für ADR 0016: Im Produktionscode gibt es genau einen Weg, `spaces.db` zu öffnen, nämlich
 * [SpacesDatabase.shared]. Hilt, der Server und der Vault nutzen dieselbe Instanz. Eine zweite Room-Instanz auf derselben
 * Datei bekommt keine Invalidierung der anderen. Flows zeigen dann veraltete Stände, etwa Teilnehmer oder Uploads.
 *
 * Die Prüfung liest den Quelltext aller Module (ohne Tests und Build-Ordner). Sie ist bewusst grob: Sie findet jeden
 * Aufruf des Builders oder von [SpacesDatabase.open] außerhalb der Companion-Definition.
 */
class SingleDatabaseInstanceTest {
    @Test
    fun productionCodeOpensTheSpacesDatabaseOnlyThroughTheSharedInstance() {
        val offenders =
            mainSourceFiles(repoRoot())
                .filterNot { it.name == DEFINITION_FILE }
                .filter { file -> FORBIDDEN.any { file.readText().contains(it) } }
                .map { it.path }
                .toList()

        assertTrue("Außerhalb von SpacesDatabase.shared wird spaces.db geöffnet: $offenders", offenders.isEmpty())
    }

    @Test
    fun theDefinitionFileItselfStillHasTheSharedEntryPoint() {
        val definition =
            mainSourceFiles(repoRoot()).single { it.name == DEFINITION_FILE }.readText()

        assertTrue(definition.contains("fun shared(context: Context): SpacesDatabase"))
    }

    private fun mainSourceFiles(root: File): Sequence<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.path.contains("${File.separator}src${File.separator}main${File.separator}")
            }

    /** Die Gradle-Tests laufen im Modulordner. Der Wurzelordner ist der mit `settings.gradle.kts`. */
    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private companion object {
        const val DEFINITION_FILE = "SpacesDatabase.kt"
        val FORBIDDEN = listOf("SpacesDatabase.open(", "databaseBuilder(")
    }
}
