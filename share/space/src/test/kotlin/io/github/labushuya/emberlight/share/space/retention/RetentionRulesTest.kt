package io.github.labushuya.emberlight.share.space.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fristberechnung, Änderung und Erinnerungsregel (Paket 3.6b, ADR 0021). Feste Zeitwerte, keine echte Uhr. */
class RetentionRulesTest {
    private val ended = 1_000_000_000_000L
    private val day = RetentionRules.DAY_MILLIS
    private val hour = RetentionRules.HOUR_MILLIS
    private val minute = RetentionRules.MINUTE_MILLIS

    @Test
    fun theDeadlineWithoutChangeIsTheEndPlusTheRetention() {
        assertEquals(ended + 7 * day, RetentionRules.defaultUntil(ended, 7))
        assertEquals(ended + day, RetentionRules.effectiveUntil(ended, 1, changedUntil = null))
    }

    @Test
    fun aChangedDeadlineReplacesTheDefault() {
        assertEquals(ended + 3 * day, RetentionRules.effectiveUntil(ended, 7, changedUntil = ended + 3 * day))
    }

    @Test
    fun aChangedDeadlineCountsFromNow() {
        val now = ended + 2 * day
        assertEquals(now + 3 * day, RetentionRules.changedUntil(now, 3))
        assertEquals(now, RetentionRules.changedUntil(now, 0))
    }

    @Test
    fun extendingByOneDayStartsAtTheLaterOfDeadlineAndNow() {
        val now = ended + 2 * day
        assertEquals(now + day, RetentionRules.extendedByOneDay(until = ended + day, now = now))
        assertEquals(ended + 8 * day, RetentionRules.extendedByOneDay(until = ended + 7 * day, now = now))
    }

    @Test
    fun onlyZeroOrOneToNinetyDaysAreValid() {
        assertTrue(RetentionRules.isValidDays(0))
        assertTrue(RetentionRules.isValidDays(1))
        assertTrue(RetentionRules.isValidDays(90))
        assertFalse(RetentionRules.isValidDays(-1))
        assertFalse(RetentionRules.isValidDays(91))
    }

    @Test
    fun twoDaysOrMoreRemindTwentyFourHoursBeforeTheDeadline() {
        val until = ended + 7 * day
        assertEquals(until - day, RetentionRules.reminderAfterEndAt(ended, until))
        assertEquals(ended + day, RetentionRules.reminderAfterEndAt(ended, ended + 2 * day))
    }

    @Test
    fun aOneDayDeadlineRemindsAfterHalfTheDeadline() {
        assertEquals(ended + 12 * hour, RetentionRules.reminderAfterEndAt(ended, ended + day))
    }

    @Test
    fun aShortDeadlineRemindsAtHalfTheDeadlineWhenThatIsEarlier() {
        // Frist von 3 Stunden: die Hälfte liegt bei 90 min, eine Stunde vor dem Ende bei 2 h. Es gilt die frühere.
        assertEquals(ended + 90 * minute, RetentionRules.reminderAfterEndAt(ended, ended + 3 * hour))
    }

    @Test
    fun aVeryShortDeadlineNeverRemindsBeforeTheEndOfTheSpace() {
        // Frist von 30 Minuten: eine Stunde vor ihrem Ende läge vor dem Ende des Space, also gilt das Ende.
        assertEquals(ended, RetentionRules.reminderAfterEndAt(ended, ended + 30 * minute))
        // Frist von 0: ebenfalls das Ende selbst.
        assertEquals(ended, RetentionRules.reminderAfterEndAt(ended, ended))
    }

    @Test
    fun soforReminderComesWithTheTenMinuteWarningBeforeTheEnd() {
        val expiresAt = ended + 2 * hour
        assertEquals(expiresAt - 10 * minute, RetentionRules.soforReminderAt(expiresAt, endedAt = null))
    }

    @Test
    fun soforReminderFallsBackToTheEndIfTheSpaceEndedEarlier() {
        val expiresAt = ended + 2 * hour
        assertEquals(ended, RetentionRules.soforReminderAt(expiresAt, endedAt = ended))
    }

    @Test
    fun soforReminderNeedsAFixedEnd() {
        assertNull(RetentionRules.soforReminderAt(expiresAt = null, endedAt = null))
    }

    @Test
    fun aReminderIsDueOnceAtItsPlannedTime() {
        val planned = ended + day
        assertFalse(RetentionRules.isDue(planned, sentFor = null, now = planned - 1))
        assertTrue(RetentionRules.isDue(planned, sentFor = null, now = planned))
        assertFalse(RetentionRules.isDue(planned, sentFor = planned, now = planned + hour))
    }
}
