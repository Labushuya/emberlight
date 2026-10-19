package io.github.labushuya.emberlight.feature.space.form

import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

private const val GIB = 1024L * 1024L * 1024L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/** Auswahl der Laufzeit im Formular. [UNTIL] liest Datum und Uhrzeit aus dem Formular. */
enum class LifetimeOption(
    val preset: LifetimePreset?,
) {
    TWO_HOURS(LifetimePreset.TWO_HOURS),
    SIX_HOURS(LifetimePreset.SIX_HOURS),
    TWELVE_HOURS(LifetimePreset.TWELVE_HOURS),
    ONE_DAY(LifetimePreset.ONE_DAY),
    THREE_DAYS(LifetimePreset.THREE_DAYS),
    ONE_WEEK(LifetimePreset.ONE_WEEK),
    UNTIL(null),
}

/** Auswahl der Aufbewahrung nach dem Ende. [CUSTOM] liest die Tage aus dem Formular. */
enum class RetentionOption(
    val days: Int?,
) {
    IMMEDIATE(0),
    ONE_DAY(1),
    ONE_WEEK(7),
    CUSTOM(null),
}

/** Auswahl des Speicherlimits in GiB. [CUSTOM] liest den Wert aus dem Formular. */
enum class StorageOption(
    val gib: Int?,
) {
    ONE(1),
    TWO(2),
    FOUR(4),
    EIGHT(8),
    SIXTEEN(16),
    CUSTOM(null),
}

/**
 * Eingaben des Formulars für Anlegen und Bearbeiten (Paket 3.2). Die Felder bleiben Eingaben, bis sie geprüft werden.
 * Datum und Uhrzeit kommen aus den Auswahldialogen und sind schon typisiert. Die Umrechnung in die Domäne geschieht in
 * [toSettings], die Standardwerte kommen aus [SpaceDefaults].
 */
data class SpaceForm(
    val name: String = "",
    val colorIndex: Int = SpaceDefaults.COLOR_INDEX,
    val lifetime: LifetimeOption = LifetimeOption.ONE_DAY,
    val untilDate: LocalDate? = null,
    val untilTime: LocalTime? = null,
    val retention: RetentionOption = RetentionOption.ONE_WEEK,
    val retentionCustom: String = "",
    val storage: StorageOption = StorageOption.FOUR,
    val storageCustom: String = "",
    val coverSha256: String? = null,
) {
    /**
     * Prüft und rechnet um. [maxStorageBytes] ist die Obergrenze des Speicherlimits auf diesem Gerät. Felder mit Fehler
     * bekommen für die übrige Prüfung einen gültigen Ersatzwert.
     */
    fun toSettings(
        nowMillis: Long,
        zone: ZoneId,
        maxStorageBytes: Long,
    ): FormParse {
        val errors = mutableSetOf<SettingsError>()
        val lifetimeValue =
            when (val preset = lifetime.preset) {
                null -> {
                    val until = untilMillis(zone)
                    if (until == null) {
                        errors += SettingsError.UNTIL_MISSING
                        Lifetime.Until(nowMillis + DAY_MILLIS)
                    } else {
                        Lifetime.Until(until)
                    }
                }

                else -> {
                    Lifetime.Preset(preset)
                }
            }

        val retentionDays =
            retention.days ?: parseRetention(retentionCustom).also { days ->
                if (days == null) errors += SettingsError.RETENTION_OUT_OF_RANGE
            } ?: SpaceDefaults.RETENTION_DAYS

        val storageBytes =
            storage.gib?.let { it * GIB } ?: parseGib(storageCustom).also { bytes ->
                if (bytes == null) errors += SettingsError.STORAGE_OUT_OF_RANGE
            } ?: SpaceDefaults.STORAGE_LIMIT_BYTES

        val settings =
            SpaceSettings(
                name = SpaceRules.normalizeName(name),
                colorIndex = colorIndex,
                lifetime = lifetimeValue,
                retentionDays = retentionDays,
                storageLimitBytes = storageBytes,
            )
        errors += SpaceRules.validate(settings, nowMillis, maxStorageBytes)
        // Eine eigene Aufbewahrung beginnt bei einem Tag. Null heißt im Formular „Sofort löschen“, nicht „Eigene“.
        if (retention == RetentionOption.CUSTOM && retentionDays == 0) errors += SettingsError.RETENTION_OUT_OF_RANGE
        return FormParse(settings, errors)
    }

    /** Zeitpunkt des festen Endes in der Zeitzone des Geräts. `null`, solange Datum oder Uhrzeit fehlen. */
    fun untilMillis(zone: ZoneId): Long? {
        val date = untilDate ?: return null
        val time = untilTime ?: return null
        return LocalDateTime
            .of(date, time)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** Wählt eine Laufzeit. Für „Eigenes Ende“ wird ein Vorschlag eingetragen, wenn noch keiner da ist: morgen, gleiche Zeit. */
    fun withLifetime(
        option: LifetimeOption,
        nowMillis: Long,
        zone: ZoneId,
    ): SpaceForm {
        if (option != LifetimeOption.UNTIL || (untilDate != null && untilTime != null)) return copy(lifetime = option)
        val suggestion = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis + DAY_MILLIS), zone)
        return copy(
            lifetime = option,
            untilDate = suggestion.toLocalDate(),
            untilTime = suggestion.toLocalTime().withSecond(0).withNano(0),
        )
    }

    companion object {
        /**
         * Formular für einen neuen Space. Die Voreinstellung 4 GiB bleibt, außer sie liegt über der Obergrenze des Geräts:
         * dann wird die größte zulässige Voreinstellung gewählt. Passt keine, bekommt das Feld den Wert der Obergrenze.
         */
        fun forNewSpace(maxStorageBytes: Long): SpaceForm {
            val default = SpaceForm()
            if (isStorageAllowed(StorageOption.FOUR, maxStorageBytes)) return default
            val largest =
                StorageOption.entries
                    .filter { isStorageAllowed(it, maxStorageBytes) && it != StorageOption.CUSTOM }
                    .maxByOrNull { it.gib ?: 0 }
            if (largest != null) return default.copy(storage = largest)
            // Keine Voreinstellung passt. Die Obergrenze selbst ist dann der gültige Wert, sofern es einen gibt.
            val custom =
                if (maxStorageBytes >=
                    SpaceRules.STORAGE_MIN_BYTES
                ) {
                    formatGib(maxStorageBytes.toDouble() / GIB)
                } else {
                    ""
                }
            return default.copy(storage = StorageOption.CUSTOM, storageCustom = custom)
        }

        /** Eine Voreinstellung ist wählbar, wenn sie nicht über der Obergrenze liegt. Eigene Werte prüft [SpaceRules]. */
        fun isStorageAllowed(
            option: StorageOption,
            maxStorageBytes: Long,
        ): Boolean = option.gib?.let { it.toLong() * GIB <= maxStorageBytes } ?: true

        /** Formular für einen gespeicherten Space. Die Laufzeit mit festem Ende wird in Datum und Uhrzeit zerlegt. */
        fun from(
            record: SpaceRecord,
            zone: ZoneId,
        ): SpaceForm {
            val settings = record.settings
            val lifetime = settings.lifetime
            val untilParts =
                (lifetime as? Lifetime.Until)?.let { until ->
                    val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(until.epochMillis), zone)
                    local.toLocalDate() to local.toLocalTime()
                }
            val retention =
                RetentionOption.entries.firstOrNull { it.days == settings.retentionDays } ?: RetentionOption.CUSTOM
            val storageGib = settings.storageLimitBytes.toDouble() / GIB
            val storage =
                StorageOption.entries.firstOrNull {
                    it.gib != null &&
                        it.gib.toLong() * GIB == settings.storageLimitBytes
                }
                    ?: StorageOption.CUSTOM
            return SpaceForm(
                name = settings.name,
                colorIndex = settings.colorIndex,
                lifetime =
                    (lifetime as? Lifetime.Preset)?.let { preset ->
                        LifetimeOption.entries.first {
                            it.preset ==
                                preset.preset
                        }
                    }
                        ?: LifetimeOption.UNTIL,
                untilDate = untilParts?.first,
                untilTime = untilParts?.second,
                retention = retention,
                retentionCustom = if (retention == RetentionOption.CUSTOM) settings.retentionDays.toString() else "",
                storage = storage,
                storageCustom = if (storage == StorageOption.CUSTOM) formatGib(storageGib) else "",
                coverSha256 = record.coverSha256,
            )
        }

        /** Ein Dezimalwert in GiB mit Punkt. Ganze Zahlen ohne Nachkommastellen. */
        fun formatGib(gib: Double): String =
            if (gib % 1.0 ==
                0.0
            ) {
                gib.toLong().toString()
            } else {
                "%.2f".format(java.util.Locale.ROOT, gib).trimEnd('0')
            }

        private fun parseRetention(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf {
                it in
                    1..SpaceRules.RETENTION_MAX_DAYS
            }

        private fun parseGib(text: String): Long? {
            val gib =
                text
                    .trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: return null
            return (gib * GIB).roundToLong()
        }
    }
}

