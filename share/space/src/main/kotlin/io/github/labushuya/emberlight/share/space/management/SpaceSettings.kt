package io.github.labushuya.emberlight.share.space.management

import io.github.labushuya.emberlight.share.space.SpaceId

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB
private const val HOUR_SECONDS = 3600L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/** Feste Laufzeiten (KONZEPT, Spaces). Die Dauer wird erst beim Start in einen Zeitpunkt umgerechnet (Paket 3.3). */
enum class LifetimePreset(
    val seconds: Long,
) {
    TWO_HOURS(2 * HOUR_SECONDS),
    SIX_HOURS(6 * HOUR_SECONDS),
    TWELVE_HOURS(12 * HOUR_SECONDS),
    ONE_DAY(24 * HOUR_SECONDS),
    THREE_DAYS(72 * HOUR_SECONDS),
    ONE_WEEK(168 * HOUR_SECONDS),
}

/**
 * Laufzeit eines Space als Regel, nicht als Zeitpunkt: [Preset] ist eine Dauer ab dem Start, [Until] ein festes Ende
 * (Datum und Uhrzeit in UTC-Millisekunden).
 */
sealed interface Lifetime {
    data class Preset(
        val preset: LifetimePreset,
    ) : Lifetime

    data class Until(
        val epochMillis: Long,
    ) : Lifetime
}

/** Vom Gastgeber festgelegte Eigenschaften eines Space. Das Titelbild steht getrennt (Verweis über den Hash). */
data class SpaceSettings(
    val name: String,
    val colorIndex: Int,
    val lifetime: Lifetime,
    /** Nachhaltefrist in Tagen. `0` heißt „Sofort löschen“ (KONZEPT, Ende und Nachhalten). */
    val retentionDays: Int,
    val storageLimitBytes: Long,
)

/** Grund für ein ungültiges Feld. Die Oberfläche übersetzt ihn in einen Text (Nutzertexte in `strings.xml`). */
enum class SettingsError {
    NAME_EMPTY,
    NAME_TOO_LONG,
    COLOR_OUT_OF_RANGE,
    UNTIL_NOT_IN_FUTURE,
    UNTIL_TOO_FAR,
    UNTIL_MISSING,
    RETENTION_OUT_OF_RANGE,
    STORAGE_OUT_OF_RANGE,

    /** Eigener Wert über der Obergrenze des Geräts. Harte Grenze, sperrt das Speichern (Paket 3.2, Entscheidung 2). */
    STORAGE_ABOVE_MAX,
    COVER_MISSING,
}

/** Grenzen der Eingaben (Paket 3.2, Festlegung vom 18.10.2026, harte Speichergrenze vom 19.10.2026). */
object SpaceRules {
    const val NAME_MAX_CHARS = 40
    const val ACCENT_COUNT = 8
    const val UNTIL_MAX_DAYS = 30L
    const val RETENTION_MAX_DAYS = 90
    const val STORAGE_MIN_BYTES = GIB / 2
    const val STORAGE_MAX_BYTES = 64 * GIB

    /** Schrittweite, auf die die Obergrenze des Speicherlimits abgerundet wird. */
    const val STORAGE_STEP_BYTES = GIB / 2

    /** Puffer, der nach dem Speicherlimit auf dem Gerät frei bleiben muss. Teil der Obergrenze, keine Reservierung. */
    const val DEVICE_RESERVE_BYTES = 2 * GIB

    /**
     * Obergrenze des Speicherlimits bei [freeBytes] freiem Speicher: frei minus [DEVICE_RESERVE_BYTES], abgerundet auf
     * [STORAGE_STEP_BYTES]. Bleibt weniger als ein Schritt, ist die Grenze `0`, und kein Space lässt sich anlegen.
     * Das Limit ist keine Reservierung. Die Prüfung läuft beim Speichern und beim Start (Paket 3.3).
     */
    fun maxStorageBytes(freeBytes: Long): Long {
        val usable = freeBytes - DEVICE_RESERVE_BYTES
        if (usable < STORAGE_STEP_BYTES) return 0L
        return usable / STORAGE_STEP_BYTES * STORAGE_STEP_BYTES
    }

    /** Name ohne führende und folgende Leerzeichen. Die Länge zählt Unicode-Zeichen, nicht UTF-16-Einheiten. */
    fun normalizeName(raw: String): String = raw.trim()

    /**
     * Prüft alle Felder. [nowMillis] ist der Bezugszeitpunkt für ein festes Ende, [maxStorageBytes] die Obergrenze des
     * Speicherlimits auf diesem Gerät (siehe [maxStorageBytes]). Liefert eine leere Menge bei Gültigkeit.
     */
    fun validate(
        settings: SpaceSettings,
        nowMillis: Long,
        maxStorageBytes: Long,
    ): Set<SettingsError> {
        val errors = mutableSetOf<SettingsError>()
        val name = normalizeName(settings.name)
        if (name.isEmpty()) {
            errors += SettingsError.NAME_EMPTY
        } else if (name.codePointCount(0, name.length) > NAME_MAX_CHARS) {
            errors += SettingsError.NAME_TOO_LONG
        }
        if (settings.colorIndex !in 0 until ACCENT_COUNT) errors += SettingsError.COLOR_OUT_OF_RANGE
        // Eine Dauer ist immer gültig (sie wird beim Start umgerechnet). Nur ein festes Ende braucht eine Prüfung.
        val lifetime = settings.lifetime
        if (lifetime is Lifetime.Until) {
            if (lifetime.epochMillis <= nowMillis) {
                errors += SettingsError.UNTIL_NOT_IN_FUTURE
            } else if (lifetime.epochMillis - nowMillis > UNTIL_MAX_DAYS * DAY_MILLIS) {
                errors += SettingsError.UNTIL_TOO_FAR
            }
        }
        if (settings.retentionDays !in 0..RETENTION_MAX_DAYS) errors += SettingsError.RETENTION_OUT_OF_RANGE
        if (settings.storageLimitBytes !in STORAGE_MIN_BYTES..STORAGE_MAX_BYTES) {
            errors += SettingsError.STORAGE_OUT_OF_RANGE
        } else if (settings.storageLimitBytes > maxStorageBytes) {
            errors += SettingsError.STORAGE_ABOVE_MAX
        }
        return errors
    }
}

/** Voreinstellungen beim Anlegen (Paket 3.2). Der Name ist bewusst leer, das Feld ist Pflicht. */
object SpaceDefaults {
    const val COLOR_INDEX = 0
    val LIFETIME: Lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY)
    const val RETENTION_DAYS = 7
    const val STORAGE_LIMIT_BYTES = 4 * GIB

    fun settings(): SpaceSettings =
        SpaceSettings(
            name = "",
            colorIndex = COLOR_INDEX,
            lifetime = LIFETIME,
            retentionDays = RETENTION_DAYS,
            storageLimitBytes = STORAGE_LIMIT_BYTES,
        )
}

/** Status eines Space. In Paket 3.2 entstehen nur Entwürfe. Aktiv folgt mit dem Start in Paket 3.3. */
enum class SpaceStatus {
    DRAFT,
    ACTIVE,
    ENDED,
    DELETED,
}

/**
 * Gespeicherter Space mit Kennung, Eigenschaften, Titelbild-Verweis und Status. Start, geplantes und tatsächliches Ende
 * sind nur gesetzt, wenn der Space gestartet wurde (Paket 3.3).
 */
data class SpaceRecord(
    val id: SpaceId,
    val settings: SpaceSettings,
    val coverSha256: String?,
    val status: SpaceStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val expiresAt: Long? = null,
    val endedAt: Long? = null,
)
