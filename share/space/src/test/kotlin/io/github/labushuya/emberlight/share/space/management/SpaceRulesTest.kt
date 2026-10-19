package io.github.labushuya.emberlight.share.space.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regeln und Voreinstellungen des Space (Paket 3.2): Validierung je Feld, Grenzen und Standardwerte. */
class SpaceRulesTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun valid(
        name: String = "Hochzeit",
        colorIndex: Int = 0,
        lifetime: Lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
        retentionDays: Int = 7,
        storageLimitBytes: Long = SpaceDefaults.STORAGE_LIMIT_BYTES,
    ) = SpaceSettings(name, colorIndex, lifetime, retentionDays, storageLimitBytes)

    @Test
    fun defaultsMatchTheAgreedValues() {
        val defaults = SpaceDefaults.settings()
        assertEquals("", defaults.name)
        assertEquals(0, defaults.colorIndex)
        assertEquals(Lifetime.Preset(LifetimePreset.ONE_DAY), defaults.lifetime)
        assertEquals(7, defaults.retentionDays)
        assertEquals(4L * 1024 * 1024 * 1024, defaults.storageLimitBytes)
    }

    @Test
    fun defaultsAreValidExceptForTheEmptyName() {
        val errors = SpaceRules.validate(SpaceDefaults.settings(), now)
        assertEquals(setOf(SettingsError.NAME_EMPTY), errors)
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(), now))
    }

    @Test
    fun nameIsTrimmedBeforeTheLengthCheck() {
        assertEquals(setOf(SettingsError.NAME_EMPTY), SpaceRules.validate(valid(name = "   "), now))
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(name = "  Ab  "), now))
    }

    @Test
    fun nameAllowsExactlyFortyCharactersAndCountsCodePoints() {
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(name = "a".repeat(40)), now))
        assertEquals(setOf(SettingsError.NAME_TOO_LONG), SpaceRules.validate(valid(name = "a".repeat(41)), now))
        // Ein Emoji ist ein Zeichen, auch wenn es zwei UTF-16-Einheiten braucht.
        val emojis = "🔥".repeat(40)
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(name = emojis), now))
    }

    @Test
    fun colorMustBeOneOfEightAccents() {
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(colorIndex = 7), now))
        assertEquals(setOf(SettingsError.COLOR_OUT_OF_RANGE), SpaceRules.validate(valid(colorIndex = 8), now))
        assertEquals(setOf(SettingsError.COLOR_OUT_OF_RANGE), SpaceRules.validate(valid(colorIndex = -1), now))
    }

    @Test
    fun presetsKeepTheAgreedDurations() {
        assertEquals(2 * 3600L, LifetimePreset.TWO_HOURS.seconds)
        assertEquals(6 * 3600L, LifetimePreset.SIX_HOURS.seconds)
        assertEquals(12 * 3600L, LifetimePreset.TWELVE_HOURS.seconds)
        assertEquals(24 * 3600L, LifetimePreset.ONE_DAY.seconds)
        assertEquals(72 * 3600L, LifetimePreset.THREE_DAYS.seconds)
        assertEquals(168 * 3600L, LifetimePreset.ONE_WEEK.seconds)
    }

    @Test
    fun untilMustBeInTheFutureAndAtMostThirtyDaysAway() {
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(lifetime = Lifetime.Until(now + hour)), now))
        assertEquals(
            setOf(SettingsError.UNTIL_NOT_IN_FUTURE),
            SpaceRules.validate(valid(lifetime = Lifetime.Until(now)), now),
        )
        assertEquals(
            setOf(SettingsError.UNTIL_NOT_IN_FUTURE),
            SpaceRules.validate(valid(lifetime = Lifetime.Until(now - day)), now),
        )
        assertEquals(
            emptySet<SettingsError>(),
            SpaceRules.validate(valid(lifetime = Lifetime.Until(now + 30 * day)), now),
        )
        assertEquals(
            setOf(SettingsError.UNTIL_TOO_FAR),
            SpaceRules.validate(valid(lifetime = Lifetime.Until(now + 30 * day + 1)), now),
        )
    }

    @Test
    fun retentionAllowsZeroForImmediateDeletionUpToNinetyDays() {
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(retentionDays = 0), now))
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(retentionDays = 90), now))
        assertEquals(setOf(SettingsError.RETENTION_OUT_OF_RANGE), SpaceRules.validate(valid(retentionDays = 91), now))
        assertEquals(setOf(SettingsError.RETENTION_OUT_OF_RANGE), SpaceRules.validate(valid(retentionDays = -1), now))
    }

    @Test
    fun storageLimitIsBetweenHalfAGibibyteAndSixtyFour() {
        val half = SpaceRules.STORAGE_MIN_BYTES
        val max = SpaceRules.STORAGE_MAX_BYTES
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(storageLimitBytes = half), now))
        assertEquals(emptySet<SettingsError>(), SpaceRules.validate(valid(storageLimitBytes = max), now))
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            SpaceRules.validate(valid(storageLimitBytes = half - 1), now),
        )
        assertEquals(
            setOf(SettingsError.STORAGE_OUT_OF_RANGE),
            SpaceRules.validate(valid(storageLimitBytes = max + 1), now),
        )
    }

    @Test
    fun warnsWhenTheLimitExceedsFreeSpaceMinusTwoGibibyte() {
        val gib = 1024L * 1024L * 1024L
        // 10 GiB frei, 2 GiB Reserve: 8 GiB sind die Grenze.
        assertFalse(SpaceRules.exceedsFreeSpace(8 * gib, 10 * gib))
        assertTrue(SpaceRules.exceedsFreeSpace(8 * gib + 1, 10 * gib))
    }

    @Test
    fun errorsAreReportedPerField() {
        val broken =
            SpaceSettings(
                name = "",
                colorIndex = 9,
                lifetime = Lifetime.Until(now - 1),
                retentionDays = 200,
                storageLimitBytes = 1,
            )
        assertEquals(
            setOf(
                SettingsError.NAME_EMPTY,
                SettingsError.COLOR_OUT_OF_RANGE,
                SettingsError.UNTIL_NOT_IN_FUTURE,
                SettingsError.RETENTION_OUT_OF_RANGE,
                SettingsError.STORAGE_OUT_OF_RANGE,
            ),
            SpaceRules.validate(broken, now),
        )
    }
}
