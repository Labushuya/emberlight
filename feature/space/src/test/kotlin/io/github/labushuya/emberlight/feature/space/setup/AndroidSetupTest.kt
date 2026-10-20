package io.github.labushuya.emberlight.feature.space.setup

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Android-Umsetzung der Einrichtung (ADR 0017): Speicher der Schalter, Prüfungen gegen das Gerät und der Start der
 * Hersteller-Einstellung. Auf dem Test-Gerät gibt es keine echten Einstellungen, der Test prüft die Verdrahtung.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSetupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private lateinit var store: SetupStore

    @Before
    fun setUp() {
        store = SetupStore(context)
    }

    @Test
    fun theOemSelfConfirmationStartsUnconfirmedAndIsStored() {
        assertFalse(store.oemConfirmed())

        store.setOemConfirmed(true)

        assertTrue(store.oemConfirmed())
    }

    @Test
    fun aPermissionIsRememberedAsAskedOnlyForThatPermission() {
        assertFalse(store.wasAsked(SetupPermissions.LOCAL_NETWORK))

        store.markAsked(SetupPermissions.LOCAL_NETWORK)

        assertTrue(store.wasAsked(SetupPermissions.LOCAL_NETWORK))
        assertFalse(store.wasAsked(SetupPermissions.NOTIFICATIONS))
    }

    @Test
    fun theEnvironmentReportsTheSystemAndTheStoredSwitches() {
        val env = environment(canAsk = true)

        assertEquals(android.os.Build.VERSION.SDK_INT, env.sdkInt)
        assertEquals(context.applicationInfo.targetSdkVersion, env.targetSdk)
        store.setOemConfirmed(true)
        assertTrue(env.oemConfirmed)
        store.markAsked(SetupPermissions.LOCAL_NETWORK)
        assertTrue(env.wasAsked(SetupPermissions.LOCAL_NETWORK))
    }

    @Test
    fun askingAgainFollowsTheCallerSupplyingTheRationaleState() {
        assertTrue(environment(canAsk = true).canAskAgain(SetupPermissions.LOCAL_NETWORK))
        assertFalse(environment(canAsk = false).canAskAgain(SetupPermissions.LOCAL_NETWORK))
    }

    @Test
    fun theLocalNetworkPermissionIsReadFromTheGrantedPermissions() {
        assertFalse(environment(canAsk = true).localNetworkGranted)

        Shadows.shadowOf(application).grantPermissions(SetupPermissions.LOCAL_NETWORK)

        assertTrue(environment(canAsk = true).localNetworkGranted)
    }

    @Test
    fun theBatteryExemptionIsReadFromThePowerManager() {
        val power = context.getSystemService(PowerManager::class.java)
        Shadows.shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(environment(canAsk = true).batteryIgnored)

        Shadows.shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(environment(canAsk = true).batteryIgnored)
    }

    @Test
    fun theOemPortKnowsTheOwnPackage() {
        assertEquals(context.packageName, ContextOemPort(context).ownPackage)
    }

    @Test
    fun theOemPortStartsTheActivityAsANewTask() {
        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)

        ContextOemPort(context).start(intent)

        val started = checkNotNull(Shadows.shadowOf(application).nextStartedActivity)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun environment(canAsk: Boolean): AndroidSetupEnvironment =
        AndroidSetupEnvironment(context = context, store = store, canAskAgain = { canAsk })
}
