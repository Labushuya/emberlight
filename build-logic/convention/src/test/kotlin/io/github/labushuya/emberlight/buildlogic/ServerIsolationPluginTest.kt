package io.github.labushuya.emberlight.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Negativtests der Build-Sperre per Gradle TestKit (ADR 0012). Jedes Fixture ist ein kleines Java-Projekt mit den
 * Pfaden `:share:server`, `:core:data`, `:core:media` und `:feature:space`. Android wird nicht gebraucht: Das Plugin
 * prüft den Graphen unabhängig von der Plattform.
 */
class ServerIsolationPluginTest {
    private val fixtureRoot = File("build/tmp/isolation-test").absoluteFile

    @AfterTest
    fun cleanUp() {
        fixtureRoot.deleteRecursively()
    }

    @Test
    fun cleanGraphAndSourcesPass() {
        val dir = fixture("clean")
        dir.writeServerBuild(dependencies = "implementation(project(\":core:data\"))")
        dir.writeSource("share/server/src/main/java/Ok.java", "class Ok {}")

        val result = runner(dir, "checkServerIsolation", "checkServerSources").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":share:server:checkServerIsolation")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":share:server:checkServerSources")?.outcome)
    }

    @Test
    fun directDependencyOnMediaBreaksBuild() {
        val dir = fixture("direct")
        dir.writeServerBuild(dependencies = "implementation(project(\":core:media\"))")

        val result = runner(dir, "checkServerIsolation").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:server:checkServerIsolation")?.outcome)
        assertTrue(result.output.contains(":core:media"), "Meldung nennt :core:media nicht.")
    }

    @Test
    fun transitiveDependencyOnMediaBreaksBuild() {
        val dir = fixture("transitive")
        dir.writeServerBuild(dependencies = "implementation(project(\":core:data\"))")
        dir.resolve("core/data/build.gradle.kts").writeText(
            "plugins { `java-library` }\ndependencies { implementation(project(\":core:media\")) }\n",
        )

        val result = runner(dir, "checkServerIsolation").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:server:checkServerIsolation")?.outcome)
        assertTrue(result.output.contains(":core:media"), "Transitive Abhängigkeit wird nicht gemeldet.")
    }

    @Test
    fun dependencyOnFeatureModuleBreaksBuild() {
        val dir = fixture("feature")
        dir.writeServerBuild(dependencies = "implementation(project(\":feature:space\"))")

        val result = runner(dir, "checkServerIsolation").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:server:checkServerIsolation")?.outcome)
        assertTrue(result.output.contains(":feature:space"))
    }

    @Test
    fun forbiddenSourceTokenBreaksBuildWithFileAndLine() {
        val dir = fixture("source")
        dir.writeServerBuild(dependencies = "")
        dir.writeSource(
            "share/server/src/main/java/Leak.java",
            "class Leak {\n    Object x = android.provider.MediaStore.Images.Media.class;\n}\n",
        )

        val result = runner(dir, "checkServerSources").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":share:server:checkServerSources")?.outcome)
        assertTrue(
            result.output.contains("src/main/java/Leak.java:2: MediaStore"),
            "Meldung nennt Datei und Zeile nicht:\n${result.output}",
        )
    }

    private fun fixture(name: String): File {
        val dir = fixtureRoot.resolve(name)
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "fixture"
            include(":core:data", ":core:media", ":feature:space", ":share:server")
            """.trimIndent(),
        )
        dir.resolve("core/media/build.gradle.kts").writeLibrary()
        dir.resolve("core/data/build.gradle.kts").writeLibrary()
        dir.resolve("feature/space/build.gradle.kts").writeLibrary()
        return dir
    }

    private fun File.writeLibrary() {
        parentFile.mkdirs()
        writeText("plugins { `java-library` }\n")
    }

    private fun File.writeServerBuild(dependencies: String) {
        resolve("share/server").mkdirs()
        resolve("share/server/build.gradle.kts").writeText(
            """
            plugins {
                id("emberlight.server.isolation")
                `java-library`
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
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun runner(
        dir: File,
        vararg tasks: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(tasks.map { ":share:server:$it" } + "--stacktrace")
}
