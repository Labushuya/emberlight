package io.github.labushuya.emberlight.feature.space

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import java.time.ZoneId
import java.time.ZonedDateTime

/** Feste Testdaten für Goldens und Oberflächentests. Zeitpunkte sind fest, damit die Bilder reproduzierbar bleiben. */
internal object SpaceFixtures {
    const val ID_HOCHZEIT = "AAAAAAAAAAAAAAAAAAAAAA"
    const val ID_GEBURTSTAG = "BBBBBBBBBBBBBBBBBBBBBB"
    const val ID_WANDERUNG = "CCCCCCCCCCCCCCCCCCCCCC"

    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** 16. Oktober 2026, 12:00 Uhr in Berlin. */
    val NOW: Long = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZONE).toInstant().toEpochMilli()

    private const val GIB = 1024L * 1024L * 1024L

    fun draft(
        id: String,
        name: String,
        colorIndex: Int = SpaceDefaults.COLOR_INDEX,
        lifetime: Lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
        retentionDays: Int = SpaceDefaults.RETENTION_DAYS,
        storageBytes: Long = SpaceDefaults.STORAGE_LIMIT_BYTES,
        status: SpaceStatus = SpaceStatus.DRAFT,
        coverSha256: String? = null,
    ): SpaceRecord =
        SpaceRecord(
            id = SpaceId.parse(id)!!,
            settings =
                SpaceSettings(
                    name = name,
                    colorIndex = colorIndex,
                    lifetime = lifetime,
                    retentionDays = retentionDays,
                    storageLimitBytes = storageBytes,
                ),
            coverSha256 = coverSha256,
            status = status,
            createdAt = NOW,
            updatedAt = NOW,
        )

    /** Drei Entwürfe mit unterschiedlichen Eigenschaften, neueste zuerst wie in der Liste. */
    fun sampleSpaces(): List<SpaceRecord> =
        listOf(
            draft(
                ID_HOCHZEIT,
                "Hochzeit Lena und Tom",
                colorIndex = 0,
                lifetime = Lifetime.Preset(LifetimePreset.TWELVE_HOURS),
            ),
            draft(
                ID_GEBURTSTAG,
                "Geburtstag",
                colorIndex = 2,
                lifetime = Lifetime.Preset(LifetimePreset.SIX_HOURS),
                retentionDays = 0,
                storageBytes = 2 * GIB,
            ),
            draft(
                ID_WANDERUNG,
                "Wanderung am Samstag",
                colorIndex = 6,
                lifetime = Lifetime.Preset(LifetimePreset.ONE_WEEK),
                retentionDays = 1,
                storageBytes = GIB / 2,
            ),
        )
}
