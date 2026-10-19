package io.github.labushuya.emberlight.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Negativtests der Sperren für `share:space` und die Galerie-Richtung (ADR 0014) per Gradle TestKit. Jedes Fixture ist
 * ein kleines Java-Projekt. Die Verzeichnisse liegen unter einem Zeitstempel-Namen im Build-Ordner, damit nichts
 * gelöscht werden muss.
 */
class SpaceAndGalleryIsolationPluginTest {
    private val fixtureRoot = File("build/tmp/isolation-space-${System.currentTimeMillis()}").absoluteFile

    @Test
    fun cleanSpaceModulePasses() {
        val dir = fixture("space-clean")
        dir.writeModule("share/space", "emberlight.space.isolation", "implementation(project(\":core:data\"))")
        dir.writeSource("share/space/src/main/java/Ok.java", "class Ok {}")

        val result = runner(dir, ":share:space", "checkSpaceIsolation", "checkSpaceSources").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":share:space:checkSpaceIsolation")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":share:space:checkSpaceSources")?.outcome)
    }

    @Test
    fun spaceDependingOnServerBreaksBuild() {
        val dir = fixture("space-server")
        dir.writeModule("share/space", "emberlight.space.isolation", "implementation(project(\":share:server\"))")

        val result = runner(dir, ":share:space", "checkSpaceIsolation").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:space:checkSpaceIsolation")?.outcome)
        assertTrue(result.output.contains(":share:server"), "Meldung nennt :share:server nicht.")
    }

    @Test
    fun spaceTransitivelyReachingMediaBreaksBuild() {
        val dir = fixture("space-transitive")
        dir.writeModule("share/space", "emberlight.space.isolation", "implementation(project(\":core:data\"))")
        dir.writeModule("core/data", null, "implementation(project(\":core:media\"))")

        val result = runner(dir, ":share:space", "checkSpaceIsolation").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:space:checkSpaceIsolation")?.outcome)
        assertTrue(result.output.contains(":core:media"))
    }

    @Test
    fun galleryDependingOnSpaceBreaksBuild() {
        val dir = fixture("gallery-space")
        dir.writeModule("core/media", "emberlight.gallery.isolation", "implementation(project(\":share:space\"))")

        val result = runner(dir, ":core:media", "checkGalleryNoSpaceDependency").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":core:media:checkGalleryNoSpaceDependency")?.outcome)
        assertTrue(result.output.contains(":share:space"))
    }

    @Test
    fun galleryTransitivelyReachingServerBreaksBuild() {
        val dir = fixture("gallery-transitive")
        dir.writeModule("core/media", "emberlight.gallery.isolation", "implementation(project(\":core:data\"))")
        dir.writeModule("core/data", null, "implementation(project(\":share:server\"))")

        val result = runner(dir, ":core:media", "checkGalleryNoSpaceDependency").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":core:media:checkGalleryNoSpaceDependency")?.outcome)
        assertTrue(result.output.contains(":share:server"))
    }

    @Test
    fun cleanGalleryModulePasses() {
        val dir = fixture("gallery-clean")
        dir.writeModule("core/media", "emberlight.gallery.isolation", "")

        val result = runner(dir, ":core:media", "checkGalleryNoSpaceDependency").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":core:media:checkGalleryNoSpaceDependency")?.outcome)
    }

    private fun fixture(name: String): File {
        val dir = fixtureRoot.resolve(name)
        Files.createDirectories(dir.toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "fixture"
            include(":app", ":core:data", ":core:media", ":feature:space", ":share:server", ":share:space")
            """.trimIndent(),
        )
        // Jedes eingetragene Projekt braucht ein Verzeichnis mit Build-Skript. Die Tests überschreiben die Skripte, die sie
        // prüfen. Die übrigen bleiben leere Java-Bibliotheken.
        listOf("app", "core/data", "core/media", "feature/space", "share/server", "share/space").forEach {
            dir.writeModule(it, null, "")
        }
        return dir
    }

    private fun File.writeModule(
        path: String,
        pluginId: String?,
        dependencies: String,
    ) {
        val module = resolve(path)
        Files.createDirectories(module.toPath())
        val plugins = listOfNotNull(pluginId?.let { "id(\"$it\")" }, "`java-library`")
        resolve("$path/build.gradle.kts").writeText(
            """
            plugins {
                ${plugins.joinToString("\n    ")}
            }
            dependencies {
                $dependencies
            }
            """.trimIndent(),
        )
    }

    private fun File.writeSource(
        relativePath: String,
        content: String,
    ) {
        val file = resolve(relativePath)
        Files.createDirectories(file.parentFile.toPath())
        file.writeText(content)
    }

    /** Führt [tasks] im Projekt [project] aus, etwa `:share:space` mit `checkSpaceIsolation`. */
    private fun runner(
        dir: File,
        project: String,
        vararg tasks: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(tasks.map { "$project:$it" } + "--stacktrace")
}
