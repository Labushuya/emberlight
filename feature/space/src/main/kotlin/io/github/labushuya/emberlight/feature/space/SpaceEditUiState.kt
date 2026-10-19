package io.github.labushuya.emberlight.feature.space

import io.github.labushuya.emberlight.feature.space.form.SpaceField
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.feature.space.form.field
import io.github.labushuya.emberlight.share.space.management.SettingsError
import java.io.File

/** Grund, warum ein Titelbild nicht übernommen wurde. Die Oberfläche übersetzt ihn in einen Text. */
enum class CoverProblem {
    UNSUPPORTED,
    TOO_LARGE,
    LOCATION,
    GENERIC,
}

/**
 * Zustand des Formulars. [errors] sind alle Fehler des aktuellen Standes. Angezeigt werden sie nur für Felder, die der
 * Nutzer berührt hat, oder nach einem Speicherversuch ([attempted]).
 */
data class SpaceEditUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val editable: Boolean = true,
    val form: SpaceForm = SpaceForm(),
    val initial: SpaceForm = SpaceForm(),
    val touched: Set<SpaceField> = emptySet(),
    val attempted: Boolean = false,
    val errors: Set<SettingsError> = emptySet(),
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val coverProblem: CoverProblem? = null,
    val coverFile: File? = null,
    val storageWarning: Boolean = false,
    val showDiscard: Boolean = false,
) {
    /** Ungespeicherte Änderungen gegenüber dem geladenen Stand. */
    val dirty: Boolean get() = form != initial

    /** Speichern ist erst mit gültigen Eingaben möglich. */
    val canSave: Boolean get() = loaded && editable && !saving && errors.isEmpty()

    /** Fehler, die jetzt angezeigt werden. */
    fun visibleErrors(): Set<SettingsError> =
        if (attempted) errors else errors.filterTo(mutableSetOf()) { it.field() in touched }
}
