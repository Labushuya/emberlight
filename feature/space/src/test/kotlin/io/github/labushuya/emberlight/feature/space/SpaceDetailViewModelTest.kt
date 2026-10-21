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
import io.github.labushuya.emberlight.feature.space.retention.RetentionNotifier
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.space.retention.RetentionRepository
import io.github.labushuya.emberlight.share.space.retention.SpacePurge
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Detail-ViewModel (Paket 3.2): Auswahl aus der Route oder der Liste, Löschen samt Speicher. */
@RunWith(RobolectricTestRunner::class)
class SpaceDetailViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository
    private lateinit var spacesDirectory: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val spacesDir = folder.newFolder("spaces")
        spacesDirectory = spacesDir
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { FREE_BYTES })
    }

    private var collector: Job? = null

    /** Aufbewahrung mit denselben Daten wie das Repository (Paket 3.6b). */
    private fun retention(): RetentionReminders =
        RetentionReminders(
            context,
            RetentionRepository(database, SpacePurge(database, SpaceCoverStore(spacesDirectory)) {}),
            RetentionNotifier(context),
        )

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
        val vm = observed(SpaceDetailViewModel(SavedStateHandle(), repository, retention()))

        vm.select(ID)
        assertEquals("Lena", awaitValue(vm.state) { it.record != null }.record?.settings?.name)

        // Die Auswahl läuft über den Datenfluss der Datenbank und kommt asynchron an. Der Test wartet deshalb auf den
        // leeren Zustand, statt ihn direkt nach select(null) zu lesen.
        vm.select(null)
        val cleared = awaitValue(vm.state) { it.record == null && it.selected == null }
        assertNull(cleared.record)
        assertNull(cleared.selected)
    }

    @Test
    fun deletingRemovesTheSpaceAndReportsBack() {
        val id = SpaceId.parse(ID)!!
        runBlocking { repository.create(id, SpaceDefaults.settings().copy(name = "Weg"), null) }
        val vm = observed(SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention()))
        awaitValue(vm.state) { it.record != null }
        val backCalled = CountDownLatch(1)

        vm.delete { backCalled.countDown() }

        // Das Löschen läuft auf dem Datenbank-Thread. Der Test blockiert, bis der Rückruf kommt, ohne Abfrageschleife.
        assertTrue(
            "Der Rückruf nach dem Löschen blieb aus.",
            backCalled.await(STATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )
        assertNull(runBlocking { repository.find(id) })
    }

    // WhileSubscribed: Ohne Beobachter aktualisiert sich der Zustand nicht, so wie in der Oberfläche.
    private fun observed(vm: SpaceDetailViewModel): SpaceDetailViewModel {
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect { } }
        return vm
    }

    private companion object {
        const val ID = "GGGGGGGGGGGGGGGGGGGGGG"
        const val FREE_BYTES = 64L * 1024 * 1024 * 1024
    }
}
