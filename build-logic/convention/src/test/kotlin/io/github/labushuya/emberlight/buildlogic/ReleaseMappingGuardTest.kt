package io.github.labushuya.emberlight.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping-Prüfung des Release (Paket 3.3, Nachtrag ADR 0018). Klassen zählen nur als Klassenzeile, nicht als Member.
 * Methoden zählen als Member ihrer Klasse oder als Inline-Frame mit Besitzer. Zusammengelegte Klassen bleiben so erkannt.
 */
class ReleaseMappingGuardTest {
    private val pipeline = "io.example.Pipeline"
    private val server = "io.example.Server"

    @Test
    fun aRetainedClassWithItsOriginalNameIsFound() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                void install() -> c
            io.example.Engine -> d.e:
            """.trimIndent()
        assertTrue(ReleaseMappingGuard.missing(mapping, listOf(pipeline, "io.example.Engine")).isEmpty())
    }

    @Test
    fun aRemovedClassIsReported() {
        val mapping = "io.example.Pipeline -> a.b:\n"
        assertEquals(listOf("io.example.Engine"), ReleaseMappingGuard.missing(mapping, listOf("io.example.Engine")))
    }

    @Test
    fun aClassNamedAsAMemberDoesNotCount() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                io.example.Engine -> x
            """.trimIndent()
        assertEquals(listOf("io.example.Engine"), ReleaseMappingGuard.missing(mapping, listOf("io.example.Engine")))
    }

    @Test
    fun aRetainedMethodOfItsOwnClassIsFound() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                void install() -> c
            """.trimIndent()
        assertTrue(ReleaseMappingGuard.missing(mapping, listOf("$pipeline#install")).isEmpty())
    }

    @Test
    fun aMethodThatIsGoneIsReported() {
        val mapping =
            """
            io.example.Pipeline -> a.b:
                void other() -> c
            """.trimIndent()
        assertEquals(listOf("$pipeline#install"), ReleaseMappingGuard.missing(mapping, listOf("$pipeline#install")))
    }

    @Test
    fun aMethodOfAnotherClassWithTheSameNameDoesNotCount() {
        val mapping =
            """
            io.example.Other -> x.y:
                void install() -> c
            io.example.Pipeline -> a.b:
            """.trimIndent()
        assertEquals(listOf("$pipeline#install"), ReleaseMappingGuard.missing(mapping, listOf("$pipeline#install")))
    }

    @Test
    fun aMergedClassWhoseMethodsSurviveIsFoundByItsInlineFrames() {
        // Die Klasse fehlt als Klassenzeile, ihr Code steht als Inline-Frame im Mapping (Zusammenlegung).
        val mapping =
            """
            io.example.Holder -> h:
                1:3:void io.example.Server.start(io.example.Config,kotlin.coroutines.Continuation):52:52 -> c
            """.trimIndent()
        assertTrue(ReleaseMappingGuard.missing(mapping, listOf("$server#start")).isEmpty())
        assertEquals(listOf(server), ReleaseMappingGuard.missing(mapping, listOf(server)))
    }

    @Test
    fun aRemovedMergedClassWithoutAnyTraceIsReported() {
        val mapping = "io.example.Holder -> h:\n    void other() -> c\n"
        assertEquals(listOf("$server#start"), ReleaseMappingGuard.missing(mapping, listOf("$server#start")))
    }

    @Test
    fun realMappingLinesOfTheReleaseBuildSatisfyTheRequirements() {
        // Auszug aus dem Mapping des Release-Builds (ohne Keep-Regel für KtorLocalServer). Die Klasse fehlt als
        // Klassenzeile, ihre Methoden stehen als Inline-Frames, die Pipeline als Member von ServerPipelineKt.
        val mapping =
            """
            io.github.labushuya.emberlight.share.server.KtorLocalServer${'$'}${'$'}ExternalSyntheticLambda0 -> in2:
                1:14:kotlin.Unit io.github.labushuya.emberlight.share.server.KtorLocalServer.start${'$'}lambda${'$'}0${'$'}3(io.github.labushuya.emberlight.share.server.ServerConfig,io.ktor.server.cio.CIOApplicationEngine${'$'}Configuration):73 -> f
                31:116:java.lang.Object io.github.labushuya.emberlight.share.server.KtorLocalServer.start(io.github.labushuya.emberlight.share.server.ServerConfig,kotlin.coroutines.Continuation):52:52 -> c
            io.github.labushuya.emberlight.share.server.ServerPipelineKt -> kx4:
                766:768:void io.github.labushuya.emberlight.share.server.ServerPipelineKt.installEmberlightModule(io.ktor.server.application.Application,io.github.labushuya.emberlight.share.server.ServerEnvironment,kotlin.jvm.functions.Function1):0:0 -> q
            io.github.labushuya.emberlight.share.server.SpaceRoutesKt -> x75:
                62:68:kotlin.jvm.functions.Function1 io.github.labushuya.emberlight.share.server.SpaceRoutesKt.spaceRoutes(io.github.labushuya.emberlight.share.server.SpaceAccess,io.github.labushuya.emberlight.share.server.ServerGates,io.github.labushuya.emberlight.share.server.UploadTuning):77:77 -> C
            io.ktor.server.cio.CIOApplicationEngine -> d00:
            """.trimIndent()
        assertTrue(ReleaseMappingGuard.missing(mapping, ReleaseMappingGuard.SERVER_PIPELINE_REQUIREMENTS).isEmpty())
    }

    @Test
    fun aReleaseWithoutThePipelineIsReportedInFull() {
        val mapping = "io.example.Unrelated -> u:\n"
        assertEquals(
            ReleaseMappingGuard.SERVER_PIPELINE_REQUIREMENTS,
            ReleaseMappingGuard.missing(mapping, ReleaseMappingGuard.SERVER_PIPELINE_REQUIREMENTS),
        )
    }

    @Test
    fun theServerPipelineRequirementsNameThePipelineRoutesServerAndEngine() {
        val names = ReleaseMappingGuard.SERVER_PIPELINE_REQUIREMENTS
        assertTrue(
            names.contains("io.github.labushuya.emberlight.share.server.ServerPipelineKt#installEmberlightModule"),
        )
        assertTrue(names.contains("io.github.labushuya.emberlight.share.server.SpaceRoutesKt#spaceRoutes"))
        assertTrue(names.contains("io.github.labushuya.emberlight.share.server.KtorLocalServer#start"))
        assertTrue(names.contains("io.ktor.server.cio.CIOApplicationEngine"))
    }
}
