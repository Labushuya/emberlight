@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.NameIdeas
import io.github.labushuya.emberlight.feature.space.form.SpaceField
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.feature.space.form.StorageOption
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.CoverResult
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Formular-ViewModel (Paket 3.2): Anlegen, Speichern mit Fehlern, Rückfrage beim Verlassen, Titelbild-Übernahme und der
 * Schreibschutz für Spaces, die kein Entwurf mehr sind. Room und der Speicher laufen echt, unter Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceEditViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository
    private val gib = 1024L * 1024L * 1024L

    /** Freier Speicher des Geräts in diesem Test. Ohne Vorgabe reicht er für alle Stufen. */
    private var freeBytes = 64 * gib

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { freeBytes })
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        routeId: String? = null,
        draftId: String = DRAFT,
    ): SpaceEditViewModel {
        val handle =
            SavedStateHandle(
                buildMap {
                    put(KEY_DRAFT, draftId)
                    if (routeId != null) put(ARG_ID, routeId)
                },
            )
        return SpaceEditViewModel(handle, repository, Random(SEED), context)
    }

    private fun awaitState(
        vm: SpaceEditViewModel,
        predicate: (SpaceEditUiState) -> Boolean,
    ): SpaceEditUiState {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val current = vm.state.value
            if (predicate(current)) return current
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Zustand wurde nicht erreicht: ${vm.state.value}")
    }

    private fun awaitLoaded(vm: SpaceEditViewModel): SpaceEditUiState = awaitState(vm) { it.loaded }

    private fun awaitFinished(vm: SpaceEditViewModel) {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { vm.events.first() } }
    }

    private fun jpeg(size: Int = 200) = CoverFixtures.jpeg(size = size)

    @Test
    fun aNewSpaceStartsAsADraftWithTheAgreedDefaults() {
        val vm = viewModel()

        val state = awaitLoaded(vm)

        assertTrue(state.isNew)
        assertTrue(state.editable)
        assertEquals(SpaceForm(), state.form)
        assertEquals(setOf(SettingsError.NAME_EMPTY), state.errors)
    }

    @Test
    fun savingInvalidInputShowsAllErrorsAndWritesNothing() {
        val vm = viewModel()
        awaitLoaded(vm)

        vm.save()

        val state = awaitState(vm) { it.attempted }
        assertTrue(state.visibleErrors().contains(SettingsError.NAME_EMPTY))
        assertFalse(state.canSave)
        assertNull(runBlocking { repository.find(SpaceId.parse(DRAFT)!!) })
    }

    @Test
    fun savingAValidFormCreatesTheSpaceAndFinishes() {
        val vm = viewModel()
        awaitLoaded(vm)
        vm.onFormChange(SpaceForm(name = "  Hochzeit  "))
        awaitState(vm) { it.canSave }

        vm.save()

        awaitFinished(vm)
        val stored = runBlocking { repository.find(SpaceId.parse(DRAFT)!!) }
        assertNotNull(stored)
        assertEquals("Hochzeit", stored?.settings?.name)
        assertEquals(SpaceStatus.DRAFT, stored?.status)
    }

    @Test
    fun aFieldShowsItsErrorOnlyAfterItWasTouched() {
        val vm = viewModel()
        val untouched = awaitLoaded(vm)
        // Ein anderes Feld zu ändern zeigt den Namensfehler noch nicht.
        vm.onFormChange(untouched.form.copy(colorIndex = 2))
        val colorChanged = awaitState(vm) { it.form.colorIndex == 2 }
        assertEquals(setOf(SpaceField.COLOR), colorChanged.touched)
        assertTrue(colorChanged.visibleErrors().isEmpty())

        // Erst eine Änderung am Namen, die ihn leer lässt, macht den Fehler sichtbar.
        vm.onFormChange(colorChanged.form.copy(name = "A"))
        vm.onFormChange(colorChanged.form.copy(name = ""))
        val touched = awaitState(vm) { it.touched.contains(SpaceField.NAME) }
        assertTrue(touched.visibleErrors().contains(SettingsError.NAME_EMPTY))
    }

    @Test
    fun closingWithChangesAsksBeforeDiscardingAndDiscardingRemovesTheDraft() {
        val vm = viewModel()
        awaitLoaded(vm)
        runBlocking { vm.importCoverFrom { ByteArrayInputStream(jpeg()) } }
        vm.onFormChange(
            vm.state.value.form
                .copy(name = "Entwurf"),
        )
        awaitState(vm) { it.dirty }

        vm.requestClose()
        assertTrue(awaitState(vm) { it.showDiscard }.showDiscard)
        vm.confirmDiscard()

        awaitFinished(vm)
        assertFalse(File(spacesDir, DRAFT).exists())
    }

    @Test
    fun closingWithoutChangesLeavesImmediately() {
        val vm = viewModel()
        awaitLoaded(vm)

        vm.requestClose()

        awaitFinished(vm)
        assertFalse(awaitState(vm) { true }.showDiscard)
    }

    @Test
    fun aSupportedImageBecomesTheCoverOfTheDraft() {
        val vm = viewModel()
        awaitLoaded(vm)

        runBlocking { vm.importCoverFrom { ByteArrayInputStream(jpeg()) } }

        val state = awaitState(vm) { it.form.coverSha256 != null }
        assertNull(state.coverProblem)
        assertNotNull(state.coverFile)
        assertTrue(state.coverFile!!.isFile)
    }

    @Test
    fun aVideoIsRefusedAsCoverWithAMessageAndNothingIsKept() {
        val vm = viewModel()
        awaitLoaded(vm)

        runBlocking { vm.importCoverFrom { ByteArrayInputStream(CoverFixtures.mp4()) } }

        val state = awaitState(vm) { it.coverProblem != null }
        assertEquals(CoverProblem.UNSUPPORTED, state.coverProblem)
        assertNull(state.form.coverSha256)
    }

    @Test
    fun aLocationThatCannotBeRemovedIsRefusedWithoutAConsentOption() {
        val vm = viewModel()
        awaitLoaded(vm)
        val notRemovable = CoverFixtures.pngWithCompressedLocation()

        runBlocking { vm.importCoverFrom { ByteArrayInputStream(notRemovable) } }

        val state = awaitState(vm) { it.coverProblem != null }
        assertEquals(CoverProblem.LOCATION, state.coverProblem)
        assertNull(state.form.coverSha256)
        assertTrue(File(spacesDir, DRAFT).listFiles().orEmpty().none { it.name.startsWith("cover-") })
    }

    @Test
    fun aMissingStreamIsAGenericCoverProblem() {
        val vm = viewModel()
        awaitLoaded(vm)

        runBlocking { vm.importCoverFrom { null } }

        assertEquals(CoverProblem.GENERIC, awaitState(vm) { it.coverProblem != null }.coverProblem)
    }

    @Test
    fun aSavedCoverStaysWhileAnUnsavedReplacementIsDiscarded() {
        val id = SpaceId.parse(SAVED_ID)!!
        val savedCover =
            runBlocking {
                repository.create(id, SpaceDefaults.settings().copy(name = "Gespeichert"), null)
                (repository.storeCover(id, ByteArrayInputStream(jpeg(120))) as CoverResult.Stored)
            }
        runBlocking { repository.update(id, SpaceDefaults.settings().copy(name = "Gespeichert"), savedCover.sha256) }
        val vm = viewModel(routeId = SAVED_ID)
        awaitLoaded(vm)
        runBlocking { vm.importCoverFrom { ByteArrayInputStream(jpeg(160)) } }
        val replacement = awaitState(vm) { it.form.coverSha256 != savedCover.sha256 && it.form.coverSha256 != null }

        vm.requestClose()
        assertTrue(awaitState(vm) { it.showDiscard }.showDiscard)
        vm.confirmDiscard()
        awaitFinished(vm)

        assertTrue(repository.coverFile(id, savedCover.sha256).isFile)
        assertFalse(repository.coverFile(id, replacement.form.coverSha256!!).exists())
    }

    @Test
    fun anActiveSpaceCannotBeEdited() {
        val id = SpaceId.parse(ACTIVE_ID)!!
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id.value,
                    name = "Aktiv",
                    colorIndex = 0,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 7,
                    storageLimitBytes = SpaceDefaults.STORAGE_LIMIT_BYTES,
                    status = SpaceStatus.ACTIVE.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }

        val state = awaitLoaded(viewModel(routeId = ACTIVE_ID))

        assertFalse(state.editable)
        assertFalse(state.canSave)
        assertEquals("Aktiv", state.form.name)
    }

    @Test
    fun aStoredDraftIsLoadedIntoTheForm() {
        val id = SpaceId.parse(SAVED_ID)!!
        runBlocking { repository.create(id, SpaceDefaults.settings().copy(name = "Geladen", colorIndex = 3), null) }

        val state = awaitLoaded(viewModel(routeId = SAVED_ID))

        assertFalse(state.isNew)
        assertTrue(state.editable)
        assertEquals("Geladen", state.form.name)
        assertEquals(3, state.form.colorIndex)
        assertEquals(state.form, state.initial)
        assertFalse(state.dirty)
    }

    @Test
    fun theDiceFillsAnEmptyNameAtOnceWithTheSuggestionFromTheSeededSource() {
        val vm = viewModel()
        awaitLoaded(vm)

        vm.onSuggestName()

        val state = awaitState(vm) { it.form.name.isNotEmpty() }
        val (adjectives, nouns) = wordLists()
        assertEquals(NameIdeas.suggest(adjectives, nouns, Random(SEED)), state.form.name)
        assertNull(state.pendingName)
    }

    @Test
    fun theDiceAsksBeforeReplacingAnOwnNameAndKeepsItOnDismiss() {
        val vm = viewModel()
        awaitLoaded(vm)
        vm.onFormChange(
            vm.state.value.form
                .copy(name = "Eigen"),
        )
        awaitState(vm) { it.form.name == "Eigen" }

        vm.onSuggestName()
        val asked = awaitState(vm) { it.pendingName != null }
        assertEquals("Eigen", asked.form.name)

        vm.dismissNameSuggestion()
        val kept = awaitState(vm) { it.pendingName == null }
        assertEquals("Eigen", kept.form.name)
    }

    @Test
    fun confirmingTheSuggestionReplacesTheOwnName() {
        val vm = viewModel()
        awaitLoaded(vm)
        vm.onFormChange(
            vm.state.value.form
                .copy(name = "Eigen"),
        )
        vm.onSuggestName()
        val pending = awaitState(vm) { it.pendingName != null }.pendingName!!

        vm.confirmNameSuggestion()

        val state = awaitState(vm) { it.pendingName == null }
        assertEquals(pending, state.form.name)
        // Der Vorschlag bleibt editierbar und ist gültig.
        assertTrue(state.errors.isEmpty())
    }

    @Test
    fun aNewSpaceWithoutRoomForFourGibibytePreselectsTheLargestFittingPreset() {
        // 4,5 GiB frei: 2,5 GiB sind die Grenze, also passt die Stufe 2 GiB.
        freeBytes = 4 * gib + gib / 2

        val state = awaitLoaded(viewModel())

        assertEquals(2 * gib + gib / 2, state.storageMaxBytes)
        assertEquals(StorageOption.TWO, state.form.storage)
        assertEquals(setOf(SettingsError.NAME_EMPTY), state.errors)
    }

    @Test
    fun aLimitAboveTheCeilingBlocksSavingAndNothingIsWritten() {
        freeBytes = 10 * gib
        val vm = viewModel()
        awaitLoaded(vm)

        vm.onFormChange(SpaceForm(name = "Abend", storage = StorageOption.SIXTEEN))

        val state = awaitState(vm) { it.errors.contains(SettingsError.STORAGE_ABOVE_MAX) }
        assertFalse(state.canSave)
        assertEquals(8 * gib, state.storageMaxBytes)
        vm.save()
        assertNull(runBlocking { repository.find(SpaceId.parse(DRAFT)!!) })
    }

    @Test
    fun aCeilingThatShrankSinceOpeningIsCheckedAgainAndShownAtSave() {
        val vm = viewModel()
        awaitLoaded(vm)
        vm.onFormChange(SpaceForm(name = "Abend", storage = StorageOption.EIGHT))
        awaitState(vm) { it.canSave }

        // Das Gerät ist voller geworden: die Obergrenze liegt jetzt bei 3 GiB.
        freeBytes = 5 * gib
        vm.save()

        val state = awaitState(vm) { it.saveFailed }
        assertTrue(state.errors.contains(SettingsError.STORAGE_ABOVE_MAX))
        assertEquals(3 * gib, state.storageMaxBytes)
        assertNull(runBlocking { repository.find(SpaceId.parse(DRAFT)!!) })
    }

    @Test
    fun aCustomEndChosenWithThePickersIsSavedAsThatInstant() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(1)
        val vm = viewModel()
        awaitLoaded(vm)
        vm.onFormChange(
            SpaceForm(
                name = "Abend",
                lifetime = LifetimeOption.UNTIL,
                untilDate = day,
                untilTime = LocalTime.of(22, 30),
            ),
        )
        awaitState(vm) { it.canSave }

        vm.save()

        awaitFinished(vm)
        val expected = ZonedDateTime.of(day, LocalTime.of(22, 30), zone).toInstant().toEpochMilli()
        assertEquals(
            Lifetime.Until(expected),
            runBlocking { repository.find(SpaceId.parse(DRAFT)!!) }?.settings?.lifetime,
        )
    }

    private fun wordLists(): Pair<List<String>, List<String>> =
        context.resources.getStringArray(R.array.space_name_adjectives).toList() to
            context.resources.getStringArray(R.array.space_name_nouns).toList()

    private companion object {
        const val SEED = 42
        const val DRAFT = "DDDDDDDDDDDDDDDDDDDDDD"
        const val SAVED_ID = "EEEEEEEEEEEEEEEEEEEEEE"
        const val ACTIVE_ID = "FFFFFFFFFFFFFFFFFFFFFF"
        const val ARG_ID = "id"
        const val KEY_DRAFT = "draftId"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
    }
}
