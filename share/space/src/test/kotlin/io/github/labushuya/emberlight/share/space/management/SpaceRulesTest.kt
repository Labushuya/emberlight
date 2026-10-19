package io.github.labushuya.emberlight.share.space.management

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regeln und Voreinstellungen des Space (Paket 3.2): Validierung je Feld, Grenzen, Obergrenze des Speichers. */
class SpaceRulesTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val gib = 1024L * 1024L * 1024L

    /** Obergrenze, die für die Feldregeln keine Rolle spielt. */
    private val noDeviceLimit = SpaceRules.STORAGE_MAX_BYTES

    private fun valid(
        name: String = "Hochzeit",
        colorIndex: Int = 0,
        lifetime: Lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
        retentionDays: Int = 7,
        storageLimitBytes: Long = SpaceDefaults.STORAGE_LIMIT_BYTES,
    ) = SpaceSettings(name, colorIndex, lifetime, retentionDays, storageLimitBytes)

    private fun check(
        settings: SpaceSettings,
        ceiling: Long = noDeviceLimit,
    ) = SpaceRules.validate(settings, now, ceiling)

    @Test
    fun defaultsMatchTheAgreedValues() {
        val defaults = SpaceDefaults.settings()
        assertEquals("", defaults.name)
        assertEquals(0, defaults.colorIndex)
        assertEquals(Lifetime.Preset(LifetimePreset.ONE_DAY), defaults.lifetime)
        assertEquals(7, defaults.retentionDays)
        assertEquals(4L * gib, defaults.storageLimitBytes)
    }

    @Test
    fun defaultsAreValidExceptForTheEmptyName() {
        assertEquals(setOf(SettingsError.NAME_EMPTY), check(SpaceDefaults.settings()))
        assertEquals(emptySet<SettingsError>(), check(valid()))
    }

    @Test
    fun nameIsTrimmedBeforeTheLengthCheck() {
        assertEquals(setOf(SettingsError.NAME_EMPTY), check(valid(name = "   ")))
        assertEquals(emptySet<SettingsError>(), check(valid(name = "  Ab  ")))
    }

    @Test
    fun nameAllowsExactlyFortyCharactersAndCountsCodePoints() {
        assertEquals(emptySet<SettingsError>(), check(valid(name = "a".repeat(40))))
        assertEquals(setOf(SettingsError.NAME_TOO_LONG), check(valid(name = "a".repeat(41))))
        // Ein Emoji ist ein Zeichen, auch wenn es zwei UTF-16-Einheiten braucht.
        val emojis = "🔥".repeat(40)
        assertEquals(emptySet<SettingsError>(), check(valid(name = emojis)))
    }

    @Test
    fun colorMustBeOneOfEightAccents() {
        assertEquals(emptySet<SettingsError>(), check(valid(colorIndex = 7)))
        assertEquals(setOf(SettingsError.COLOR_OUT_OF_RANGE), check(valid(colorIndex = 8)))
        assertEquals(setOf(SettingsError.COLOR_OUT_OF_RANGE), check(valid(colorIndex = -1)))
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
        assertEquals(emptySet<SettingsError>(), check(valid(lifetime = Lifetime.Until(now + hour))))
        assertEquals(setOf(SettingsError.UNTIL_NOT_IN_FUTURE), check(valid(lifetime = Lifetime.Until(now))))
        assertEquals(setOf(SettingsError.UNTIL_NOT_IN_FUTURE), check(valid(lifetime = Lifetime.Until(now - day))))
        assertEquals(emptySet<SettingsError>(), check(valid(lifetime = Lifetime.Until(now + 30 * day))))
        assertEquals(setOf(SettingsError.UNTIL_TOO_FAR), check(valid(lifetime = Lifetime.Until(now + 30 * day + 1))))
    }

    @Test
    fun retentionAllowsZeroForImmediateDeletionUpToNinetyDays() {
        assertEquals(emptySet<SettingsError>(), check(valid(retentionDays = 0)))
        assertEquals(emptySet<SettingsError>(), check(valid(retentionDays = 90)))
        assertEquals(setOf(SettingsError.RETENTION_OUT_OF_RANGE), check(valid(retentionDays = 91)))
        assertEquals(setOf(SettingsError.RETENTION_OUT_OF_RANGE), check(valid(retentionDays = -1)))
    }

    @Test
    fun storageLimitIsBetweenHalfAGibibyteAndSixtyFour() {
        val half = SpaceRules.STORAGE_MIN_BYTES
        val max = SpaceRules.STORAGE_MAX_BYTES
        assertEquals(emptySet<SettingsError>(), check(valid(storageLimitBytes = half)))
        assertEquals(emptySet<SettingsError>(), check(valid(storageLimitBytes = max)))
        assertEquals(setOf(SettingsError.STORAGE_OUT_OF_RANGE), check(valid(storageLimitBytes = half - 1)))
        assertEquals(setOf(SettingsError.STORAGE_OUT_OF_RANGE), check(valid(storageLimitBytes = max + 1)))
    }

    @Test
    fun ceilingIsFreeSpaceMinusTwoGibibyteRoundedDownToHalfAGibibyte() {
        // 10 GiB frei: 8 GiB sind die Grenze, genau auf einem Schritt.
        assertEquals(8 * gib, SpaceRules.maxStorageBytes(10 * gib))
        // 10,9 GiB frei: 8,9 GiB, auf halbe GiB abgerundet, also 8,5 GiB.
        assertEquals(8 * gib + gib / 2, SpaceRules.maxStorageBytes(10 * gib + gib * 9 / 10))
        // Ein Byte unter der nächsten Stufe (8,5 GiB) bleibt bei der Stufe darunter.
        assertEquals(8 * gib, SpaceRules.maxStorageBytes(10 * gib + gib / 2 - 1))
    }

    @Test
    fun ceilingIsZeroWhenLessThanHalfAGibibyteIsLeftAfterTheBuffer() {
        assertEquals(0L, SpaceRules.maxStorageBytes(2 * gib))
        assertEquals(0L, SpaceRules.maxStorageBytes(2 * gib + gib / 2 - 1))
        assertEquals(0L, SpaceRules.maxStorageBytes(0))
        assertEquals(0L, SpaceRules.maxStorageBytes(-5 * gib))
        // Genau die Schwelle: 2,5 GiB frei ergeben 0,5 GiB.
        assertEquals(gib / 2, SpaceRules.maxStorageBytes(2 * gib + gib / 2))
    }

    @Test
    fun aLimitAboveTheCeilingIsAnErrorAndAtTheCeilingIsFine() {
        val ceiling = 8 * gib
        assertEquals(emptySet<SettingsError>(), check(valid(storageLimitBytes = 8 * gib), ceiling))
        assertEquals(setOf(SettingsError.STORAGE_ABOVE_MAX), check(valid(storageLimitBytes = 8 * gib + 1), ceiling))
        // Ohne Speicher für einen Space ist auch der kleinste Wert zu groß.
        assertEquals(
            setOf(SettingsError.STORAGE_ABOVE_MAX),
            check(valid(storageLimitBytes = SpaceRules.STORAGE_MIN_BYTES), ceiling = 0L),
        )
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
            check(broken),
        )
    }
}
