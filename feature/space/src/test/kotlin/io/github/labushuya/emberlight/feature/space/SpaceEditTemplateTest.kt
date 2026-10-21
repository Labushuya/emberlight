@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.StorageOption
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.CoverResult
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
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
import kotlin.random.Random

/**
 * Vorlage im Formular (Paket 3.6c, ADR 0021): Ein beendeter oder gelöschter Space füllt den neuen Entwurf aus, das
 * Titelbild wird in den neuen Space kopiert, und die Hinweise nennen, was angepasst wurde. Ein aktiver Space oder eine
 * unbekannte Kennung ist keine Vorlage. Room und der Speicher laufen echt, unter Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceEditTemplateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository
    private val gib = 1024L * 1024L * 1024L

    /** Freier Speicher des Geräts in diesem Test. Bestimmt die Obergrenze des Speicherlimits. */
    private var freeBytes = 64 * gib

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        freeBytes = 64 * gib
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { freeBytes })
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(templateId: String?): SpaceEditViewModel {
        val handle =
            SavedStateHandle(
                buildMap {
                    put(KEY_DRAFT, DRAFT)
                    if (templateId != null) put(ARG_TEMPLATE, templateId)
                },
            )
        return SpaceEditViewModel(handle, repository, Random(SEED), context)
    }

    private fun awaitState(
        vm: SpaceEditViewModel,
        predicate: (SpaceEditUiState) -> Boolean,
    ): SpaceEditUiState = awaitValue(vm.state, predicate = predicate)

    private fun awaitFinished(vm: SpaceEditViewModel) {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { vm.events.first() } }
    }

    /** Legt einen Space mit der gegebenen Frist und dem Status direkt in der Tabelle an, wie ihn die Liste vorfindet. */
    private fun insertSpace(
        id: String,
        status: String,
        preset: String? = "ONE_DAY",
        until: Long? = null,
        storageBytes: Long = 4 * gib,
        cover: String? = null,
    ) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = "Vorlage",
                    colorIndex = 3,
                    coverSha256 = cover,
                    lifetimePreset = preset,
                    lifetimeUntil = until,
                    retentionDays = 7,
                    storageLimitBytes = storageBytes,
                    status = status,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = if (status == SpaceStatus.ENDED.name) 2_000L else null,
                ),
            )
        }
    }

    /** Legt das Titelbild der Vorlage in deren Speicher ab und liefert seinen Hash. */
    private fun storeTemplateCover(): String {
        val result =
            runBlocking {
                repository.storeCover(SpaceId.parse(TEMPLATE)!!, ByteArrayInputStream(CoverFixtures.jpeg(size = 200)))
            }
        return (result as CoverResult.Stored).sha256
    }

    @Test
    fun aTemplateFillsTheNewDraftAndItsCoverIsCopiedIntoTheDraft() {
        val sha = storeTemplateCover()
        insertSpace(TEMPLATE, SpaceStatus.ENDED.name, cover = sha)

        val vm = viewModel(templateId = TEMPLATE)
        val state = awaitState(vm) { it.loaded && it.form.coverSha256 == sha }

        assertTrue(state.isNew)
        assertTrue(state.editable)
        assertEquals("Vorlage", state.form.name)
        assertEquals(setOf(TemplateNote.TEMPLATE), state.templateNotes)
        // Die Kopie liegt im Speicher des neuen Entwurfs. Der Vorlage gehört sie nicht mehr als Bezug.
        assertTrue(repository.coverFile(SpaceId.parse(DRAFT)!!, sha).isFile)
    }

    @Test
    fun aTemplateWithoutCoverLeavesTheDraftWithoutOne() {
        insertSpace(TEMPLATE, SpaceStatus.ENDED.name)

        val state = awaitState(viewModel(templateId = TEMPLATE)) { it.loaded }

        assertNull(state.form.coverSha256)
        assertEquals(setOf(TemplateNote.TEMPLATE), state.templateNotes)
    }

    @Test
    fun aDeletedSpaceIsATemplateToo() {
        insertSpace(TEMPLATE, SpaceStatus.DELETED.name, preset = "ONE_DAY")

        val state = awaitState(viewModel(templateId = TEMPLATE)) { it.loaded }

        assertEquals("Vorlage", state.form.name)
        assertEquals(setOf(TemplateNote.TEMPLATE), state.templateNotes)
    }

    @Test
    fun aFixedEndThatHasPassedIsResetAndTheNoteSaysSo() {
        val passed = System.currentTimeMillis() - DAY_MILLIS
        insertSpace(TEMPLATE, SpaceStatus.ENDED.name, preset = null, until = passed)

        val state = awaitState(viewModel(templateId = TEMPLATE)) { it.loaded }

        assertEquals(LifetimeOption.ONE_DAY, state.form.lifetime)
        assertEquals(setOf(TemplateNote.TEMPLATE, TemplateNote.LIFETIME_RESET), state.templateNotes)
    }

    @Test
    fun aStorageLimitAboveTheCeilingIsResetAndTheNoteSaysSo() {
        // Auf einem Gerät mit 1 GiB frei passen die 4 GiB der Vorlage nicht mehr.
        freeBytes = gib
        insertSpace(TEMPLATE, SpaceStatus.ENDED.name, storageBytes = 4 * gib)

        val state = awaitState(viewModel(templateId = TEMPLATE)) { it.loaded }

        assertTrue(state.templateNotes.contains(TemplateNote.LIMIT_RESET))
        assertTrue(state.form.storage != StorageOption.FOUR)
    }

    @Test
    fun anActiveSpaceIsNoTemplate() {
        insertSpace(TEMPLATE, SpaceStatus.ACTIVE.name)

        val state = awaitState(viewModel(templateId = TEMPLATE)) { it.loaded }

        assertTrue(state.templateNotes.isEmpty())
        assertEquals("", state.form.name)
    }

    @Test
    fun anUnknownOrInvalidTemplateIdStartsAnEmptyDraft() {
        val unknown = awaitState(viewModel(templateId = UNKNOWN)) { it.loaded }
        val invalid = awaitState(viewModel(templateId = "kein-Schlüssel")) { it.loaded }

        assertTrue(unknown.templateNotes.isEmpty())
        assertTrue(invalid.templateNotes.isEmpty())
        assertEquals("", unknown.form.name)
    }

    @Test
    fun savingTheTemplateDraftCreatesTheSpaceWithTheTemplateSettingsAndCover() {
        val sha = storeTemplateCover()
        insertSpace(TEMPLATE, SpaceStatus.ENDED.name, cover = sha)
        val vm = viewModel(templateId = TEMPLATE)
        awaitState(vm) { it.loaded && it.form.coverSha256 == sha && it.canSave }

        vm.save()

        awaitFinished(vm)
        val stored = runBlocking { repository.find(SpaceId.parse(DRAFT)!!) }
        assertNotNull(stored)
        assertEquals("Vorlage", stored?.settings?.name)
        assertEquals(sha, stored?.coverSha256)
        assertEquals(SpaceStatus.DRAFT, stored?.status)
    }

    private companion object {
        const val SEED = 42
        const val DRAFT = "DDDDDDDDDDDDDDDDDDDDDD"
        const val TEMPLATE = "AAAAAAAAAAAAAAAAAAAAAA"
        const val UNKNOWN = "GGGGGGGGGGGGGGGGGGGGGG"
        const val ARG_TEMPLATE = "template"
        const val KEY_DRAFT = "draftId"
        const val TIMEOUT_MILLIS = 5_000L
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
