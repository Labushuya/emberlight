package io.github.labushuya.emberlight.feature.space.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ziele aus den Benachrichtigungen (Paket 3.6b): „Ansehen“ öffnet, „Jetzt löschen“ öffnet mit Bestätigung. */
class RetentionRoutesTest {
    @Test
    fun viewOpensTheDetailWithoutConfirmation() {
        val target = RetentionRoutes.parse(RetentionRoutes.open(ID))

        assertEquals(RetentionRoutes.Target(ID, confirmPurge = false), target)
    }

    @Test
    fun purgeOpensTheDetailWithTheConfirmationOpen() {
        val target = RetentionRoutes.parse(RetentionRoutes.purge(ID))

        assertEquals(RetentionRoutes.Target(ID, confirmPurge = true), target)
    }

    @Test
    fun foreignOrIncompleteRoutesAreIgnored() {
        assertNull(RetentionRoutes.parse("open_space"))
        assertNull(RetentionRoutes.parse("retention_open/"))
        assertNull(RetentionRoutes.parse("retention_delete/$ID"))
        assertNull(RetentionRoutes.parse(""))
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
