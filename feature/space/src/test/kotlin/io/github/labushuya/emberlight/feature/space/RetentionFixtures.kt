package io.github.labushuya.emberlight.feature.space

import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.retention.RetentionRules

/** Beispiele für die Aufbewahrung in den Goldens und Tests (Paket 3.6b). Feste Zeitwerte aus [SpaceFixtures]. */
internal object RetentionFixtures {
    /** Ein beendeter Space vor zwei Tagen mit der angegebenen Frist. */
    fun ended(
        name: String,
        days: Int,
    ): SpaceRecord =
        SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, name, retentionDays = days).copy(
            status = SpaceStatus.ENDED,
            endedAt = SpaceFixtures.NOW - 2 * RetentionRules.DAY_MILLIS,
        )

    /** Liste mit einem beendeten Space (mit Löschdatum) und einem Entwurf. */
    fun list(): List<SpaceRecord> =
        listOf(
            ended("Sommerfest", days = 7),
            SpaceFixtures.draft(SpaceFixtures.ID_WANDERUNG, "Wanderung"),
        )
}