/** Ergebnis von [SpaceForm.toSettings]: die Werte (mit Ersatz bei Fehlern) und alle Fehler der Felder. */
data class FormParse(
    val settings: SpaceSettings,
    val errors: Set<SettingsError>,
)

/** Felder des Formulars, für die Anzeige von Fehlern nach einer Änderung. */
enum class SpaceField {
    NAME,
    COLOR,
    LIFETIME,
    RETENTION,
    STORAGE,
    COVER,
}

/** Welche Felder sich zwischen zwei Ständen geändert haben. Nur diese zeigen danach ihre Fehler. */
fun changedFields(
    before: SpaceForm,
    after: SpaceForm,
): Set<SpaceField> =
    buildSet {
        if (before.name != after.name) add(SpaceField.NAME)
        if (before.colorIndex != after.colorIndex) add(SpaceField.COLOR)
        if (before.lifetime != after.lifetime || before.untilDate != after.untilDate ||
            before.untilTime != after.untilTime
        ) {
            add(SpaceField.LIFETIME)
        }
        if (before.retention != after.retention ||
            before.retentionCustom != after.retentionCustom
        ) {
            add(SpaceField.RETENTION)
        }
        if (before.storage != after.storage || before.storageCustom != after.storageCustom) add(SpaceField.STORAGE)
        if (before.coverSha256 != after.coverSha256) add(SpaceField.COVER)
    }

/** Das Feld, zu dem ein Fehler gehört. */
fun SettingsError.field(): SpaceField =
    when (this) {
        SettingsError.NAME_EMPTY, SettingsError.NAME_TOO_LONG -> SpaceField.NAME

        SettingsError.COLOR_OUT_OF_RANGE -> SpaceField.COLOR

        SettingsError.UNTIL_NOT_IN_FUTURE,
        SettingsError.UNTIL_TOO_FAR,
        SettingsError.UNTIL_MISSING,
        -> SpaceField.LIFETIME

        SettingsError.RETENTION_OUT_OF_RANGE -> SpaceField.RETENTION

        SettingsError.STORAGE_OUT_OF_RANGE,
        SettingsError.STORAGE_ABOVE_MAX,
        -> SpaceField.STORAGE

        SettingsError.COVER_MISSING -> SpaceField.COVER
    }
