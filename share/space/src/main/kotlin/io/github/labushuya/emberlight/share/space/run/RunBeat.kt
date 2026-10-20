package io.github.labushuya.emberlight.share.space.run

import android.content.Context
import io.github.labushuya.emberlight.share.space.SpaceId

/**
 * Letzter bekannter Stand eines laufenden Space. Der Dienst schreibt ihn bei jedem Herzschlag, damit ein Neustart nach
 * einem Prozess-Tod (ADR 0017, Neustart) den Beginn der Unterbrechung kennt und den Server am selben Port fortsetzt.
 */
data class RunBeat(
    val spaceId: SpaceId,
    /** Wand-Uhr des Starts (bei einer Wiederaufnahme der ursprüngliche Start). */
    val startedAtMillis: Long,
    /** `elapsedRealtime` zum Start. Dient der Plausibilisierung der Frist, solange dasselbe Gerät läuft. */
    val startedElapsedMillis: Long,
    /** Wand-Uhr des letzten Herzschlags. Beginn einer Unterbrechung. */
    val lastWallMillis: Long,
    /** `elapsedRealtime` des letzten Herzschlags. */
    val lastElapsedMillis: Long,
    /** Port des Servers, damit die Adresse nach dem Neustart gleich bleibt. */
    val port: Int,
)

/** Speicher für den letzten [RunBeat]. Ein Space läuft gleichzeitig, deshalb genügt ein Eintrag. */
interface RunBeatStore {
    fun load(): RunBeat?

    fun save(beat: RunBeat)

    fun clear()
}

/**
 * Speicher über die Einstellungen der App (ohne Datenbankmigration). Schreibt asynchron (`apply`), ein Herzschlag blockiert
 * den Verarbeiter nicht.
 */
class PrefsRunBeatStore(
    context: Context,
) : RunBeatStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): RunBeat? {
        val id = prefs.getString(KEY_SPACE, null)?.let(SpaceId::parse) ?: return null
        if (!prefs.contains(KEY_LAST_WALL)) return null
        return RunBeat(
            spaceId = id,
            startedAtMillis = prefs.getLong(KEY_STARTED_WALL, 0L),
            startedElapsedMillis = prefs.getLong(KEY_STARTED_ELAPSED, 0L),
            lastWallMillis = prefs.getLong(KEY_LAST_WALL, 0L),
            lastElapsedMillis = prefs.getLong(KEY_LAST_ELAPSED, 0L),
            port = prefs.getInt(KEY_PORT, 0),
        )
    }

    override fun save(beat: RunBeat) {
        prefs
            .edit()
            .putString(KEY_SPACE, beat.spaceId.value)
            .putLong(KEY_STARTED_WALL, beat.startedAtMillis)
            .putLong(KEY_STARTED_ELAPSED, beat.startedElapsedMillis)
            .putLong(KEY_LAST_WALL, beat.lastWallMillis)
            .putLong(KEY_LAST_ELAPSED, beat.lastElapsedMillis)
            .putInt(KEY_PORT, beat.port)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "emberlight.space-run"
        const val KEY_SPACE = "space"
        const val KEY_STARTED_WALL = "started_wall"
        const val KEY_STARTED_ELAPSED = "started_elapsed"
        const val KEY_LAST_WALL = "last_wall"
        const val KEY_LAST_ELAPSED = "last_elapsed"
        const val KEY_PORT = "port"
    }
}

/** Speicher im Arbeitsspeicher, für Tests und Fakes. */
class MemoryRunBeatStore : RunBeatStore {
    private var beat: RunBeat? = null

    override fun load(): RunBeat? = beat

    override fun save(beat: RunBeat) {
        this.beat = beat
    }

    override fun clear() {
        beat = null
    }
}
