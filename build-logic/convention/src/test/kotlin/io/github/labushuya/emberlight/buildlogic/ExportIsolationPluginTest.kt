package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Erlaubnisliste von `share:export` (ADR 0022). Nur `share:space` ist erlaubt, jedes andere Projekt des Builds ist verboten,
 * auch ein neues. Die Prüfung am Graphen selbst läuft als `checkExportIsolation` in `check`.
 */
class ExportIsolationPluginTest {
    private val build =
        listOf(
            ":app",
            ":core:media",
            ":feature:gallery",
            ":feature:space",
            ":share:export",
            ":share:server",
            ":share:space",
        )

    @Test
    fun everythingButTheSpaceDomainIsForbidden() {
        assertEquals(
            listOf(":app", ":core:media", ":feature:gallery", ":feature:space", ":share:server"),
            ExportIsolationConventionPlugin.forbiddenOutside(build, guardedPath = ":share:export"),
        )
    }

    @Test
    fun aModuleAddedLaterIsForbiddenByDefault() {
        val later = build + ":share:network"

        assertTrue(
            ExportIsolationConventionPlugin
                .forbiddenOutside(
                    later,
                    guardedPath = ":share:export",
                ).contains(":share:network"),
        )
    }
}
