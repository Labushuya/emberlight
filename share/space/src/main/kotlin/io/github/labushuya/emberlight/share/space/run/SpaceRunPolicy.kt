package io.github.labushuya.emberlight.share.space.run

import android.os.SystemClock
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import io.github.labushuya.emberlight.share.space.management.SpaceStatus

/** Uhren, die der Betrieb braucht. Die Implementierung auf dem Gerät liest `System` und `SystemClock`, Tests setzen Werte. */
interface RunClock {
    /** Wand-Uhr in UTC-Millisekunden. Kann vom Nutzer verstellt werden. */
    fun wallMillis(): Long

    /** `SystemClock.elapsedRealtime()`: seit dem Start des Geräts, auch im Tiefschlaf weitergezählt, nicht verstellbar. */
    fun elapsedMillis(): Long

    /** `SystemClock.uptimeMillis()`: seit dem Start des Geräts ohne Tiefschlaf. */
    fun uptimeMillis(): Long
}

/** Gründe, warum ein Space nicht starten kann. Die Oberfläche übersetzt sie in Texte (Nutzertexte in `strings.xml`). */
enum class StartProblem {
    /** Ein anderer Space ist schon aktiv. Nur einer gleichzeitig (ADR 0016, Paket 3.3). */
    ANOTHER_ACTIVE,

    /** Das Speicherlimit liegt über dem, was der freie Speicher des Geräts abzüglich 2 GiB erlaubt. */
    STORAGE_ABOVE_FREE,

    /** Das feste Ende liegt nicht in der Zukunft. */
    END_IN_PAST,

    /** Der Space ist kein Entwurf (schon gestartet oder beendet). */
    NOT_A_DRAFT,

    /** Der Space existiert nicht. */
    NOT_FOUND,
}

/** Gründe, warum ein laufender Space nicht verlängert werden kann (Paket 3.6a). */
enum class ExtendProblem {
    /** Der Space ist nicht aktiv: entworfen, beendet oder schon im Nachlauf. */
    NOT_ACTIVE,

    /** Die neue Endzeit liegt nicht in der Zukunft. */
    NOT_IN_FUTURE,

    /** Die neue Endzeit liegt nicht nach dem bisherigen Ende. Verlängern kürzt nicht. */
    NOT_LATER,

    /** Die Gesamtlaufzeit ab dem Start würde 30 Tage überschreiten. */
    TOO_LONG,
}

/** Regeln für Laufzeit, Ablauf und Pausen. Ohne Android, ohne Speicher. */
object SpaceRunPolicy {
    /** Abstand der Herzschläge im Dienst. Ein Herzschlag prüft Ablauf und Pause (ADR 0017). */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /**
     * Lücke zwischen zwei Herzschlägen, ab der der Dienst eine Pause meldet. Die Toleranz von 2 Minuten deckt das
     * verzögerte Aufwachen im Doze-Modus ab. Vier verpasste Herzschläge bei 30 Sekunden gelten als Pause (ADR 0017).
     */
    const val PAUSE_THRESHOLD_MS = 120_000L

    /** Das geplante Ende eines Space, berechnet beim Start. Eine Dauer wird ab dem Start gerechnet, ein festes Ende ist ein Zeitpunkt. */
    fun expiresAt(
        lifetime: Lifetime,
        startedAtMillis: Long,
    ): Long =
        when (lifetime) {
            is Lifetime.Preset -> startedAtMillis + lifetime.preset.seconds * MILLIS_PER_SECOND
            is Lifetime.Until -> lifetime.epochMillis
        }

    /**
     * Ob der Space abgelaufen ist. Die Wand-Uhr und die Laufzeit seit dem Start (`elapsedRealtime`) gelten beide. Das
     * frühere von beiden Ergebnissen gilt (ADR 0017). Eine vorgestellte Uhr beendet den Space also nicht später, eine
     * zurückgestellte nicht früher als die gemessene Laufzeit.
     */
    fun isClosed(
        expiresAtMillis: Long,
        startedAtMillis: Long,
        startedElapsedMillis: Long,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        if (nowWallMillis >= expiresAtMillis) return true
        val plannedMillis = expiresAtMillis - startedAtMillis
        val measuredMillis = nowElapsedMillis - startedElapsedMillis
        return measuredMillis >= plannedMillis
    }

