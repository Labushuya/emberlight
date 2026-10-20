package io.github.labushuya.emberlight.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapping-Prüfung des Release (Paket 3.3): Klassen zählen nur als Klassenzeile, nicht als Member. */
class ReleaseMappingGuardTest {
    private val required = listOf("io.example.Pipeline", "io.example.Engine")

    @Test
    fun aRetainedClassWithItsOriginalNameIsFound() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                void install() -> c
            io.example.Engine -> d.e:
            """.trimIndent()
        assertTrue(ReleaseMappingGuard.missing(mapping, required).isEmpty())
    }

    @Test
    fun aRemovedClassIsReported() {
        val mapping = "io.example.Pipeline -> a.b:\n"
        assertEquals(listOf("io.example.Engine"), ReleaseMappingGuard.missing(mapping, required))
    }

    @Test
    fun aMemberNamedLikeTheClassDoesNotCount() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                io.example.Engine -> x
            """.trimIndent()
        assertEquals(listOf("io.example.Engine"), ReleaseMappingGuard.missing(mapping, required))
    }

    @Test
    fun theServerPipelineClassesNameThePipelineTheServerAndTheEngine() {
        val names = ReleaseMappingGuard.SERVER_PIPELINE_CLASSES
        assertTrue(names.contains("io.github.labushuya.emberlight.share.server.ServerPipelineKt"))
        assertTrue(names.contains("io.ktor.server.cio.CIOApplicationEngine"))
    }
}
