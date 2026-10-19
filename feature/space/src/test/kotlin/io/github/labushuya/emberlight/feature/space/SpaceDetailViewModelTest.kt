@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceDefaults
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Detail-ViewModel (Paket 3.2): Auswahl aus der Route oder der Liste, Löschen samt Speicher. */
@RunWith(RobolectricTestRunner::class)
class SpaceDetailViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { FREE_BYTES })
    }

    private var collector: Job? = null

    @After
    fun tearDown() {
        collector?.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun selectingASpaceLoadsItAndClearingTheSelectionHidesIt() {
        val id = SpaceId.parse(ID)!!
        runBlocking { repository.create(id, SpaceDefaults.settings().copy(name = "Lena"), null) }
        val vm = observed(SpaceDetailViewModel(SavedStateHandle(), repository))

        vm.select(ID)
        assertEquals("Lena", waitFor(vm) { it.record != null }.record?.settings?.name)

        vm.select(null)
        assertNull(vm.state.value.record)
        assertTrue(vm.state.value.selected == null)
    }

    @Test
    fun deletingRemovesTheSpaceAndReportsBack() {
        val id = SpaceId.parse(ID)!!
        runBlocking { repository.create(id, SpaceDefaults.settings().copy(name = "Weg"), null) }
        val vm = observed(SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository))
        waitFor(vm) { it.record != null }
        val backCalled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        vm.delete { backCalled.set(true) }

        // Das Löschen läuft auf dem Datenbank-Thread. Erst wenn der Rückruf kommt, ist es abgeschlossen.
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!backCalled.get() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertTrue(backCalled.get())
        assertNull(runBlocking { repository.find(id) })
    }

    // WhileSubscribed: Ohne Beobachter aktualisiert sich der Zustand nicht, so wie in der Oberfläche.
    private fun observed(vm: SpaceDetailViewModel): SpaceDetailViewModel {
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect { } }
        return vm
    }

    private fun waitFor(
        vm: SpaceDetailViewModel,
        predicate: (SpaceDetailUiState) -> Boolean,
    ): SpaceDetailUiState {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val current = vm.state.value
            if (predicate(current)) return current
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Zustand wurde nicht erreicht: ${vm.state.value}")
    }

    private companion object {
        const val ID = "GGGGGGGGGGGGGGGGGGGGGG"
        const val FREE_BYTES = 64L * 1024 * 1024 * 1024
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
    }
}
