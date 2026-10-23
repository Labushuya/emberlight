package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.export.ExportedMediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Auswahl der Übernahme (Paket 3.7, ADR 0022): Vorauswahl nichts, „Alle“ und „Nichts“, bereits übernommene Medien
 * lassen sich nicht wählen, gelöschte Spaces sind nicht übernehmbar.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceExportViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = SpacesDatabase.open(context, folder.newFolder("spaces"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun nothingIsSelectedAtTheStartAndStartIsOff() {
        insertSpace(ID, "ENDED")
        insertMedium("m1", createdAt = 1_000L)
        insertMedium("m2", createdAt = 2_000L, video = true)

        val viewModel = viewModel()
        waitUntilLoaded(viewModel)

        val state = viewModel.state.value
        assertEquals(0, state.selectedCount)
        assertEquals(0L, state.selectedBytes)
        assertFalse(state.canStart)
        assertEquals(2, state.items.size)
        assertTrue(state.items.none { it.selected })
        // Neueste zuerst.
        assertEquals("m2", state.items.first().mediaId)
        assertTrue(state.items.first().isVideo)
    }

    @Test
    fun allSelectsEveryMediumThatIsNotExportedAndNoneClearsTheSelection() {
        insertSpace(ID, "ENDED")
        insertMedium("m1", createdAt = 1_000L, size = 100L)
        insertMedium("m2", createdAt = 2_000L, size = 300L)
        insertMedium("m3", createdAt = 3_000L, size = 500L)
        runBlocking {
            database.exports().insertExported(
                ExportedMediaEntity("m2", ID, "zwei.jpg", "content://x/2", 1L),
            )
        }
        val viewModel = viewModel()
        waitUntilLoaded(viewModel)

        viewModel.selectAll()
        val all = viewModel.state.value
        assertEquals(2, all.selectedCount)
        assertEquals(600L, all.selectedBytes)
        assertTrue(all.items.single { it.mediaId == "m2" }.exported)
        assertFalse(all.items.single { it.mediaId == "m2" }.selected)
        assertTrue(all.canStart)

        viewModel.selectNone()
        assertEquals(0, viewModel.state.value.selectedCount)
        assertFalse(viewModel.state.value.canStart)
    }

    @Test
    fun aTapTogglesOneMediumAndAnExportedOneCannotBeChosen() {
        insertSpace(ID, "ENDED")
        insertMedium("m1", createdAt = 1_000L)
        runBlocking { database.exports().insertExported(ExportedMediaEntity("m9", ID, "x.jpg", "content://x/9", 1L)) }
        val viewModel = viewModel()
        waitUntilLoaded(viewModel)

        viewModel.toggle("m1")
        assertEquals(1, viewModel.state.value.selectedCount)
        viewModel.toggle("m1")
        assertEquals(0, viewModel.state.value.selectedCount)
        viewModel.toggle("unbekannt")
        assertEquals(0, viewModel.state.value.selectedCount)
    }

    @Test
    fun aSpaceThatIsDeletedOrBeingDeletedIsNotExportable() {
        insertSpace(ID, "DELETED")
        insertMedium("m1", createdAt = 1_000L)

        val viewModel = viewModel()
        waitUntilLoaded(viewModel)

        assertEquals(ExportPhase.NotExportable, viewModel.state.value.phase)
        viewModel.toggle("m1")
        viewModel.start()
        assertEquals(ExportPhase.NotExportable, viewModel.state.value.phase)
    }

    @Test
    fun aSpaceWithoutMediaShowsAnEmptyList() {
        insertSpace(ID, "ENDED")

        val viewModel = viewModel()
        waitUntilLoaded(viewModel)

        assertTrue(
            viewModel.state.value.items
                .isEmpty(),
        )
        assertFalse(viewModel.state.value.canStart)
    }

    private fun viewModel(): SpaceExportViewModel =
        SpaceExportViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to ID)),
            database = database,
            context = context,
        )

    /** Das Laden läuft auf dem Hauptthread und liest die Datenbank im Hintergrund. Bis dahin Schleife mit Leerlauf. */
    private fun waitUntilLoaded(viewModel: SpaceExportViewModel) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (viewModel.state.value.loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Der Zustand hat nicht geladen", viewModel.state.value.loading)
    }

    private fun insertSpace(
        id: String,
        status: String,
    ) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = "Urlaub",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 1,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = status,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = if (status == "ENDED") 2_000L else null,
                ),
            )
        }
    }

    private fun insertMedium(
        id: String,
        createdAt: Long,
        size: Long = 10L,
        video: Boolean = false,
    ) {
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id,
                    spaceId = ID,
                    sha256 = id.padEnd(64, '0'),
                    mime = if (video) "video/mp4" else "image/jpeg",
                    sizeBytes = size,
                    originalName = "$id.bin",
                    uploaderId = "gast",
                    createdAt = createdAt,
                    state = "READY",
                ),
            )
        }
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
