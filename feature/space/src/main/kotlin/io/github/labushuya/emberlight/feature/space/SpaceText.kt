package io.github.labushuya.emberlight.feature.space

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import io.github.labushuya.emberlight.feature.space.form.StorageOption
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

private const val GIB = 1024.0 * 1024.0 * 1024.0

/** Text zu einem Fehler am Feld. Nutzertexte stehen in `strings.xml` (de und en). */
@StringRes
internal fun SettingsError.messageRes(): Int =
    when (this) {
        SettingsError.NAME_EMPTY -> R.string.space_error_name_empty

        SettingsError.NAME_TOO_LONG -> R.string.space_error_name_too_long

        SettingsError.COLOR_OUT_OF_RANGE -> R.string.space_error_color

        SettingsError.UNTIL_NOT_IN_FUTURE -> R.string.space_error_until_not_future

        SettingsError.UNTIL_TOO_FAR -> R.string.space_error_until_too_far

        SettingsError.UNTIL_MISSING -> R.string.space_error_until_missing

        SettingsError.RETENTION_OUT_OF_RANGE -> R.string.space_error_retention

        SettingsError.STORAGE_OUT_OF_RANGE -> R.string.space_error_storage

        // Mit Zahl, siehe [settingsErrorText].
        SettingsError.STORAGE_ABOVE_MAX -> R.string.space_error_storage_above_max

        SettingsError.COVER_MISSING -> R.string.space_error_cover_missing
    }

/** Fehlertext für die Anzeige. Der Hinweis zur Obergrenze nennt den Wert des Geräts. */
@Composable
internal fun settingsErrorText(
    error: SettingsError,
    storageMaxBytes: Long,
): String =
    when (error) {
        SettingsError.STORAGE_ABOVE_MAX -> stringResource(error.messageRes(), formatGib(storageMaxBytes))
        else -> stringResource(error.messageRes())
    }

@StringRes
internal fun LifetimeOption.labelRes(): Int =
    when (this) {
        LifetimeOption.TWO_HOURS -> R.string.space_lifetime_two_hours
        LifetimeOption.SIX_HOURS -> R.string.space_lifetime_six_hours
        LifetimeOption.TWELVE_HOURS -> R.string.space_lifetime_twelve_hours
        LifetimeOption.ONE_DAY -> R.string.space_lifetime_one_day
        LifetimeOption.THREE_DAYS -> R.string.space_lifetime_three_days
        LifetimeOption.ONE_WEEK -> R.string.space_lifetime_one_week
        LifetimeOption.UNTIL -> R.string.space_lifetime_until
    }

@StringRes
internal fun RetentionOption.labelRes(): Int =
    when (this) {
        RetentionOption.IMMEDIATE -> R.string.space_retention_immediate
        RetentionOption.ONE_DAY -> R.string.space_retention_one_day
        RetentionOption.ONE_WEEK -> R.string.space_retention_one_week
        RetentionOption.CUSTOM -> R.string.space_retention_custom
    }

@StringRes
internal fun StorageOption.labelRes(): Int =
    if (this ==
        StorageOption.CUSTOM
    ) {
        R.string.space_storage_custom
    } else {
        R.string.space_storage_gib
    }

/** Name der Akzentfarbe für Beschreibungen. Die Reihenfolge entspricht `SpaceAccents`. */
@StringRes
internal fun colorNameRes(index: Int): Int = COLOR_NAME_RES.getOrElse(index) { COLOR_NAME_RES.first() }

private val COLOR_NAME_RES: List<Int> =
    listOf(
        R.string.space_color_glow,
        R.string.space_color_gold,
        R.string.space_color_coral,
        R.string.space_color_amber,
        R.string.space_color_rose,
        R.string.space_color_copper,
        R.string.space_color_moss,
        R.string.space_color_dusk,
    )

/** Laufzeit als Text: Dauer oder festes Ende mit Datum und Uhrzeit. */
@Composable
internal fun lifetimeText(lifetime: Lifetime): String =
    when (lifetime) {
        is Lifetime.Preset -> stringResource(LifetimeOption.entries.first { it.preset == lifetime.preset }.labelRes())
        is Lifetime.Until -> stringResource(R.string.space_lifetime_until) + ": " + formatUntil(lifetime.epochMillis)
    }

/** Aufbewahrung nach dem Ende als Text. `0` heißt „Sofort löschen“. */
@Composable
internal fun retentionText(days: Int): String =
    when (days) {
        0 -> stringResource(RetentionOption.IMMEDIATE.labelRes())
        1 -> stringResource(RetentionOption.ONE_DAY.labelRes())
        7 -> stringResource(RetentionOption.ONE_WEEK.labelRes())
        else -> pluralStringResource(R.plurals.space_days, days, days)
    }

/** Speicherlimit als Text in GiB, mit einer Nachkommastelle nur bei halben Werten. */
@Composable
internal fun limitText(bytes: Long): String = stringResource(R.string.space_storage_gib, formatGib(bytes))

/** Zahl in GiB für die Anzeige, mit Dezimalkomma je nach Sprache. */
internal fun formatGib(bytes: Long): String = formatGib(bytes / GIB)

/** Zahl in GiB für die Anzeige, mit Dezimaltrennzeichen der aktuellen Sprache. */
internal fun formatGib(gib: Double): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }
    return format.format(gib)
}

/** Status als Text. Gelöschte Spaces zeigt die Oberfläche nicht, sie fallen auf „Beendet“ zurück. */
@Composable
internal fun statusLabel(status: SpaceStatus): String =
    when (status) {
        SpaceStatus.DRAFT -> stringResource(R.string.space_status_draft)
        SpaceStatus.ACTIVE -> stringResource(R.string.space_status_active)
        SpaceStatus.ENDED -> stringResource(R.string.space_status_ended)
        SpaceStatus.DELETED -> stringResource(R.string.space_status_deleted)
    }

/** Datum und Uhrzeit in der Sprache des Geräts (Mittel-Format), wie im Formular (ADR 0016). */
internal fun formatDate(millis: Long): String =
    DateFormat
        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(millis))

/** Datum in der Form der aktuellen Sprache, etwa „20.10.2026“ auf Deutsch. */
internal fun formatLocalDate(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(date)

/** Uhrzeit in der Form der aktuellen Sprache, etwa „14:30“ auf Deutsch. */
internal fun formatLocalTime(time: LocalTime): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(time)

/** Datum und Uhrzeit eines festen Endes in der Zeitzone des Geräts, in der Form der aktuellen Sprache. */
internal fun formatUntil(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
    return formatLocalDate(local.toLocalDate()) + " " + formatLocalTime(local.toLocalTime())
}