    /**
     * Vorbedingungen für den Start. [freeBytes] ist der freie Speicher des Geräts. Liefert `null`, wenn der Start möglich
     * ist. Die Prüfung von WLAN und Einrichtung liegt außerhalb (Oberfläche und Dienst).
     */
    fun startProblem(
        status: SpaceStatus?,
        storageLimitBytes: Long,
        freeBytes: Long,
        lifetime: Lifetime?,
        nowWallMillis: Long,
        anotherActive: Boolean,
    ): StartProblem? {
        if (status == null) return StartProblem.NOT_FOUND
        if (status != SpaceStatus.DRAFT) return StartProblem.NOT_A_DRAFT
        if (anotherActive) return StartProblem.ANOTHER_ACTIVE
        if (storageLimitBytes > freeBytes - SpaceRules.DEVICE_RESERVE_BYTES) return StartProblem.STORAGE_ABOVE_FREE
        if (lifetime is Lifetime.Until && lifetime.epochMillis <= nowWallMillis) return StartProblem.END_IN_PAST
        return null
    }

    /** Höchstens so lange darf ein Space ab seinem Start laufen, auch nach dem Verlängern (Paket 3.6a). */
    const val MAX_TOTAL_MILLIS = 30L * 86_400_000L

    /**
     * Prüft eine Verlängerung auf eine neue Endzeit. Erlaubt ist nur ein aktiver Space, dessen neue Endzeit in der Zukunft
     * und nach dem bisherigen Ende liegt. Die Gesamtlaufzeit ab [startedAtMillis] bleibt bei höchstens [MAX_TOTAL_MILLIS].
     * Liefert `null`, wenn die Verlängerung gilt.
     */
    fun extendProblem(
        status: SpaceStatus?,
        startedAtMillis: Long?,
        currentEndsAtMillis: Long?,
        newEndsAtMillis: Long,
        nowWallMillis: Long,
    ): ExtendProblem? {
        if (status != SpaceStatus.ACTIVE || startedAtMillis == null || currentEndsAtMillis == null) {
            return ExtendProblem.NOT_ACTIVE
        }
        if (newEndsAtMillis <= nowWallMillis) return ExtendProblem.NOT_IN_FUTURE
        if (newEndsAtMillis <= currentEndsAtMillis) return ExtendProblem.NOT_LATER
        if (newEndsAtMillis - startedAtMillis > MAX_TOTAL_MILLIS) return ExtendProblem.TOO_LONG
        return null
    }

    private const val MILLIS_PER_SECOND = 1_000L
}

/** Eine erkannte Pause des Dienstes (ADR 0017). */
data class PauseEvent(
    /** Wand-Uhr des letzten Herzschlags vor der Lücke. */
    val startedAtMillis: Long,
    /** Lücke laut `elapsedRealtime`. */
    val durationMillis: Long,
    /** Anteil der Lücke, den das Gerät im Tiefschlaf verbracht hat (Differenz zu `uptimeMillis`). Nur Zusatzinfo. */
    val deepSleepMillis: Long,
    /** `true`, sobald der Gastgeber den Hinweis gesehen hat. */
    val acknowledged: Boolean = false,
)

/** Uhren des Systems: Wand-Uhr, `elapsedRealtime` und `uptimeMillis` (ADR 0017). */
object SystemRunClock : RunClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}

/**
 * Erkennt Pausen aus den Herzschlägen des Dienstes (ADR 0017). Jeder Herzschlag übergibt die drei Uhren. Liegt der
 * Abstand zum letzten Herzschlag über [thresholdMillis], liefert die Methode eine Pause zurück.
 */
class PauseDetector(
    private val thresholdMillis: Long = SpaceRunPolicy.PAUSE_THRESHOLD_MS,
) {
    private var last: Beat? = null

    /** Setzt den Bezugspunkt zurück, etwa beim Start oder nach einer Pause, die bewusst übergangen wird. */
    fun reset() {
        last = null
    }

    fun beat(
        wallMillis: Long,
        elapsedMillis: Long,
        uptimeMillis: Long,
    ): PauseEvent? {
        val previous = last
        last = Beat(wallMillis, elapsedMillis, uptimeMillis)
        if (previous == null) return null
        val gap = elapsedMillis - previous.elapsed
        if (gap <= thresholdMillis) return null
        val uptimeGap = uptimeMillis - previous.uptime
        return PauseEvent(
            startedAtMillis = previous.wall,
            durationMillis = gap,
            deepSleepMillis = (gap - uptimeGap).coerceAtLeast(0L),
        )
    }

    private data class Beat(
        val wall: Long,
        val elapsed: Long,
        val uptime: Long,
    )
}
