package io.github.labushuya.emberlight.feature.space

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.NameIdeas
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.feature.space.form.changedFields
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.CoverResult
import io.github.labushuya.emberlight.share.space.management.SaveResult
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import javax.inject.Inject
import kotlin.random.Random

/** Ereignis des Formulars: Das Ziel ist abgeschlossen (gespeichert oder verworfen), die Oberfläche geht zurück. */
sealed interface SpaceEditEvent {
    data object Finished : SpaceEditEvent
}

/**
 * Formular für Anlegen und Bearbeiten (Paket 3.2). Ein neuer Space bekommt seine Kennung schon beim Öffnen, damit das
 * Titelbild gleich in seinen Speicher kann. Gespeichert wird erst bei gültigen Eingaben. Verlassen ohne Speichern
 * verwirft: ein neuer Entwurf wird ganz entfernt, ein gespeicherter Space behält sein gespeichertes Titelbild.
 */
@HiltViewModel
class SpaceEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: SpaceRepository,
        private val random: Random,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val zone: ZoneId = ZoneId.systemDefault()
        private val routeId: String? = savedStateHandle.get<String>(ARG_ID)

        /** Kennung des Entwurfs. Bleibt über einen Prozesstod hinweg erhalten. */
        private val draftId: SpaceId =
            savedStateHandle.get<String>(KEY_DRAFT)?.let { SpaceId.parse(it) }
                ?: SpaceId.generate().also { savedStateHandle[KEY_DRAFT] = it.value }

        private val spaceId: SpaceId = routeId?.let { SpaceId.parse(it) } ?: draftId

        private var saved: SpaceRecord? = null

        private val _state = MutableStateFlow(SpaceEditUiState(isNew = routeId == null))
        val state: StateFlow<SpaceEditUiState> = _state.asStateFlow()

        private val _events = Channel<SpaceEditEvent>(Channel.BUFFERED)
        val events: Flow<SpaceEditEvent> = _events.receiveAsFlow()

        init {
            viewModelScope.launch {
                val existing = if (routeId != null) repository.find(spaceId) else null
                saved = existing
                val ceiling = repository.storageCeilingBytes()
                // Ein neuer Space bekommt die größte zulässige Voreinstellung, wenn die übliche 4 GiB nicht passen.
                val initial = existing?.let { SpaceForm.from(it, zone) } ?: SpaceForm.forNewSpace(ceiling)
                val editable = routeId == null || (existing != null && existing.status == SpaceStatus.DRAFT)
                _state.value =
                    SpaceEditUiState(
                        loaded = true,
                        isNew = routeId == null,
                        editable = editable,
                        form = initial,
                        initial = initial,
                        storageMaxBytes = ceiling,
                    ).withDerived(initial)
            }
        }

        /** Übernimmt einen geänderten Stand des Formulars. */
        fun onFormChange(form: SpaceForm) {
            applyForm(form)
        }

        /** Wählt eine Laufzeit. Bei „Eigenes Ende“ wird ein Vorschlag eingetragen, wenn noch keiner da ist. */
        fun onLifetimeSelected(option: LifetimeOption) {
            applyForm(_state.value.form.withLifetime(option, System.currentTimeMillis(), zone))
        }

        /**
         * Würfel: schlägt einen Namen vor. Ist das Feld leer, wird der Vorschlag gleich übernommen. Steht schon ein eigener
         * Name darin, fragt die Oberfläche vorher nach ([SpaceEditUiState.pendingName]). So geht keine Eingabe verloren.
         */
        fun onSuggestName() {
            val suggestion =
                NameIdeas.suggest(
                    adjectives = appContext.resources.getStringArray(R.array.space_name_adjectives).toList(),
                    nouns = appContext.resources.getStringArray(R.array.space_name_nouns).toList(),
                    random = random,
                )
            val current = _state.value
            if (current.form.name.isBlank()) {
                applyForm(current.form.copy(name = suggestion))
            } else {
                _state.value = current.copy(pendingName = suggestion)
            }
        }

        /** Der Nutzer bestätigt den Ersatz des eigenen Namens durch den Vorschlag. */
        fun confirmNameSuggestion() {
            val pending = _state.value.pendingName ?: return
            _state.value = _state.value.copy(pendingName = null)
            applyForm(_state.value.form.copy(name = pending))
        }

        /** Der Nutzer behält den eigenen Namen. */
        fun dismissNameSuggestion() {
            _state.value = _state.value.copy(pendingName = null)
        }

        /** Titelbild aus der System-Fotoauswahl übernehmen. */
        fun importCover(uri: Uri) {
            viewModelScope.launch {
                importCoverFrom { appContext.contentResolver.openInputStream(uri) }
            }
        }

        /** Titelbild entfernen. Das gespeicherte Bild bleibt, bis gespeichert oder verworfen wird. */
        fun removeCover() {
            applyForm(_state.value.form.copy(coverSha256 = null), clearCoverProblem = true)
            viewModelScope.launch {
                repository.retainCovers(spaceId, setOfNotNull(saved?.coverSha256))
            }
        }

        /** Speichert, wenn alle Eingaben gültig sind. Sonst zeigt das Formular die Fehler. */
        fun save() {
            val current = _state.value
            if (current.saving) return
            if (!current.canSave) {
                _state.value = current.copy(attempted = true)
                return
            }
            viewModelScope.launch {
                _state.value = _state.value.copy(saving = true, attempted = true)
                val form = _state.value.form
                val parsed = form.toSettings(System.currentTimeMillis(), zone, _state.value.storageMaxBytes)
                val result =
                    if (saved == null) {
                        repository.create(spaceId, parsed.settings, form.coverSha256)
                    } else {
                        repository.update(spaceId, parsed.settings, form.coverSha256)
                    }
                when (result) {
                    is SaveResult.Saved -> {
                        saved = result.record
                        _events.send(SpaceEditEvent.Finished)
                    }

                    is SaveResult.Invalid -> {
                        // Die Prüfung beim Speichern gilt mit der aktuellen Obergrenze. Die Anzeige übernimmt sie.
                        _state.value =
                            _state.value.copy(
                                saving = false,
                                saveFailed = true,
                                errors = result.errors,
                                storageMaxBytes = repository.storageCeilingBytes(),
                            )
                    }

                    SaveResult.NotEditable, SaveResult.NotFound -> {
                        _state.value = _state.value.copy(saving = false, editable = false)
                    }
                }
            }
        }

        /** Schließt das Formular. Mit ungespeicherten Änderungen kommt zuerst die Rückfrage. */
        fun requestClose() {
            if (_state.value.dirty) {
                _state.value = _state.value.copy(showDiscard = true)
            } else {
                discard()
            }
        }

        fun dismissDiscard() {
            _state.value = _state.value.copy(showDiscard = false)
        }

        fun confirmDiscard() {
            _state.value = _state.value.copy(showDiscard = false)
            discard()
        }

        /** Übernimmt das Bild aus [open]. Ein Fehler ergibt eine Meldung am Titelbild, nichts wird übernommen. */
        internal suspend fun importCoverFrom(open: () -> InputStream?) {
            val outcome: CoverResult? =
                withContext(Dispatchers.IO) {
                    try {
                        open()?.let { repository.storeCover(spaceId, it) }
                    } catch (failure: IOException) {
                        null
                    }
                }
            when (outcome) {
                is CoverResult.Stored -> {
                    // Ein vorher gewähltes, nicht gespeichertes Bild wird entfernt. Das gespeicherte bleibt bis zum Speichern.
                    repository.retainCovers(spaceId, setOfNotNull(saved?.coverSha256, outcome.sha256))
                    applyForm(_state.value.form.copy(coverSha256 = outcome.sha256), clearCoverProblem = true)
                }

                is CoverResult.UnsupportedType -> {
                    _state.value = _state.value.copy(coverProblem = CoverProblem.UNSUPPORTED)
                }

                is CoverResult.TooLarge -> {
                    _state.value = _state.value.copy(coverProblem = CoverProblem.TOO_LARGE)
                }

                is CoverResult.LocationNotRemovable -> {
                    _state.value = _state.value.copy(coverProblem = CoverProblem.LOCATION)
                }

                null -> {
                    _state.value = _state.value.copy(coverProblem = CoverProblem.GENERIC)
                }
            }
        }

        private fun discard() {
            viewModelScope.launch {
                val record = saved
                if (record == null) {
                    repository.discardUnsaved(spaceId)
                } else {
                    repository.retainCovers(spaceId, setOfNotNull(record.coverSha256))
                }
                _events.send(SpaceEditEvent.Finished)
            }
        }

        private fun applyForm(
            form: SpaceForm,
            clearCoverProblem: Boolean = false,
        ) {
            val current = _state.value
            val next =
                current.copy(
                    form = form,
                    touched = current.touched + changedFields(current.form, form),
                    saveFailed = false,
                    coverProblem = if (clearCoverProblem) null else current.coverProblem,
                )
            _state.value = next.withDerived(form)
        }

        /** Fehler und Titelbild-Pfad aus dem Formular ableiten. Die Obergrenze des Speichers kommt aus dem Zustand. */
        private fun SpaceEditUiState.withDerived(form: SpaceForm): SpaceEditUiState {
            val parsed = form.toSettings(System.currentTimeMillis(), zone, storageMaxBytes)
            val file = form.coverSha256?.let { repository.coverFile(spaceId, it) }
            return copy(errors = parsed.errors, coverFile = file)
        }

        private companion object {
            const val ARG_ID = "id"
            const val KEY_DRAFT = "draftId"
        }
    }
