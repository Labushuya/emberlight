package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseManifestGuardTest {
    private val tokens = ReleaseManifestConventionPlugin.FORBIDDEN_TOKENS

    @Test
    fun manifestWithoutDebugEntryPasses() {
        val manifest =
            """
            <manifest package="io.github.labushuya.emberlight">
              <application><activity android:name="io.github.labushuya.emberlight.MainActivity" /></application>
            </manifest>
            """.trimIndent()

        assertTrue(ReleaseManifestGuard.findViolations(manifest, tokens).isEmpty())
    }

    @Test
    fun debugReceiverInManifestIsReported() {
        val manifest =
            """
            <manifest>
              <receiver android:name="io.github.labushuya.emberlight.debugserver.DebugServerReceiver" />
            </manifest>
            """.trimIndent()

        assertEquals(
            listOf("io.github.labushuya.emberlight.debugserver"),
            ReleaseManifestGuard.findViolations(manifest, tokens),
        )
    }

    @Test
    fun debugActionIsReportedOnce() {
        val manifest = "<action android:name=\"x.DEBUG_SERVER_START\" /><action android:name=\"x.DEBUG_SERVER_STOP\" />"

        assertEquals(listOf("DEBUG_SERVER"), ReleaseManifestGuard.findViolations(manifest, tokens))
    }
}
