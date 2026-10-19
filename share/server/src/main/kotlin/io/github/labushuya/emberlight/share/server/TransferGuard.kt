package io.github.labushuya.emberlight.share.server

import java.util.ArrayDeque
import kotlin.math.min

/** Monotone Uhr in Nanosekunden. Im Test injizierbar, damit keine echten Minuten gewartet werden. */
fun interface MonotonicClock {
    fun nanos(): Long

    companion object {
        val System: MonotonicClock = MonotonicClock { java.lang.System.nanoTime() }
    }
}

/** Grenzen für das Lesen eines Upload-Körpers (ADR 0013, Startwerte, bestätigt am 18.10.2026). */
data class TransferPolicy(
    /** Kein Byte für so lange, dann Abbruch. */
    val idleNanos: Long = seconds(30),
    /** Anlaufzeit, bevor die Mindestrate gilt. */
    val rateWarmupNanos: Long = seconds(30),
    /** Gleitendes Fenster für die Mindestrate. */
    val rateWindowNanos: Long = seconds(60),
    /** Mindestrate in Bytes pro Sekunde (16 KiB/s). */
    val minBytesPerSecond: Long = 16L * 1024,
    /** Gesamtdauer eines Uploads. */
    val maxDurationNanos: Long = minutes(60),
) {
    init {
        require(idleNanos > 0 && rateWindowNanos >= rateWarmupNanos && maxDurationNanos > 0)
        require(minBytesPerSecond >= 0)
    }

    companion object {
        fun seconds(value: Long): Long = value * NANOS_PER_SECOND

        fun minutes(value: Long): Long = value * 60 * NANOS_PER_SECOND

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** Grund für einen Abbruch. Alle drei führen zu `408`, der Grund steht im Feld `reason` (ADR 0014). */
enum class TransferViolation(
    val wireName: String,
) {
    IDLE("idle"),
    TOO_SLOW("too_slow"),
    TOO_LONG("too_long"),
}

/** Wirft das Lesen ab, wenn die Übertragung gegen [TransferPolicy] verstößt. */
class TransferAbortedException(
    val violation: TransferViolation,
) : RuntimeException("Übertragung abgebrochen: ${violation.wireName}", null, false, false)

/**
 * Reine Regelprüfung der Übertragung. Erhält die Zeitpunkte von außen, damit sie ohne Wartezeiten getestet werden kann.
 *
 * - Leerlauf: seit dem letzten Byte (oder Beginn) mindestens [TransferPolicy.idleNanos].
 * - Mindestrate: nach der Anlaufzeit muss das gleitende Fenster mindestens die Mindestrate erreichen.
 * - Gesamtdauer: seit Beginn höchstens [TransferPolicy.maxDurationNanos].
 */
class TransferGuard(
    private val policy: TransferPolicy,
    startNanos: Long,
) {
    private val startedAt = startNanos
    private var lastDataAt = startNanos
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Meldet [count] empfangene Bytes zum Zeitpunkt [now]. */
    fun onBytes(
        count: Int,
        now: Long,
    ) {
        if (count <= 0) return
        lastDataAt = now
        samples.addLast(now to count.toLong())
        prune(now)
    }

    /** Liefert den Verstoß zum Zeitpunkt [now] oder `null`. */
    fun check(now: Long): TransferViolation? {
        val elapsed = now - startedAt
        if (elapsed > policy.maxDurationNanos) return TransferViolation.TOO_LONG
        if (now - lastDataAt >= policy.idleNanos) return TransferViolation.IDLE
        if (elapsed >= policy.rateWarmupNanos) {
            prune(now)
            val window = min(policy.rateWindowNanos, elapsed)
            val received = samples.filter { it.first >= now - window }.sumOf { it.second }
            val required = policy.minBytesPerSecond * window / NANOS_PER_SECOND
            if (received < required) return TransferViolation.TOO_SLOW
        }
        return null
    }

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && samples.first().first < now - policy.rateWindowNanos) {
            samples.removeFirst()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
