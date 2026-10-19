package io.github.labushuya.emberlight.feature.space.form

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Formular des Space (Paket 3.2): Umrechnung der Eingaben, Voreinstellungen und Rückführung aus einem Space. */
class SpaceFormTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Long = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val gib = 1024L * 1024L * 1024L

    private fun valid(form: SpaceForm = SpaceForm(name = "Hochzeit")) = form.toSettings(now, zone)

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
    fun untilIsParsedInTheDevicesZone() {
        val form =
            SpaceForm(name = "Abend", lifetime = LifetimeOption.UNTIL, untilDate = "17.10.2026", untilTime = "22:30")
        val expected = ZonedDateTime.of(2026, 10, 17, 22, 30, 0, 0, zone).toInstant().toEpochMilli()

        val parsed = valid(form)

        assertTrue(parsed.errors.isEmpty())
        assertEquals(Lifetime.Until(expected), parsed.settings.lifetime)
    }

    @Test
    fun unreadableUntilIsReported() {
        val form =
            SpaceForm(name = "Abend", lifetime = LifetimeOption.UNTIL, untilDate = "31.02.2026", untilTime = "22:30")
        assertEquals(setOf(SettingsError.UNTIL_UNREADABLE), valid(form).errors)
        val badTime = form.copy(untilDate = "17.10.2026", untilTime = "25:00")
        assertEquals(setOf(SettingsError.UNTIL_UNREADABLE), valid(badTime).errors)
    }

    @Test
    fun untilMustBeInTheFutureAndAtMostThirtyDaysAway() {
        val past = SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL, untilDate = "16.10.2026", untilTime = "11:00")
        assertEquals(setOf(SettingsError.UNTIL_NOT_IN_FUTURE), valid(past).errors)
        val far = SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL, untilDate = "16.11.2026", untilTime = "13:00")
        assertEquals(setOf(SettingsError.UNTIL_TOO_FAR), valid(far).errors)
        // Zwischen dem 16.10. und dem 15.11. liegt die Zeitumstellung. 11:00 ist genau 30 Tage entfernt, 12:00 schon eine Stunde mehr.
        val edge = SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL, untilDate = "15.11.2026", untilTime = "11:00")
        assertTrue(valid(edge).errors.isEmpty())
        val justOver =
            SpaceForm(name = "A", lifetime = LifetimeOption.UNTIL, untilDate = "15.11.2026", untilTime = "12:00")
        assertEquals(setOf(SettingsError.UNTIL_TOO_FAR), valid(justOver).errors)
    }

    @Test
    fun choosingCustomEndSuggestsTomorrowAtTheSameTime() {
        val suggested = SpaceForm(name = "A").withLifetime(LifetimeOption.UNTIL, now, zone)

        assertEquals(LifetimeOption.UNTIL, suggested.lifetime)
        assertEquals("17.10.2026", suggested.untilDate)
        assertEquals("12:00", suggested.untilTime)
        assertTrue(valid(suggested).errors.isEmpty())
    }

    @Test
    fun choosingAPresetKeepsTheTypedUntilFields() {
        val typed = SpaceForm(untilDate = "17.10.2026", untilTime = "10:00")
        val preset = typed.withLifetime(LifetimeOption.TWO_HOURS, now, zone)
        assertEquals(LifetimeOption.TWO_HOURS, preset.lifetime)
        assertEquals("17.10.2026", preset.untilDate)
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
    fun aSpaceRoundTripsThroughTheForm() {
        val record = record(lifetime = Lifetime.Until(now + 3_600_000L), retentionDays = 45, storageBytes = gib / 2 * 3)

        val form = SpaceForm.from(record, zone)

        assertEquals(LifetimeOption.UNTIL, form.lifetime)
        assertEquals(RetentionOption.CUSTOM, form.retention)
        assertEquals("45", form.retentionCustom)
        assertEquals(StorageOption.CUSTOM, form.storage)
        assertEquals("1.5", form.storageCustom)
        val back = form.toSettings(now, zone)
        assertTrue(back.errors.isEmpty())
        assertEquals(record.settings, back.settings)
    }

    @Test
    fun presetValuesComeBackAsPresetOptions() {
        val record =
            record(
                lifetime = Lifetime.Preset(LifetimePreset.THREE_DAYS),
                retentionDays = 7,
                storageBytes =
                    8 * gib,
            )

        val form = SpaceForm.from(record, zone)

        assertEquals(LifetimeOption.THREE_DAYS, form.lifetime)
        assertEquals(RetentionOption.ONE_WEEK, form.retention)
        assertEquals(StorageOption.EIGHT, form.storage)
        assertEquals(record.settings, form.toSettings(now, zone).settings)
    }

    @Test
    fun changedFieldsNamesOnlyWhatChanged() {
        val before = SpaceForm(name = "A")
        assertEquals(setOf(SpaceField.NAME), changedFields(before, before.copy(name = "B")))
        assertEquals(setOf(SpaceField.LIFETIME), changedFields(before, before.copy(untilTime = "10:00")))
        assertEquals(emptySet<SpaceField>(), changedFields(before, before))
    }

    @Test
    fun everyErrorBelongsToItsField() {
        assertEquals(SpaceField.NAME, SettingsError.NAME_TOO_LONG.field())
        assertEquals(SpaceField.LIFETIME, SettingsError.UNTIL_UNREADABLE.field())
        assertEquals(SpaceField.RETENTION, SettingsError.RETENTION_OUT_OF_RANGE.field())
        assertEquals(SpaceField.STORAGE, SettingsError.STORAGE_OUT_OF_RANGE.field())
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
