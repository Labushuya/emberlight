package io.github.labushuya.emberlight.feature.space.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ziel „Ansehen“ der Erinnerung öffnet die Auswahl zur Übernahme (Paket 3.7, ADR 0022). */
class RetentionExportRouteTest {
    @Test
    fun theExportTargetOpensTheSelectionOfTheSpace() {
        val target = RetentionRoutes.parse(RetentionRoutes.export(ID))

        assertEquals(RetentionRoutes.Target(ID, confirmPurge = false, openExport = true), target)
        assertTrue(target!!.openExport)
    }

    @Test
    fun theOpenAndPurgeTargetsAreUnchanged() {
        assertFalse(RetentionRoutes.parse(RetentionRoutes.open(ID))!!.openExport)
        assertFalse(RetentionRoutes.parse(RetentionRoutes.purge(ID))!!.openExport)
    }

    @Test
    fun aTargetWithoutAnIdIsIgnored() {
        assertNull(RetentionRoutes.parse("retention_export/"))
        assertNull(RetentionRoutes.parse("retention_export"))
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
