package io.github.labushuya.emberlight.feature.space.form

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Formular des Space (Paket 3.2): Umrechnung der Eingaben, Voreinstellungen, Obergrenze und Rückführung. */
class SpaceFormTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Long = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val gib = 1024L * 1024L * 1024L

    /** Obergrenze, die für die übrigen Regeln keine Rolle spielt. */
    private val noDeviceLimit = SpaceRules.STORAGE_MAX_BYTES

    private fun valid(
        form: SpaceForm = SpaceForm(name = "Hochzeit"),
        maxStorageBytes: Long = noDeviceLimit,
    ) = form.toSettings(now, zone, maxStorageBytes)

    private fun until(
        date: LocalDate,
        time: LocalTime,
    ) = SpaceForm(name = "Abend", lifetime = LifetimeOption.UNTIL, untilDate = date, untilTime = time)

    @Test
    fun formDefaultsAreTheAgreedPresets() {
        val form = SpaceForm()
        assertEquals(SpaceDefaults.COLOR_INDEX, form.colorIndex)
        assertEquals(LifetimeOption.ONE_DAY, form.lifetime)
        assertEquals(RetentionOption.ONE_WEEK, form.retention)
        assertEquals(StorageOption.FOUR, form.storage)
        assertEquals(null, form.coverSha256)
    }

    @Test
    fun onlyTheNameIsMissingOnAFreshForm() {
        val parsed = valid(SpaceForm())
        assertEquals(setOf(SettingsError.NAME_EMPTY), parsed.errors)
        assertEquals(Lifetime.Preset(LifetimePreset.ONE_DAY), parsed.settings.lifetime)
        assertEquals(7, parsed.settings.retentionDays)
        assertEquals(4 * gib, parsed.settings.storageLimitBytes)
    }

    @Test
    fun aFilledFormIsValidAndNameIsTrimmed() {
        val parsed = valid(SpaceForm(name = "  Geburtstag  "))
        assertTrue(parsed.errors.isEmpty())
        assertEquals("Geburtstag", parsed.settings.name)
    }

    @Test
    fun untilIsTakenFromThePickersInTheDevicesZone() {
        val form = until(LocalDate.of(2026, 10, 17), LocalTime.of(22, 30))
        val expected = ZonedDateTime.of(2026, 10, 17, 22, 30, 0, 0, zone).toInstant().toEpochMilli()

        val parsed = valid(form)

        assertTrue(parsed.errors.isEmpty())
        assertEquals(Lifetime.Until(expected), parsed.settings.lifetime)
    }

    @Test
    fun aCustomEndWithoutDateOrTimeIsMissing() {
        assertEquals(
            setOf(SettingsError.UNTIL_MISSING),
            valid(SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL)).errors,
        )
        assertEquals(
            setOf(SettingsError.UNTIL_MISSING),
            valid(
                SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL, untilDate = LocalDate.of(2026, 10, 17)),
            ).errors,
        )
    }

    @Test
    fun untilMustBeInTheFutureAndAtMostThirtyDaysAway() {
        val past = until(LocalDate.of(2026, 10, 16), LocalTime.of(11, 0))
        assertEquals(setOf(SettingsError.UNTIL_NOT_IN_FUTURE), valid(past).errors)
        val far = until(LocalDate.of(2026, 11, 16), LocalTime.of(13, 0))
        assertEquals(setOf(SettingsError.UNTIL_TOO_FAR), valid(far).errors)
        // Zwischen dem 16.10. und dem 15.11. liegt die Zeitumstellung. 11:00 ist genau 30 Tage entfernt, 12:00 schon eine Stunde mehr.
        val edge = until(LocalDate.of(2026, 11, 15), LocalTime.of(11, 0))
        assertTrue(valid(edge).errors.isEmpty())
        val justOver = until(LocalDate.of(2026, 11, 15), LocalTime.of(12, 0))
        assertEquals(setOf(SettingsError.UNTIL_TOO_FAR), valid(justOver).errors)
    }

    @Test
    fun choosingCustomEndSuggestsTomorrowAtTheSameTime() {
        val suggested = SpaceForm(name = "A").withLifetime(LifetimeOption.UNTIL, now, zone)

        assertEquals(LifetimeOption.UNTIL, suggested.lifetime)
        assertEquals(LocalDate.of(2026, 10, 17), suggested.untilDate)
        assertEquals(LocalTime.of(12, 0), suggested.untilTime)
        assertTrue(valid(suggested).errors.isEmpty())
    }

    @Test
    fun choosingAPresetKeepsTheChosenUntilFields() {
        val typed = SpaceForm(untilDate = LocalDate.of(2026, 10, 17), untilTime = LocalTime.of(10, 0))
        val preset = typed.withLifetime(LifetimeOption.TWO_HOURS, now, zone)
        assertEquals(LifetimeOption.TWO_HOURS, preset.lifetime)
        assertEquals(LocalDate.of(2026, 10, 17), preset.untilDate)
    }

    @Test
    fun customRetentionAcceptsOneToNinetyDays() {
        val ok = valid(SpaceForm(name = "A", retention = RetentionOption.CUSTOM, retentionCustom = "90"))
        assertTrue(ok.errors.isEmpty())
        assertEquals(90, ok.settings.retentionDays)
        assertEquals(
            setOf(SettingsError.RETENTION_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", retention = RetentionOption.CUSTOM, retentionCustom = "91")).errors,
        )
        assertEquals(
            setOf(SettingsError.RETENTION_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", retention = RetentionOption.CUSTOM, retentionCustom = "0")).errors,
        )
        assertEquals(
            setOf(SettingsError.RETENTION_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", retention = RetentionOption.CUSTOM, retentionCustom = "viel")).errors,
        )
    }

    @Test
    fun immediateDeletionIsZeroDays() {
        val parsed = valid(SpaceForm(name = "A", retention = RetentionOption.IMMEDIATE))
        assertTrue(parsed.errors.isEmpty())
        assertEquals(0, parsed.settings.retentionDays)
    }

    @Test
    fun customStorageAcceptsCommaAndPointBetweenHalfAndSixtyFourGib() {
        val half = valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "0,5"))
        assertTrue(half.errors.isEmpty())
        assertEquals(gib / 2, half.settings.storageLimitBytes)
        val max = valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "64"))
        assertTrue(max.errors.isEmpty())
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "0.4")).errors,
        )
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "65")).errors,
        )
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "NaN")).errors,
        )
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "")).errors,
        )
    }

    @Test
    fun presetStoragesAreWholeGibibyte() {
        assertEquals(16 * gib, valid(SpaceForm(name = "A", storage = StorageOption.SIXTEEN)).settings.storageLimitBytes)
        assertEquals(gib, valid(SpaceForm(name = "A", storage = StorageOption.ONE)).settings.storageLimitBytes)
    }

    @Test
    fun aLimitAboveTheDeviceCeilingIsAHardError() {
        val eightGibCeiling = 8 * gib

        assertEquals(
            setOf(SettingsError.STORAGE_ABOVE_MAX),
            valid(SpaceForm(name = "A", storage = StorageOption.SIXTEEN), eightGibCeiling).errors,
        )
        assertEquals(
            setOf(SettingsError.STORAGE_ABOVE_MAX),
            valid(SpaceForm(name = "A", storage = StorageOption.CUSTOM, storageCustom = "9"), eightGibCeiling).errors,
        )
        assertTrue(valid(SpaceForm(name = "A", storage = StorageOption.EIGHT), eightGibCeiling).errors.isEmpty())
    }

    @Test
    fun onlyPresetsUpToTheCeilingAreSelectable() {
        assertTrue(SpaceForm.isStorageAllowed(StorageOption.EIGHT, 8 * gib))
        assertFalse(SpaceForm.isStorageAllowed(StorageOption.SIXTEEN, 8 * gib))
        // Der eigene Wert ist kein Preset. Seine Grenze prüft die Domäne.
        assertTrue(SpaceForm.isStorageAllowed(StorageOption.CUSTOM, 0))
    }

    @Test
    fun aNewSpaceKeepsFourGibibyteWhenItFits() {
        assertEquals(SpaceForm(), SpaceForm.forNewSpace(10 * gib))
        assertEquals(SpaceForm(), SpaceForm.forNewSpace(4 * gib))
    }

    @Test
    fun aNewSpacePreselectsTheLargestPresetBelowTheCeiling() {
        assertEquals(StorageOption.TWO, SpaceForm.forNewSpace(3 * gib + gib / 2).storage)
        // Genau auf der Grenze zählt die Stufe noch.
        assertEquals(StorageOption.TWO, SpaceForm.forNewSpace(2 * gib).storage)
        assertEquals(StorageOption.ONE, SpaceForm.forNewSpace(2 * gib - 1).storage)
    }

    @Test
    fun aNewSpaceFallsBackToTheCeilingItselfWhenNoPresetFits() {
        val onlyHalf = SpaceForm.forNewSpace(gib / 2)
        assertEquals(StorageOption.CUSTOM, onlyHalf.storage)
        assertEquals("0.5", onlyHalf.storageCustom)
        // Unter 0,5 GiB gibt es keinen gültigen Wert. Das Feld bleibt leer, das Formular erklärt den Grund.
        assertEquals("", SpaceForm.forNewSpace(0).storageCustom)
    }

    @Test
    fun aSpaceRoundTripsThroughTheForm() {
        val record = record(lifetime = Lifetime.Until(now + 3_600_000L), retentionDays = 45, storageBytes = gib / 2 * 3)

        val form = SpaceForm.from(record, zone)

        assertEquals(LifetimeOption.UNTIL, form.lifetime)
        assertEquals(LocalDate.of(2026, 10, 16), form.untilDate)
        assertEquals(LocalTime.of(13, 0), form.untilTime)
        assertEquals(RetentionOption.CUSTOM, form.retention)
        assertEquals("45", form.retentionCustom)
        assertEquals(StorageOption.CUSTOM, form.storage)
        assertEquals("1.5", form.storageCustom)
        val back = form.toSettings(now, zone, noDeviceLimit)
        assertTrue(back.errors.isEmpty())
        assertEquals(record.settings, back.settings)
    }

    @Test
    fun presetValuesComeBackAsPresetOptions() {
        val record =
            record(
                lifetime = Lifetime.Preset(LifetimePreset.THREE_DAYS),
                retentionDays = 7,
                storageBytes = 8 * gib,
            )

        val form = SpaceForm.from(record, zone)

        assertEquals(LifetimeOption.THREE_DAYS, form.lifetime)
        assertEquals(RetentionOption.ONE_WEEK, form.retention)
        assertEquals(StorageOption.EIGHT, form.storage)
        assertEquals(record.settings, form.toSettings(now, zone, noDeviceLimit).settings)
    }

    @Test
    fun changedFieldsNamesOnlyWhatChanged() {
        val before = SpaceForm(name = "A")
        assertEquals(setOf(SpaceField.NAME), changedFields(before, before.copy(name = "B")))
        assertEquals(setOf(SpaceField.LIFETIME), changedFields(before, before.copy(untilTime = LocalTime.of(10, 0))))
        assertEquals(emptySet<SpaceField>(), changedFields(before, before))
    }

    @Test
    fun everyErrorBelongsToItsField() {
        assertEquals(SpaceField.NAME, SettingsError.NAME_TOO_LONG.field())
        assertEquals(SpaceField.LIFETIME, SettingsError.UNTIL_MISSING.field())
        assertEquals(SpaceField.RETENTION, SettingsError.RETENTION_OUT_OF_RANGE.field())
        assertEquals(SpaceField.STORAGE, SettingsError.STORAGE_OUT_OF_RANGE.field())
        assertEquals(SpaceField.STORAGE, SettingsError.STORAGE_ABOVE_MAX.field())
        assertEquals(SpaceField.COLOR, SettingsError.COLOR_OUT_OF_RANGE.field())
        assertEquals(SpaceField.COVER, SettingsError.COVER_MISSING.field())
    }

    @Test
    fun gibValuesAreFormattedWithAPointAndNoTrailingZeros() {
        assertEquals("4", SpaceForm.formatGib(4.0))
        assertEquals("0.5", SpaceForm.formatGib(0.5))
        assertEquals("1.25", SpaceForm.formatGib(1.25))
    }

    @Test
    fun unchangedCopyIsEqualAndAnyChangeIsNot() {
        val loaded = SpaceForm(name = "A")
        assertEquals(loaded, loaded.copy())
        assertNotEquals(loaded, loaded.copy(name = "B"))
    }

    private fun record(
        lifetime: Lifetime,
        retentionDays: Int,
        storageBytes: Long,
    ) = SpaceRecord(
        id = SpaceId.parse("AAAAAAAAAAAAAAAAAAAAAA")!!,
        settings =
            SpaceSettings(
                name = "Hochzeit",
                colorIndex = 2,
                lifetime = lifetime,
                retentionDays = retentionDays,
                storageLimitBytes = storageBytes,
            ),
        coverSha256 = null,
        status = SpaceStatus.DRAFT,
        createdAt = now,
        updatedAt = now,
    )
}
