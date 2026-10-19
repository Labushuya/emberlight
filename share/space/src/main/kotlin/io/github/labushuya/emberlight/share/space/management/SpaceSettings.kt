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
    UNTIL_UNREADABLE,
    RETENTION_OUT_OF_RANGE,
    STORAGE_OUT_OF_RANGE,
    COVER_MISSING,
}

/** Grenzen der Eingaben (Paket 3.2, Festlegung vom 18.10.2026). */
object SpaceRules {
    const val NAME_MAX_CHARS = 40
    const val ACCENT_COUNT = 8
    const val UNTIL_MAX_DAYS = 30L
    const val RETENTION_MAX_DAYS = 90
    const val STORAGE_MIN_BYTES = GIB / 2
    const val STORAGE_MAX_BYTES = 64 * GIB

    /** Mindest-Restspeicher des Geräts, der nach dem Speicherlimit frei bleiben soll (Warnung, nicht blockierend). */
    const val DEVICE_RESERVE_BYTES = 2 * GIB

    /** Name ohne führende und folgende Leerzeichen. Die Länge zählt Unicode-Zeichen, nicht UTF-16-Einheiten. */
    fun normalizeName(raw: String): String = raw.trim()

    /** Prüft alle Felder. [nowMillis] ist der Bezugszeitpunkt für ein festes Ende. Liefert eine leere Menge bei Gültigkeit. */
    fun validate(
        settings: SpaceSettings,
        nowMillis: Long,
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
        }
        return errors
    }

    /** Warnung: Das Limit übersteigt den freien Speicher abzüglich des Mindest-Restspeichers. */
    fun exceedsFreeSpace(
        storageLimitBytes: Long,
        freeBytes: Long,
    ): Boolean = storageLimitBytes > freeBytes - DEVICE_RESERVE_BYTES
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

/** Gespeicherter Space mit Kennung, Eigenschaften, Titelbild-Verweis und Status. */
data class SpaceRecord(
    val id: SpaceId,
    val settings: SpaceSettings,
    val coverSha256: String?,
    val status: SpaceStatus,
    val createdAt: Long,
    val updatedAt: Long,
)
