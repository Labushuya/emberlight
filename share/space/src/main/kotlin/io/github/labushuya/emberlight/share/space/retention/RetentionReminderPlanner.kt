package io.github.labushuya.emberlight.share.space.retention

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus

/** Art einer fälligen Erinnerung: nach dem Ende (Frist läuft) oder bei „Sofort löschen“ (vor dem Ende). */
enum class RetentionReminderKind {
    AFTER_END,
    SOFORT,
}

/**
 * Eine Erinnerung, die fällig ist oder später fällig wird. [plannedAt] ist der Zeitpunkt, der sie eindeutig macht. Er wird
 * am Space gemerkt, sobald die Erinnerung gesendet ist (ADR 0021).
 */
data class RetentionReminder(
    val spaceId: SpaceId,
    val name: String,
    val kind: RetentionReminderKind,
    val untilMillis: Long?,
    val plannedAt: Long,
)

/** Ergebnis einer Planung: die jetzt fälligen Erinnerungen und der nächste Zeitpunkt, zu dem wieder geprüft werden muss. */
data class RetentionPlan(
    val due: List<RetentionReminder>,
    val nextCheckAt: Long?,
)

/**
 * Plant die Erinnerungen zur Aufbewahrung (Paket 3.6b, ADR 0021). Ein Space mit „Sofort löschen“ bekommt seine Erinnerung vor
 * dem Ende, ein beendeter Space mit Frist nach dem Ende. Gelöschte oder gerade gelöschte Spaces zählen nicht.
 */
object RetentionReminderPlanner {
    fun plan(
        spaces: List<SpaceRecord>,
        now: Long,
    ): RetentionPlan {
        val due = mutableListOf<RetentionReminder>()
        val later = mutableListOf<Long>()
        for (space in spaces) {
            val reminder = reminderOf(space) ?: continue
            if (RetentionRules.isDue(reminder.plannedAt, space.retentionRemindedFor, now)) {
                due += reminder
            } else if (reminder.plannedAt > now) {
                later += reminder.plannedAt
            }
        }
        return RetentionPlan(due = due, nextCheckAt = later.minOrNull())
    }

    /** Die Erinnerung eines Space, oder `null`, wenn er keine braucht (aktiv mit Frist, Entwurf, Löschung läuft). */
    fun reminderOf(space: SpaceRecord): RetentionReminder? {
        if (space.purging) return null
        val days = space.settings.retentionDays
        return when (space.status) {
            SpaceStatus.ENDED -> {
                val endedAt = space.endedAt ?: return null
                val name = space.settings.name
                if (days == 0 && space.retentionUntil == null) {
                    // Sofort-Löschung: die Erinnerung gilt dem Ende. Ein Ende ohne Vorlauf meldet sie sofort.
                    val at = RetentionRules.soforReminderAt(space.expiresAt, endedAt) ?: return null
                    RetentionReminder(space.id, name, RetentionReminderKind.SOFORT, endedAt, at)
                } else {
                    val until = RetentionRules.effectiveUntil(endedAt, days, space.retentionUntil)
                    RetentionReminder(
                        space.id,
                        name,
                        RetentionReminderKind.AFTER_END,
                        until,
                        RetentionRules.reminderAfterEndAt(endedAt, until),
                    )
                }
            }

            SpaceStatus.ACTIVE -> {
                if (days != 0) return null
                val at = RetentionRules.soforReminderAt(space.expiresAt, null) ?: return null
                RetentionReminder(space.id, space.settings.name, RetentionReminderKind.SOFORT, null, at)
            }

            SpaceStatus.DRAFT, SpaceStatus.DELETED -> null
        }
    }
}
