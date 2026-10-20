package io.github.labushuya.emberlight.feature.space.run

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.StartProblem
import io.github.labushuya.emberlight.share.space.run.SystemRunClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows

/**
 * Start und Fortsetzen aus der Oberfläche (Paket 3.3, ADR 0017). Ein Entwurf geht zurück auf Entwurf, wenn der Dienst nicht
 * startet. Ein fortgesetzter Space bleibt aktiv. Die Datenbank ist echt, der Dienst wird nur als Auftrag geprüft.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceStarterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = SpaceId.generate()
    private lateinit var database: SpacesDatabase
    private lateinit var runs: SpaceRunRepository

    @Before
    fun setUp() {
        database = SpacesDatabase.open(context, folder.newFolder("spaces"))
        runs = SpaceRunRepository(database, SystemRunClock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aDraftWithWifiStartsAndTheServiceGetsTheStartCommand() =
        runBlocking {
            draft(SpaceStatus.DRAFT)

            val outcome = starter(wifi = WIFI).start(id)

            assertEquals(StartOutcome.Started, outcome)
            assertEquals(SpaceStatus.ACTIVE.name, database.spaces().find(id.value)?.status)
            val intent = Shadows.shadowOf(context as Application).nextStartedService
            assertEquals(SpaceServerService.ACTION_START, intent.action)
            assertEquals(id.value, intent.getStringExtra(SpaceServerService.EXTRA_SPACE_ID))
            assertFalse(intent.getBooleanExtra(SpaceServerService.EXTRA_RESUME, true))
        }

    @Test
    fun aDraftWithoutWifiStaysADraft() =
        runBlocking {
            draft(SpaceStatus.DRAFT)

            val outcome = starter(wifi = null).start(id)

            assertEquals(StartOutcome.NoWifi, outcome)
            assertEquals(SpaceStatus.DRAFT.name, database.spaces().find(id.value)?.status)
        }

    @Test
    fun aStorageLimitAboveTheFreeSpaceIsRefusedBeforeTheServiceIsAsked() =
        runBlocking {
            draft(SpaceStatus.DRAFT, limitBytes = FREE_BYTES)

            val outcome = starter(wifi = WIFI).start(id)

            assertEquals(StartOutcome.Refused(StartProblem.STORAGE_ABOVE_FREE), outcome)
            assertNull(Shadows.shadowOf(context as Application).nextStartedService)
        }

    @Test
    fun aRefusedForegroundStartTurnsTheDraftBackIntoADraft() =
        runBlocking {
            draft(SpaceStatus.DRAFT)

            val outcome = starter(wifi = WIFI, context = RefusingContext(context)).start(id)

            assertEquals(StartOutcome.ServiceRefused, outcome)
            assertEquals(SpaceStatus.DRAFT.name, database.spaces().find(id.value)?.status)
        }

    @Test
    fun aResumeStartsTheServiceInResumeModeWithoutTouchingTheSpace() =
        runBlocking {
            draft(SpaceStatus.ACTIVE)

            val outcome = starter(wifi = WIFI).resume(id)

            assertEquals(StartOutcome.Started, outcome)
            val intent = Shadows.shadowOf(context as Application).nextStartedService
            assertTrue(intent.getBooleanExtra(SpaceServerService.EXTRA_RESUME, false))
            assertEquals(SpaceStatus.ACTIVE.name, database.spaces().find(id.value)?.status)
        }

    @Test
    fun aResumeWithoutWifiKeepsTheSpaceActive() =
        runBlocking {
            draft(SpaceStatus.ACTIVE)

            val outcome = starter(wifi = null).resume(id)

            assertEquals(StartOutcome.NoWifi, outcome)
            assertEquals(SpaceStatus.ACTIVE.name, database.spaces().find(id.value)?.status)
        }

    @Test
    fun aRefusedResumeKeepsTheSpaceActiveAndSaysSo() =
        runBlocking {
            draft(SpaceStatus.ACTIVE)

            val outcome = starter(wifi = WIFI, context = RefusingContext(context)).resume(id)

            assertEquals(StartOutcome.ServiceRefused, outcome)
            assertEquals(SpaceStatus.ACTIVE.name, database.spaces().find(id.value)?.status)
        }

    private fun starter(
        wifi: WifiAddress?,
        context: Context = this.context,
    ): SpaceStarter =
        SpaceStarter(
            context = context,
            runs = runs,
            storage = StorageProbe { FREE_BYTES },
            wifi = FixedWifi(wifi),
        )

    private suspend fun draft(
        status: SpaceStatus,
        limitBytes: Long = GIB,
    ) {
        database.spaces().upsert(
            SpaceEntity(
                id = id.value,
                name = "Test",
                colorIndex = 0,
                coverSha256 = null,
                lifetimePreset = "ONE_DAY",
                lifetimeUntil = null,
                retentionDays = 7,
                storageLimitBytes = limitBytes,
                status = status.name,
                createdAt = NOW,
                updatedAt = NOW,
                startedAt = if (status == SpaceStatus.ACTIVE) NOW else null,
                expiresAt = if (status == SpaceStatus.ACTIVE) NOW + DAY else null,
            ),
        )
    }

    /** Ein Kontext, bei dem Android den Vordergrund-Start verweigert (ForegroundServiceStartNotAllowedException). */
    private class RefusingContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("verweigert")
    }

    private class FixedWifi(
        private val address: WifiAddress?,
    ) : WifiNetworkProvider {
        override fun currentAddress(): WifiAddress? = address

        override fun changes(): Flow<WifiAddress?> = flowOf(address)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60L * 60L * 1000L
        const val GIB = 1024L * 1024L * 1024L
        const val FREE_BYTES = 100 * GIB
        val WIFI = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
    }
}
