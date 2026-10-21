package io.github.labushuya.emberlight.share.server

import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Benachrichtigungen des Gastgebers zum Ende (Paket 3.5a-3, ADR 0017): eine Vorwarnung zehn Minuten vor dem Ende und eine
 * Meldung beim Ende mit dem Datum der Aufbewahrung. Die Entscheidung ist frei von Android-Typen und damit testbar. Der
 * Dienst fragt sie bei jedem Herzschlag (Vorwarnung) und beim Ende.
 */
internal object EndNotices {
    /** Vorlaufzeit der Vorwarnung in Millisekunden. */
    const val WARNING_LEAD_MILLIS = 10L * 60_000L

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Die Vorwarnung ist fällig, wenn das Ende in der Vorlaufzeit liegt, der Space noch läuft und die Warnung noch nicht
     * gesendet ist. Ohne festes Ende gibt es keine Vorwarnung.
     */
    fun warningDue(
        nowMillis: Long,
        endsAtMillis: Long?,
        alreadyWarned: Boolean,
    ): Boolean {
        if (alreadyWarned || endsAtMillis == null) return false
        return nowMillis >= endsAtMillis - WARNING_LEAD_MILLIS && nowMillis < endsAtMillis
    }

    /** Ende der Aufbewahrung: das Ende plus die Tage nach dem Ende. Negative Tage zählen als null. */
    fun keptUntilMillis(
        endedAtMillis: Long,
        retentionDays: Int,
    ): Long = endedAtMillis + retentionDays.coerceAtLeast(0) * DAY_MILLIS

    /** Datum der Aufbewahrung in der Sprache des Geräts, etwa „16.11.2026“. */
    fun keptUntilDate(
        keptUntilMillis: Long,
        locale: Locale = Locale.getDefault(),
    ): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(keptUntilMillis))
}

/** Merkt sich, welche Benachrichtigungen schon gesendet sind. Die Merkmale überleben Neustarts, damit nichts doppelt kommt. */
internal interface EndNoticeStore {
    fun warned(spaceId: String): Boolean

    fun markWarned(spaceId: String)

    fun ended(spaceId: String): Boolean

    fun markEnded(spaceId: String)
}

/** Speicherung in den Einstellungen der App. Eigene Datei, keine Datenbankmigration. */
internal class PrefsEndNoticeStore(
    private val prefs: SharedPreferences,
) : EndNoticeStore {
    override fun warned(spaceId: String): Boolean = prefs.getBoolean(key(WARNED, spaceId), false)

    override fun markWarned(spaceId: String) {
        prefs.edit { putBoolean(key(WARNED, spaceId), true) }
    }

    override fun ended(spaceId: String): Boolean = prefs.getBoolean(key(ENDED, spaceId), false)

    override fun markEnded(spaceId: String) {
        // Mit dem Ende ist die Vorwarnung erledigt. Ihr Merkmal wird nicht mehr gebraucht.
        prefs.edit {
            putBoolean(key(ENDED, spaceId), true)
            remove(key(WARNED, spaceId))
        }
    }

    private fun key(
        kind: String,
        spaceId: String,
    ): String = "$kind:$spaceId"

    private companion object {
        const val WARNED = "warned"
        const val ENDED = "ended"
    }
}

/** Speicher im Arbeitsspeicher, für Tests. */
internal class MemoryEndNoticeStore : EndNoticeStore {
    private val warned = mutableSetOf<String>()
    private val ended = mutableSetOf<String>()

    override fun warned(spaceId: String): Boolean = spaceId in warned

    override fun markWarned(spaceId: String) {
        warned += spaceId
    }

    override fun ended(spaceId: String): Boolean = spaceId in ended

    override fun markEnded(spaceId: String) {
        ended += spaceId
        warned -= spaceId
    }
}
