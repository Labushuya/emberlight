@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.labushuya.emberlight.feature.space.setup

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.run.SpaceStarter
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.server.WifiAddress
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.SystemRunClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/**
 * Einrichtung eines Entwurfs (Paket 3.3): Prüfung der Punkte, Selbstbestätigung, Berechtigungs-Merkmal und der Start mit
 * seinen Fehlern. Die Datenbank ist echt, der Start geht über den echten [SpaceStarter] mit gefälschtem WLAN.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceSetupViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = SpaceId.generate()
    private lateinit var database: SpacesDatabase
    private lateinit var store: SetupStore
    private var wifi: WifiAddress? = WIFI

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = SpacesDatabase.open(context, folder.newFolder("spaces"))
        store = SetupStore(context)
        store.setOemConfirmed(false)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun theChecklistIsBlockedUntilTheLocalNetworkPermissionIsGranted() {
        val vm = viewModel(id)

        vm.refresh(FakeEnvironment(sdkInt = 37, targetSdk = 37, localNetworkGranted = false))
        assertTrue(vm.state.value.gate.blocked)

        vm.refresh(FakeEnvironment(sdkInt = 37, targetSdk = 37, localNetworkGranted = true))
        assertFalse(vm.state.value.gate.blocked)
    }

    @Test
    fun onAnOlderSystemTheLocalNetworkItemDoesNotBlockTheStart() {
        val vm = viewModel(id)

        vm.refresh(FakeEnvironment(sdkInt = 36, targetSdk = 36, localNetworkGranted = false))

        assertFalse(vm.state.value.gate.blocked)
        assertTrue(
            vm.state.value.items
                .isNotEmpty(),
        )
    }

    @Test
    fun confirmingTheOemSettingIsStoredAndAskedAgainLater() {
        val vm = viewModel(id)

        vm.confirmOem(true)

        assertTrue(store.oemConfirmed())
        vm.confirmOem(false)
        assertFalse(store.oemConfirmed())
    }

    @Test
    fun aPermissionRequestIsRememberedForTheNextCheck() {
        val vm = viewModel(id)

        vm.markAsked("android.permission.POST_NOTIFICATIONS")

        assertTrue(store.wasAsked("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun startWithoutAValidSpaceDoesNothing() {
        var started = false

        viewModel(null).start { started = true }

        assertFalse(started)
    }

    @Test
    fun aStartWithoutWifiShowsTheReasonAndDoesNotCallBack() =
        runBlocking {
            seed()
            wifi = null
            val vm = viewModel(id)
            var started = false

            vm.start { started = true }
            awaitTrue { !vm.state.value.starting && vm.state.value.outcome != null }

            assertEquals(StartOutcome.NoWifi, vm.state.value.outcome)
            assertFalse(started)
            assertEquals(SpaceStatus.DRAFT.name, database.spaces().find(id.value)?.status)
        }

    @Test
    fun aSuccessfulStartCallsBackAndLeavesNoError() =
        runBlocking {
            seed()
            val vm = viewModel(id)
            var started = false

            vm.start { started = true }
            awaitTrue { started }

            assertNull(vm.state.value.outcome)
            assertFalse(vm.state.value.starting)
        }

    @Test
    fun dismissingTheErrorClearsIt() =
        runBlocking {
            seed()
            wifi = null
            val vm = viewModel(id)
            vm.start {}
            awaitTrue { vm.state.value.outcome != null }

            vm.dismissOutcome()

            assertNull(vm.state.value.outcome)
        }

    private fun viewModel(spaceId: SpaceId?): SpaceSetupViewModel {
        val handle = SavedStateHandle(listOfNotNull(spaceId?.let { "id" to it.value }).toMap())
        val starter =
            SpaceStarter(
                context = context,
                runs = SpaceRunRepository(database, SystemRunClock),
                storage = StorageProbe { FREE_BYTES },
                wifi = FixedWifi { wifi },
            )
        return SpaceSetupViewModel(savedStateHandle = handle, starter = starter, store = store)
    }

    private suspend fun seed() {
        database.spaces().upsert(
            SpaceEntity(
                id = id.value,
                name = "Test",
                colorIndex = 0,
                coverSha256 = null,
                lifetimePreset = "ONE_DAY",
                lifetimeUntil = null,
                retentionDays = 7,
                storageLimitBytes = GIB,
                status = SpaceStatus.DRAFT.name,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    /** Wartet, bis die Bedingung gilt. Die Zeitgrenze fängt einen ausbleibenden Schritt ab. */
    private suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    /** Die Prüfungen eines Geräts, wie sie die Einrichtung braucht. Felder einzeln einstellbar. */
    private class FakeEnvironment(
        override val sdkInt: Int,
        override val targetSdk: Int,
        override val localNetworkGranted: Boolean,
        override val notificationsGranted: Boolean = true,
        override val batteryIgnored: Boolean = true,
        override val backgroundRestricted: Boolean = false,
        override val standbyRestricted: Boolean = false,
        override val oemConfirmed: Boolean = false,
    ) : SetupEnvironment {
        override fun wasAsked(permission: String): Boolean = false

        override fun canAskAgain(permission: String): Boolean = true
    }

    private class FixedWifi(
        private val current: () -> WifiAddress?,
    ) : WifiNetworkProvider {
        override fun currentAddress(): WifiAddress? = current()

        override fun changes(): Flow<WifiAddress?> = flowOf(current())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val GIB = 1024L * 1024L * 1024L
        const val FREE_BYTES = 100 * GIB
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        val WIFI = WifiAddress("192.168.1.20", 24, gateway = "192.168.1.1")
    }
}
