package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.retention.RetentionNotifier
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.retention.RetentionRepository
import io.github.labushuya.emberlight.share.space.retention.RetentionRules
import io.github.labushuya.emberlight.share.space.retention.SpacePurge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Aktionen im Detail eines beendeten Space (Paket 3.6b): Bestätigung über die Route, Frist ändern, Jetzt löschen mit
 * Ergebnis. Ein aktiver Space bleibt bei „Jetzt löschen“ unangetastet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RetentionDetailViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository
    private lateinit var retention: RetentionReminders

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { 8L * GIB })
        retention =
            RetentionReminders(
                context,
                RetentionRepository(database, SpacePurge(database, SpaceCoverStore(spacesDir)) {}),
                RetentionNotifier(context),
            )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun theConfirmationOpensWhenTheRouteAsksForIt() {
        val vm =
            SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID, "confirmPurge" to true)), repository, retention)

        assertTrue(vm.confirmPurgeRequested)
        assertFalse(
            SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention).confirmPurgeRequested,
        )
    }

    @Test
    fun purgingAnEndedSpaceRemovesItAndReturns() {
        insertEnded()
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)
        var done = false

        vm.purge(onDone = { done = true })

        eventually { done }
        assertEquals("DELETED", runBlocking { repository.find(SpaceId.parse(ID)!!) }?.status?.name)
        assertFalse(vm.failure.value)
    }

    @Test
    fun removingADeletedSpaceFromTheListGoesBackAndRemovesIt() {
        insertEnded()
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)
        vm.purge(onDone = {})
        eventually { runBlocking { repository.find(SpaceId.parse(ID)!!) }?.status?.name == "DELETED" }
        var done = false

        vm.removeFromList(onDone = { done = true })

        eventually { done }
        assertNull(runBlocking { repository.find(SpaceId.parse(ID)!!) })
    }

    @Test
    fun purgingAnActiveSpaceIsRefusedAndShowsAFailure() {
        insertEnded(status = "ACTIVE")
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)
        var done = false

        vm.purge(onDone = { done = true })

        eventually { vm.failure.value }
        assertFalse(done)
        assertTrue(runBlocking { repository.find(SpaceId.parse(ID)!!) } != null)
    }

    @Test
    fun changingTheRetentionStoresTheNewPeriod() {
        insertEnded()
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)

        vm.changeRetention(days = 3)

        eventually { runBlocking { repository.find(SpaceId.parse(ID)!!) }?.retentionUntil != null }
        val until = runBlocking { repository.find(SpaceId.parse(ID)!!) }?.retentionUntil ?: 0L
        assertTrue(until > System.currentTimeMillis() + 2 * RetentionRules.DAY_MILLIS)
    }

    @Test
    fun aRetentionChangeThatIsRefusedIsReportedAndCanBeConsumed() {
        insertEnded(status = "ACTIVE")
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)

        vm.changeRetention(days = 3)

        eventually { vm.problem.value == DetailProblem.RETENTION_NOT_CHANGED }
        vm.consumeProblem()
        assertNull(vm.problem.value)
    }

    @Test
    fun changingTheRetentionOfAMissingSpaceIsReportedAsNotFound() {
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)

        vm.changeRetention(days = 3)

        eventually { vm.problem.value == DetailProblem.NOT_FOUND }
    }

    @Test
    fun removingAnEndedSpaceFromTheListIsRefusedAndReported() {
        insertEnded()
        val vm = SpaceDetailViewModel(SavedStateHandle(mapOf("id" to ID)), repository, retention)
        vm.select(ID)
        var done = false

        vm.removeFromList(onDone = { done = true })

        eventually { vm.problem.value == DetailProblem.REMOVE_NOT_DELETED }
        assertFalse(done)
        assertTrue(runBlocking { repository.find(SpaceId.parse(ID)!!) } != null)
    }

    /** Die Löschung und das Speichern laufen auf dem Thread von Room. Die Prüfung wartet deshalb kurz. */
    private fun eventually(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(check())
    }

    private fun insertEnded(status: String = "ENDED") {
        runBlocking {
            database.spaces().upsert(
                io.github.labushuya.emberlight.share.space.SpaceEntity(
                    id = ID,
                    name = "Sommerfest",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 1,
                    storageLimitBytes = 4L * GIB,
                    status = status,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = if (status == "ENDED") 2_000L else null,
                ),
            )
        }
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val GIB = 1024L * 1024L * 1024L
    }
}
