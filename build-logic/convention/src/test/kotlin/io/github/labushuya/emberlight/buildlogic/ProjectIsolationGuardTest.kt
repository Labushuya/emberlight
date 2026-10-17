package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectIsolationGuardTest {
    private val forbidden = listOf(":core:media", ":feature:gallery")

    @Test
    fun directForbiddenProjectIsReported() {
        val resolved = listOf(":share:server", ":core:media")
        assertEquals(listOf(":core:media"), ProjectIsolationGuard.findViolations(resolved, forbidden))
    }

    @Test
    fun transitiveForbiddenProjectIsReported() {
        // Die aufgelöste Kette liefert auch Projekte, die nur über ein Zwischenmodul erreicht werden.
        val resolved = listOf(":share:server", ":core:data", ":feature:gallery")
        assertEquals(listOf(":feature:gallery"), ProjectIsolationGuard.findViolations(resolved, forbidden))
    }

    @Test
    fun cleanGraphReportsNothing() {
        val resolved = listOf(":share:server", ":core:data", ":share:network")
        assertTrue(ProjectIsolationGuard.findViolations(resolved, forbidden).isEmpty())
    }

    @Test
    fun duplicatesAreReportedOnce() {
        val resolved = listOf(":core:media", ":core:media", ":share:server")
        assertEquals(listOf(":core:media"), ProjectIsolationGuard.findViolations(resolved, forbidden))
    }

    @Test
    fun matchRequiresExactProjectPath() {
        // ":core:mediaplayer" ist ein anderes Projekt und darf nicht als Verstoß gelten.
        val resolved = listOf(":core:mediaplayer", ":core:media-extra")
        assertTrue(ProjectIsolationGuard.findViolations(resolved, forbidden).isEmpty())
    }
}
