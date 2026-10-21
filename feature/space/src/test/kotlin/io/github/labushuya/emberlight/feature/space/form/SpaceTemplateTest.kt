package io.github.labushuya.emberlight.feature.space.form

import io.github.labushuya.emberlight.feature.space.SpaceFixtures
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vorlage für einen neuen Space (Paket 3.6c, ADR 0021): Felder, Grenzen der Laufzeit und des Speicherlimits, ohne Titelbild
 * und ohne Medien.
 */
class SpaceTemplateTest {
    private val now = SpaceFixtures.NOW
    private val zone = SpaceFixtures.ZONE
    private val gib = 1024L * 1024L * 1024L

    private fun ended(
        lifetime: Lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
        storageBytes: Long = 4 * gib,
        cover: String? = "a".repeat(64),
        status: SpaceStatus = SpaceStatus.ENDED,
    ): SpaceRecord =
        SpaceFixtures.draft(
            SpaceFixtures.ID_HOCHZEIT,
            "Hochzeit Lena",
            colorIndex = 3,
            lifetime = lifetime,
            retentionDays = 7,
            storageBytes = storageBytes,
            status = status,
            coverSha256 = cover,
        )

    @Test
    fun theTemplateKeepsNameColorRetentionAndLimitButNoCoverAndNoMedia() {
        val result = SpaceTemplate.of(ended(), now, zone, maxStorageBytes = 16 * gib)

        assertEquals("Hochzeit Lena", result.form.name)
        assertEquals(3, result.form.colorIndex)
        assertEquals(RetentionOption.ONE_WEEK, result.form.retention)
        assertEquals(StorageOption.FOUR, result.form.storage)
        assertNull(result.form.coverSha256)
        assertFalse(result.lifetimeReset)
        assertFalse(result.limitReset)
    }

    @Test
    fun aDeletedSpaceGivesTheSameTemplate() {
        val result = SpaceTemplate.of(ended(status = SpaceStatus.DELETED), now, zone, maxStorageBytes = 16 * gib)

        assertEquals("Hochzeit Lena", result.form.name)
        assertEquals(RetentionOption.ONE_WEEK, result.form.retention)
    }

    @Test
    fun aFixedEndInThePastIsResetToTheDefaultLifetime() {
        val past = Lifetime.Until(now - 3_600_000L)

        val result = SpaceTemplate.of(ended(lifetime = past), now, zone, maxStorageBytes = 16 * gib)

        assertEquals(LifetimeOption.ONE_DAY, result.form.lifetime)
        assertNull(result.form.untilDate)
        assertNull(result.form.untilTime)
        assertTrue(result.lifetimeReset)
    }

    @Test
    fun aFixedEndMoreThanThirtyDaysAwayIsAlsoReset() {
        val far = Lifetime.Until(now + 31L * 24 * 3_600_000L)

        val result = SpaceTemplate.of(ended(lifetime = far), now, zone, maxStorageBytes = 16 * gib)

        assertEquals(LifetimeOption.ONE_DAY, result.form.lifetime)
        assertTrue(result.lifetimeReset)
    }

    @Test
    fun aFixedEndInTheFutureWithinThirtyDaysIsKept() {
        val soon = Lifetime.Until(now + 2 * 24 * 3_600_000L)

        val result = SpaceTemplate.of(ended(lifetime = soon), now, zone, maxStorageBytes = 16 * gib)

        assertEquals(LifetimeOption.UNTIL, result.form.lifetime)
        assertFalse(result.lifetimeReset)
    }

    @Test
    fun aLimitAboveTheDeviceCeilingFallsBackToTheLargestAllowedPreset() {
        // Der Space hatte 16 GiB, das Gerät bietet jetzt 2 GiB. Die größte zulässige Voreinstellung ist 2 GiB.
        val result = SpaceTemplate.of(ended(storageBytes = 16 * gib), now, zone, maxStorageBytes = 2 * gib)

        assertEquals(StorageOption.TWO, result.form.storage)
        assertTrue(result.limitReset)
    }

    @Test
    fun aLimitAboveTheCeilingUsesTheNewSpaceDefaultWhenItFits() {
        // Die Voreinstellung 4 GiB passt unter die Obergrenze von 8 GiB, also gilt sie wie für einen neuen Space.
        val result = SpaceTemplate.of(ended(storageBytes = 16 * gib), now, zone, maxStorageBytes = 8 * gib)

        assertEquals(StorageOption.FOUR, result.form.storage)
        assertTrue(result.limitReset)
    }

    @Test
    fun aLimitAboveAllPresetsUsesTheCeilingAsCustomValue() {
        // Die Obergrenze liegt zwischen 0,5 und 1 GiB: keine Voreinstellung passt, also der eigene Wert.
        val result = SpaceTemplate.of(ended(storageBytes = 4 * gib), now, zone, maxStorageBytes = gib / 2)

        assertEquals(StorageOption.CUSTOM, result.form.storage)
        assertEquals("0.5", result.form.storageCustom)
        assertTrue(result.limitReset)
    }

    @Test
    fun aLimitThatStillFitsIsNotChanged() {
        val result = SpaceTemplate.of(ended(storageBytes = 2 * gib), now, zone, maxStorageBytes = 8 * gib)

        assertEquals(StorageOption.TWO, result.form.storage)
        assertFalse(result.limitReset)
    }

    @Test
    fun theDefaultTemplateKeepsTheDefaultColor() {
        val defaults =
            SpaceFixtures.draft(
                SpaceFixtures.ID_GEBURTSTAG,
                "Geburtstag",
                colorIndex = SpaceDefaults.COLOR_INDEX,
            )

        assertEquals(SpaceDefaults.COLOR_INDEX, SpaceTemplate.of(defaults, now, zone, 16 * gib).form.colorIndex)
    }
}
