package io.github.labushuya.emberlight.share.space.retention

/**
 * Regeln der Aufbewahrung nach dem Ende (Paket 3.6b, ADR 0021). Reine Funktionen in UTC-Millisekunden, ohne Android-Typen,
 * damit sie mit einer festen Uhr testbar sind.
 */
object RetentionRules {
    const val MINUTE_MILLIS = 60_000L
    const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    const val DAY_MILLIS = 24L * HOUR_MILLIS

    /** Kürzeste eigene Frist in Tagen (die Voreinstellung „1 Tag“ liegt darüber). */
    const val MIN_CUSTOM_DAYS = 1

    /** Längste eigene Frist in Tagen. */
    const val MAX_DAYS = 90

    /** Vorlauf der Erinnerung bei „Sofort löschen“. Dieselbe Zeit wie bei der Vorwarnung zum Ende (ADR 0017). */
    const val SOFORT_LEAD_MILLIS = 10L * MINUTE_MILLIS

    /** Ende der Aufbewahrung ohne Änderung: Ende plus Frist. Eine Frist von 0 Tagen heißt „sofort“ und fällt mit dem Ende zusammen. */
    fun defaultUntil(
        endedAt: Long,
        retentionDays: Int,
    ): Long = endedAt + retentionDays.coerceAtLeast(0) * DAY_MILLIS

    /** Ende der Aufbewahrung, wie es gilt: die Änderung des Gastgebers, sonst Ende plus Frist. */
    fun effectiveUntil(
        endedAt: Long,
        retentionDays: Int,
        changedUntil: Long?,
    ): Long = changedUntil ?: defaultUntil(endedAt, retentionDays)

    /**
     * Neue Frist, wie der Gastgeber sie während der Aufbewahrung festlegt: gezählt ab jetzt (Auslegung zu Paket 3.6b).
     * Verlängern und Verkürzen bekommen so dasselbe Verhalten, und das Datum in der Liste passt zur gewählten Dauer.
     */
    fun changedUntil(
        now: Long,
        days: Int,
    ): Long = now + days.coerceAtLeast(0) * DAY_MILLIS

    /** Aktion „1 Tag länger aufbewahren“: ab dem späteren von Ende der Frist und jetzt, plus einen Tag. */
    fun extendedByOneDay(
        until: Long,
        now: Long,
    ): Long = maxOf(until, now) + DAY_MILLIS

    /** Ist eine Dauer in Tagen gültig? 0 ist „sofort“, sonst 1 bis 90 Tage. */
    fun isValidDays(days: Int): Boolean = days == 0 || days in MIN_CUSTOM_DAYS..MAX_DAYS

    /**
     * Zeitpunkt der Erinnerung nach dem Ende. Ab zwei Tagen Frist 24 h vor dem Ende der Frist. Bei kürzeren Fristen nach der
     * Hälfte der Frist, spätestens 1 h vor dem Ende der Frist. Nie vor dem Ende des Space.
     */
    fun reminderAfterEndAt(
        endedAt: Long,
        until: Long,
    ): Long {
        val period = until - endedAt
        if (period <= 0L) return endedAt
        if (period >= 2 * DAY_MILLIS) return until - DAY_MILLIS
        return maxOf(endedAt, minOf(endedAt + period / 2, until - HOUR_MILLIS))
    }

    /**
     * Zeitpunkt der Erinnerung bei „Sofort löschen“: zusammen mit der Vorwarnung zehn Minuten vor dem Ende. Endete der Space
     * früher, gilt das Ende. Ohne festes Ende gibt es keinen Zeitpunkt.
     */
    fun soforReminderAt(
        expiresAt: Long?,
        endedAt: Long?,
    ): Long? {
        val beforeEnd = expiresAt?.let { it - SOFORT_LEAD_MILLIS }
        return listOfNotNull(beforeEnd, endedAt).minOrNull()
    }

    /** Ist die Erinnerung zum geplanten Zeitpunkt fällig? Je geplantem Zeitpunkt höchstens einmal. */
    fun isDue(
        plannedAt: Long,
        sentFor: Long?,
        now: Long,
    ): Boolean = now >= plannedAt && sentFor != plannedAt
}
