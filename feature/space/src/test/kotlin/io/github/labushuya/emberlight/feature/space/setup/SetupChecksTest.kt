package io.github.labushuya.emberlight.feature.space.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einrichtung (Paket 3.3): Punkte, Berechtigungszustände für API 36 und 37, Schranke vor dem Start. */
class SetupChecksTest {
    private val notifications = SetupPermissions.NOTIFICATIONS
    private val localNetwork = SetupPermissions.LOCAL_NETWORK

    /** Umgebung mit allem erfüllt, außer was ein Test ändert. */
    private fun env(
        sdkInt: Int = 37,
        targetSdk: Int = 37,
        notificationsGranted: Boolean = true,
        localNetworkGranted: Boolean = true,
        batteryIgnored: Boolean = true,
        backgroundRestricted: Boolean = false,
        standbyRestricted: Boolean = false,
        oemConfirmed: Boolean = true,
        asked: Set<String> = emptySet(),
        askAgain: Set<String> = emptySet(),
    ): SetupEnvironment =
        object : SetupEnvironment {
            override val sdkInt = sdkInt
            override val targetSdk = targetSdk
            override val notificationsGranted = notificationsGranted
            override val localNetworkGranted = localNetworkGranted
            override val batteryIgnored = batteryIgnored
            override val backgroundRestricted = backgroundRestricted
            override val standbyRestricted = standbyRestricted
            override val oemConfirmed = oemConfirmed

            override fun wasAsked(permission: String): Boolean = permission in asked

            override fun canAskAgain(permission: String): Boolean = permission in askAgain
        }

    private fun List<SetupItem>.item(kind: CheckKind): SetupItem? = firstOrNull { it.kind == kind }

    @Test
    fun localNetworkIsHiddenBelowApi37() {
        val items = SetupChecks.items(env(sdkInt = 36, localNetworkGranted = false))
        assertNull(items.item(CheckKind.LOCAL_NETWORK))
        assertFalse(SetupChecks.gate(items).blocked)
    }

    @Test
    fun localNetworkIsHiddenWhenTheAppStillTargetsOlderSdk() {
        // API 37 allein reicht nicht: Ab targetSdk 37 gilt die Laufzeit-Berechtigung (ADR 0012).
        val items = SetupChecks.items(env(sdkInt = 37, targetSdk = 36, localNetworkGranted = false))
        assertNull(items.item(CheckKind.LOCAL_NETWORK))
    }

    @Test
    fun aNeverAskedLocalNetworkPermissionIsOpenAndBlocksTheStart() {
        val items = SetupChecks.items(env(localNetworkGranted = false))
        assertEquals(CheckStatus.OPEN, items.item(CheckKind.LOCAL_NETWORK)?.status)
        assertTrue(items.item(CheckKind.LOCAL_NETWORK)?.required == true)
        assertTrue(SetupChecks.gate(items).blocked)
    }

    @Test
    fun aDeniedLocalNetworkPermissionCanBeAskedAgain() {
        val items =
            SetupChecks.items(
                env(localNetworkGranted = false, asked = setOf(localNetwork), askAgain = setOf(localNetwork)),
            )
        assertEquals(CheckStatus.DENIED, items.item(CheckKind.LOCAL_NETWORK)?.status)
        assertTrue(SetupChecks.gate(items).blocked)
    }

    @Test
    fun aPermanentlyDeniedLocalNetworkPermissionLeadsToSettingsAndBlocks() {
        // „Nicht mehr fragen“: asked, aber keine erneute Anfrage. Die Oberfläche zeigt dann den Weg zu den App-Einstellungen.
        val items = SetupChecks.items(env(localNetworkGranted = false, asked = setOf(localNetwork)))
        assertEquals(CheckStatus.PERMANENTLY_DENIED, items.item(CheckKind.LOCAL_NETWORK)?.status)
        assertTrue(SetupChecks.gate(items).blocked)
    }

    @Test
    fun aGrantedLocalNetworkPermissionUnblocksTheStart() {
        val items = SetupChecks.items(env(localNetworkGranted = true))
        assertEquals(CheckStatus.MET, items.item(CheckKind.LOCAL_NETWORK)?.status)
        assertFalse(SetupChecks.gate(items).blocked)
    }

    @Test
    fun notificationsAreNotRequiredBeforeApi33() {
        val items =
            SetupChecks.items(
                env(sdkInt = 32, targetSdk = 33, notificationsGranted = false, localNetworkGranted = true),
            )
        assertEquals(CheckStatus.MET, items.item(CheckKind.NOTIFICATIONS)?.status)
    }

    @Test
    fun missingNotificationsAreRecommendedButDoNotBlock() {
        val items =
            SetupChecks.items(
                env(notificationsGranted = false, asked = setOf(notifications), askAgain = setOf(notifications)),
            )
        assertEquals(CheckStatus.DENIED, items.item(CheckKind.NOTIFICATIONS)?.status)
        val gate = SetupChecks.gate(items)
        assertFalse(gate.blocked)
        assertTrue(CheckKind.NOTIFICATIONS in gate.recommendedOpen)
    }

    @Test
    fun batteryAndManufacturerAreRecommendedWhenOpen() {
        val items = SetupChecks.items(env(batteryIgnored = false, oemConfirmed = false))
        val gate = SetupChecks.gate(items)
        assertFalse(gate.blocked)
        assertEquals(
            listOf(CheckKind.BATTERY, CheckKind.OEM_BACKGROUND),
            gate.recommendedOpen.filter { it in OPTIONAL },
        )
    }

    @Test
    fun theManufacturerStepIsSelfConfirmed() {
        assertEquals(
            CheckStatus.CONFIRMED,
            SetupChecks.items(env(oemConfirmed = true)).item(CheckKind.OEM_BACKGROUND)?.status,
        )
        assertEquals(
            CheckStatus.OPEN,
            SetupChecks.items(env(oemConfirmed = false)).item(CheckKind.OEM_BACKGROUND)?.status,
        )
    }

    @Test
    fun theBackgroundStepAppearsOnlyWhenRestricted() {
        assertNull(SetupChecks.items(env()).item(CheckKind.BACKGROUND_RESTRICTED))
        assertEquals(
            CheckStatus.OPEN,
            SetupChecks.items(env(backgroundRestricted = true)).item(CheckKind.BACKGROUND_RESTRICTED)?.status,
        )
        assertTrue(SetupChecks.items(env(standbyRestricted = true)).any { it.kind == CheckKind.BACKGROUND_RESTRICTED })
    }

    @Test
    fun theChargerStepIsInformationOnly() {
        val items = SetupChecks.items(env())
        assertEquals(CheckStatus.INFO, items.item(CheckKind.CHARGER)?.status)
        assertFalse(items.item(CheckKind.CHARGER)?.required == true)
    }

    private companion object {
        val OPTIONAL = setOf(CheckKind.BATTERY, CheckKind.OEM_BACKGROUND)
    }
}
