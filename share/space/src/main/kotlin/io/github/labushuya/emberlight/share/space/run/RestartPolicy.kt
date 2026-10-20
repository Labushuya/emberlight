package io.github.labushuya.emberlight.share.space.run

import io.github.labushuya.emberlight.share.space.SpaceId

/** Der aktive Space aus der Datenbank, nur soweit die Entscheidung nach einem Neustart ihn braucht. */
data class ActiveSpan(
    val spaceId: SpaceId,
    val startedAtMillis: Long?,
    /** Geplantes Ende in UTC-Millisekunden, `null` ohne Ende. */
    val expiresAtMillis: Long?,
)

/** Grund, warum ein aktiver Space nach einem Neustart nicht wieder startet. */
enum class InterruptReason {
    /** Keine Ausnahme von der Akku-Optimierung. Das System würde den Dienst im Hintergrund wieder beenden. */
    NO_BATTERY_EXEMPTION,
}

/** Was nach einem Neustart des Dienstes (ohne Auftrag) oder beim Wecker geschieht (ADR 0017, Neustart). */
sealed interface RestartDecision {
    /** Kein aktiver Space: der Dienst endet. */
    data object NothingToDo : RestartDecision

    /** Der Space ist abgelaufen. Er endet zum Zeitpunkt [endedAtMillis] (`ENDED`). */
    data class Expired(
        val spaceId: SpaceId,
        val endedAtMillis: Long,
    ) : RestartDecision

    /** Der Space ist aktiv und nicht abgelaufen, die Voraussetzungen sind erfüllt: der Server startet wieder. */
    data class Resume(
        val spaceId: SpaceId,
    ) : RestartDecision

    /** Der Space ist aktiv, aber der Dienst darf nicht laufen. Der Space bleibt `ACTIVE`, der Lauf ist unterbrochen. */
    data class Interrupted(
        val spaceId: SpaceId,
        val reason: InterruptReason,
    ) : RestartDecision
}

/**
 * Entscheidet ohne Android-Typen, was mit dem aktiven Space geschieht. Die Frist gilt wie im Lauf (siehe
 * [SpaceRunPolicy.isClosed]): Die Wand-Uhr zählt immer. Die gemessene Laufzeit zählt nur, solange dasselbe Gerät gelaufen
 * ist, also `elapsedRealtime` seit dem Start nicht zurückgesetzt wurde. Sonst gilt nur die Wand-Uhr.
 */
object RestartPolicy {
    fun decide(
        active: ActiveSpan?,
        beat: RunBeat?,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
        batteryExempt: Boolean,
    ): RestartDecision {
        if (active == null) return RestartDecision.NothingToDo
        val expires = active.expiresAtMillis
        if (expires != null && isExpired(active, expires, beat, nowWallMillis, nowElapsedMillis)) {
            return RestartDecision.Expired(active.spaceId, endedAtMillis = minOf(nowWallMillis, expires))
        }
        if (!batteryExempt) {
            return RestartDecision.Interrupted(active.spaceId, InterruptReason.NO_BATTERY_EXEMPTION)
        }
        return RestartDecision.Resume(active.spaceId)
    }

    private fun isExpired(
        active: ActiveSpan,
        expires: Long,
        beat: RunBeat?,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        if (nowWallMillis >= expires) return true
        val measured =
            beat?.takeIf { it.spaceId == active.spaceId && nowElapsedMillis >= it.startedElapsedMillis }
                ?: return false
        val startedAt = active.startedAtMillis ?: measured.startedAtMillis
        return SpaceRunPolicy.isClosed(
            expiresAtMillis = expires,
            startedAtMillis = startedAt,
            startedElapsedMillis = measured.startedElapsedMillis,
            nowWallMillis = nowWallMillis,
            nowElapsedMillis = nowElapsedMillis,
        )
    }
}

/**
 * Dauer der Unterbrechung seit dem letzten Herzschlag (ADR 0017, Neustart). Ist dasselbe Gerät noch gelaufen, zählt
 * `elapsedRealtime`, sonst die Wand-Uhr. Das Ergebnis ist nie negativ.
 */
fun interruptionMillis(
    beat: RunBeat,
    nowWallMillis: Long,
    nowElapsedMillis: Long,
): Long {
    val gap =
        if (nowElapsedMillis >= beat.lastElapsedMillis) {
            nowElapsedMillis - beat.lastElapsedMillis
        } else {
            nowWallMillis - beat.lastWallMillis
        }
    return gap.coerceAtLeast(0L)
}
