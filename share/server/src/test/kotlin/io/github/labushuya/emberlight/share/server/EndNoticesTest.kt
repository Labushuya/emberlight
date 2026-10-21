package io.github.labushuya.emberlight.share.server

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Benachrichtigungen zum Ende (Paket 3.5a-3, ADR 0017): Zeitpunkt der Vorwarnung, Ende der Aufbewahrung und das Merken
 * des Gesendeten. Die Zeiten sind Zahlen, die Uhr ist ein Argument. Es gibt keine echten Wartezeiten.
 */
class EndNoticesTest {
    private val end = 1_800_000_000_000L
    private val lead = EndNotices.WARNING_LEAD_MILLIS

    @Test
    fun theWarningIsDueFromTenMinutesBeforeTheEndUntilTheEnd() {
        assertFalse(EndNotices.warningDue(end - lead - 1, end, alreadyWarned = false))
        assertTrue(EndNotices.warningDue(end - lead, end, alreadyWarned = false))
        assertTrue(EndNotices.warningDue(end - 1, end, alreadyWarned = false))
        assertFalse(EndNotices.warningDue(end, end, alreadyWarned = false))
    }

    @Test
    fun theWarningIsNotSentTwice() {
        assertFalse(EndNotices.warningDue(end - 1_000, end, alreadyWarned = true))
    }

    @Test
    fun noWarningWithoutAFixedEnd() {
        assertFalse(EndNotices.warningDue(end - lead, null, alreadyWarned = false))
    }

    @Test
    fun keptUntilAddsTheRetentionDaysAfterTheEnd() {
        assertEquals(end + 7 * 86_400_000L, EndNotices.keptUntilMillis(end, retentionDays = 7))
        assertEquals(end, EndNotices.keptUntilMillis(end, retentionDays = 0))
        assertEquals(end, EndNotices.keptUntilMillis(end, retentionDays = -3))
    }

    @Test
    fun theKeptUntilDateFollowsTheLocale() {
        val german = EndNotices.keptUntilDate(1_800_000_000_000L, Locale.GERMANY)
        val english = EndNotices.keptUntilDate(1_800_000_000_000L, Locale.US)
        // Mitte Januar 2027, die Zeitzone verschiebt das Datum nicht über den Jahreswechsel.
        assertTrue(german, german.contains("2027") && german.contains("."))
        assertTrue(english, english.contains("2027") && english.contains(","))
    }

    @Test
    fun theMemoryStoreRemembersWhatWasSentAndDropsTheWarningAtTheEnd() {
        val store = MemoryEndNoticeStore()
        assertFalse(store.warned("a"))
        store.markWarned("a")
        assertTrue(store.warned("a"))
        assertFalse(store.warned("b"))

        store.markEnded("a")
        assertTrue(store.ended("a"))
        assertFalse(store.warned("a"))
    }
}

/** Das Merken überlebt einen Neustart: eine zweite Instanz auf denselben Einstellungen sieht das Gesendete. */
@RunWith(RobolectricTestRunner::class)
class PrefsEndNoticeStoreTest {
    private val prefsName = "emberlight.end-notices-test"

    private fun store() =
        PrefsEndNoticeStore(
            ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE),
        )

    @Test
    fun aWarningSentBeforeARestartIsStillKnownAfterIt() {
        store().markWarned("space-1")

        assertTrue(store().warned("space-1"))
        assertFalse(store().warned("space-2"))
    }

    @Test
    fun theEndNoticeIsRememberedAndTheWarningFlagIsCleared() {
        store().markWarned("space-1")
        store().markEnded("space-1")

        assertTrue(store().ended("space-1"))
        assertFalse(store().warned("space-1"))
    }

    @Test
    fun anExtensionClearsTheWarningSoTheNewEndGetsItsOwnOne() {
        store().markWarned("space-1")

        store().clearWarned("space-1")

        assertFalse(store().warned("space-1"))
        // Die neue Vorwarnung ist fällig, sobald die neue Endzeit in der Vorlaufzeit liegt (Paket 3.6a).
        val newEnd = 10_000_000L
        assertTrue(EndNotices.warningDue(newEnd - EndNotices.WARNING_LEAD_MILLIS, newEnd, store().warned("space-1")))
    }
}
