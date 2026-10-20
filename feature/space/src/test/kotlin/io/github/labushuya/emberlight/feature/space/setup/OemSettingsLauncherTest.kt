package io.github.labushuya.emberlight.feature.space.setup

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Hersteller-Freigabe (Paket 3.3, ADR 0017): Kandidaten, Fallback-Kette und Erkennung ohne echtes Gerät. */
@RunWith(RobolectricTestRunner::class)
class OemSettingsLauncherTest {
    /** Fake für den Paketzugriff. [resolvable] sind die Komponenten, die `resolveActivity` findet. */
    private class FakePort(
        private val resolvable: Set<ComponentName> = emptySet(),
        private val failing: Map<ComponentName, Throwable> = emptyMap(),
        private val failingAppDetails: Throwable? = null,
        private val failingBatteryList: Throwable? = null,
    ) : OemPort {
        val started = mutableListOf<Intent>()
        override val ownPackage: String = OWN_PACKAGE

        override fun resolves(component: ComponentName): Boolean = component in resolvable

        override fun start(intent: Intent) {
            val component = intent.component
            when {
                component != null && component in failing -> throw failing.getValue(component)
                intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS && failingAppDetails != null ->
                    throw failingAppDetails
                intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS && failingBatteryList != null ->
                    throw failingBatteryList
            }
            started += intent
        }
    }

    private val honorFirst = OemFamily.HONOR.candidates[0]
    private val honorSecond = OemFamily.HONOR.candidates[1]

    @Test
    fun aResolvableCandidateOpensTheVendorScreen() {
        val port = FakePort(resolvable = setOf(honorFirst))
        val result = OemSettingsLauncher(port).open(OemFamily.HONOR)
        assertEquals(OemOpenResult.Vendor(honorFirst), result)
        assertEquals(honorFirst, port.started.single().component)
    }

    @Test
    fun anUnresolvableCandidateIsSkipped() {
        val port = FakePort(resolvable = setOf(honorSecond))
        assertEquals(OemOpenResult.Vendor(honorSecond), OemSettingsLauncher(port).open(OemFamily.HONOR))
    }

    @Test
    fun aCandidateThatThrowsActivityNotFoundIsSkipped() {
        val port =
            FakePort(
                resolvable = setOf(honorFirst, honorSecond),
                failing = mapOf(honorFirst to ActivityNotFoundException()),
            )
        assertEquals(OemOpenResult.Vendor(honorSecond), OemSettingsLauncher(port).open(OemFamily.HONOR))
    }

    @Test
    fun aCandidateThatThrowsSecurityExceptionIsSkipped() {
        val port =
            FakePort(
                resolvable = setOf(honorFirst, honorSecond),
                failing = mapOf(honorFirst to SecurityException("nicht exportiert")),
            )
        assertEquals(OemOpenResult.Vendor(honorSecond), OemSettingsLauncher(port).open(OemFamily.HONOR))
    }

    @Test
    fun withoutAVendorScreenTheAppInfoOpensForOwnPackage() {
        val port = FakePort()
        assertEquals(OemOpenResult.AppDetails, OemSettingsLauncher(port).open(OemFamily.HONOR))
        val intent = port.started.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:$OWN_PACKAGE", intent.dataString)
    }

    @Test
    fun whenTheAppInfoIsUnavailableTheBatteryListOpens() {
        val port = FakePort(failingAppDetails = ActivityNotFoundException())
        assertEquals(OemOpenResult.BatteryList, OemSettingsLauncher(port).open(OemFamily.HONOR))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, port.started.single().action)
    }

    @Test
    fun ifEverythingFailsTheResultSaysSo() {
        val port =
            FakePort(
                resolvable = setOf(honorFirst),
                failing = mapOf(honorFirst to ActivityNotFoundException()),
                failingAppDetails = SecurityException("gesperrt"),
                failingBatteryList = ActivityNotFoundException(),
            )
        assertEquals(OemOpenResult.Failed, OemSettingsLauncher(port).open(OemFamily.HONOR))
        assertTrue(port.started.isEmpty())
    }

    @Test
    fun anUnknownManufacturerGoesStraightToTheFallbackChain() {
        val port = FakePort()
        assertEquals(OemOpenResult.AppDetails, OemSettingsLauncher(port).open(OemFamily.OTHER))
    }

    @Test
    fun theHonorListStartsWithTheUnclaimedHonorComponent() {
        assertEquals(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            OemFamily.HONOR.candidates.first(),
        )
    }

    @Test
    fun everyCandidatePackageIsVisibleThroughQueries() {
        val candidatePackages = OemFamily.entries.flatMap { family -> family.candidates.map { it.packageName } }.toSet()
        assertTrue(candidatePackages.all { it in OemFamily.PACKAGES })
    }

    @Test
    fun theManufacturerIsDetectedFromManufacturerAndBrand() {
        assertEquals(OemFamily.HONOR, OemFamily.detect("HONOR", "HONOR"))
        assertEquals(OemFamily.HUAWEI, OemFamily.detect("HUAWEI", "HUAWEI"))
        assertEquals(OemFamily.XIAOMI, OemFamily.detect("Xiaomi", "Redmi"))
        assertEquals(OemFamily.XIAOMI, OemFamily.detect("Xiaomi", "POCO"))
        assertEquals(OemFamily.SAMSUNG, OemFamily.detect("samsung", "samsung"))
        assertEquals(OemFamily.ONEPLUS, OemFamily.detect("OnePlus", "OnePlus"))
        assertEquals(OemFamily.OPPO, OemFamily.detect("OPPO", "realme"))
        assertEquals(OemFamily.VIVO, OemFamily.detect("vivo", "iQOO"))
        assertEquals(OemFamily.ASUS, OemFamily.detect("asus", "asus"))
        assertEquals(OemFamily.NOKIA, OemFamily.detect("HMD Global", "Nokia"))
        assertEquals(OemFamily.MOTOROLA, OemFamily.detect("motorola", "motorola"))
        assertEquals(OemFamily.SONY, OemFamily.detect("Sony", "Sony"))
        assertEquals(OemFamily.PIXEL, OemFamily.detect("Google", "google"))
        assertEquals(OemFamily.OTHER, OemFamily.detect("Fairphone", "Fairphone"))
    }

    private companion object {
        const val OWN_PACKAGE = "io.github.labushuya.emberlight"
    }
}
