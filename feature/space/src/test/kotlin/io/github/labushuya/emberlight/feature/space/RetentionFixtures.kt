package io.github.labushuya.emberlight.feature.space

import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.feature.space.form.StorageOption
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

    /** Ein gelöschter Space: Name, Farbe und Einstellungen bleiben, das Löschdatum steht dabei (Paket 3.6c). */
    fun deleted(
        name: String,
        days: Int,
    ): SpaceRecord =
        ended(name, days).copy(
            status = SpaceStatus.DELETED,
            deletedAt = SpaceFixtures.NOW - RetentionRules.DAY_MILLIS,
        )

    /** Liste mit einem gelöschten Space und einem Entwurf (Paket 3.6c). */
    fun deletedList(): List<SpaceRecord> =
        listOf(
            deleted("Sommerfest", days = 7),
            SpaceFixtures.draft(SpaceFixtures.ID_WANDERUNG, "Wanderung"),
        )

    /** Formular einer Vorlage aus einem beendeten Space: Hinweis auf die Vorlage und auf eine zurückgesetzte Laufzeit (Paket 3.6c). */
    fun templateState(): SpaceEditUiState {
        val form =
            SpaceForm(
                name = "Sommerfest",
                colorIndex = 3,
                lifetime = LifetimeOption.ONE_DAY,
                retention = RetentionOption.ONE_WEEK,
                storage = StorageOption.FOUR,
            )
        return SpaceEditUiState(
            loaded = true,
            isNew = true,
            editable = true,
            form = form,
            initial = form,
            storageMaxBytes = 16L * 1024 * 1024 * 1024,
            templateNotes = setOf(TemplateNote.TEMPLATE, TemplateNote.LIFETIME_RESET),
        )
    }
}
