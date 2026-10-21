@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.retention.RetentionNotifier
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.retention.RetentionRepository
import io.github.labushuya.emberlight.share.space.retention.SpacePurge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Liste der Spaces (Paket 3.2): neueste zuerst, und das Titelbild nur, wenn es eines gibt. */
@RunWith(RobolectricTestRunner::class)
class SpacesListViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SpacesDatabase
    private lateinit var repository: SpaceRepository
    private lateinit var collector: Job
    private var latest: List<SpaceRecord> = emptyList()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = SpaceRepository(database, SpaceCoverStore(spacesDir), spacesDir, StorageProbe { FREE_BYTES })
    }

    @After
    fun tearDown() {
        collector.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun theNewestSpaceComesFirst() =
        runBlocking {
            seed(OLDER, createdAt = NOW - DAY, cover = null)
            seed(NEWER, createdAt = NOW, cover = null)
            viewModel()

            awaitTrue { latest.size == 2 }

            assertEquals(listOf(NEWER, OLDER), latest.map { it.id })
        }

    @Test
    fun aSpaceWithoutACoverHasNoCoverFile() =
        runBlocking {
            seed(OLDER, createdAt = NOW, cover = null)
            val vm = viewModel()
            awaitTrue { latest.size == 1 }

            assertNull(vm.coverFile(latest.single()))
        }

    @Test
    fun aSpaceWithACoverPointsAtItsFileInTheSpaceStorage() =
        runBlocking {
            seed(OLDER, createdAt = NOW, cover = COVER)
            val vm = viewModel()
            awaitTrue { latest.size == 1 }

            assertNotNull(vm.coverFile(latest.single()))
        }

    private fun viewModel(): SpacesListViewModel {
        val spacesDir = folder.root.resolve("spaces")
        val retention =
            RetentionReminders(
                context,
                RetentionRepository(database, SpacePurge(database, SpaceCoverStore(spacesDir)) {}),
                RetentionNotifier(context),
            )
        val vm = SpacesListViewModel(repository, retention)
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.spaces.collect { latest = it } }
        return vm
    }

    private suspend fun seed(
        id: SpaceId,
        createdAt: Long,
        cover: String?,
    ) {
        database.spaces().upsert(
            SpaceEntity(
                id = id.value,
                name = "Test ${id.value.take(4)}",
                colorIndex = 0,
                coverSha256 = cover,
                lifetimePreset = "ONE_DAY",
                lifetimeUntil = null,
                retentionDays = 7,
                storageLimitBytes = GIB,
                status = SpaceStatus.DRAFT.name,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }

    private suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60L * 60L * 1000L
        const val GIB = 1024L * 1024L * 1024L
        const val FREE_BYTES = 100 * GIB
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        val COVER = "ab".repeat(32)
        val OLDER = SpaceId.generate()
        val NEWER = SpaceId.generate()
    }
}
