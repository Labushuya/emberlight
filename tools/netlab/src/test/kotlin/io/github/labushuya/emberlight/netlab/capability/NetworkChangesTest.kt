package io.github.labushuya.emberlight.netlab.capability

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkChangesTest {
    private val wifi = NetworkFact(setOf(Transport.WIFI), validated = false, isDefault = false)
    private val mobile = NetworkFact(setOf(Transport.CELLULAR), validated = true, isDefault = true)

    @Test
    fun startStateIsDescribedWithoutPrevious() {
        val lines = NetworkChanges.describe(null, listOf(wifi, mobile))
        assertEquals(4, lines.size)
        assertEquals("Startzustand: WLAN aktiv", lines[0])
        assertEquals("Startzustand: Standardnetz Mobilfunk", lines[3])
    }

    @Test
    fun wifiLossIsReported() {
        assertEquals(listOf("WLAN getrennt"), NetworkChanges.describe(listOf(wifi, mobile), listOf(mobile)))
    }

    @Test
    fun mobileValidationChangesAreReported() {
        val unvalidated = mobile.copy(validated = false)
        assertEquals(
            listOf("Mobilfunk nicht mehr validiert"),
            NetworkChanges.describe(listOf(mobile), listOf(unvalidated)),
        )
    }

    @Test
    fun vpnAndDefaultChangesAreReported() {
        val vpn = NetworkFact(setOf(Transport.VPN), validated = true, isDefault = true)
        // Das Mobilfunknetz ist verschwunden, damit ist auch seine Validierung weg. Das wird ehrlich mitgemeldet.
        assertEquals(
            listOf("Mobilfunk nicht mehr validiert", "VPN aktiv", "Standardnetz: VPN"),
            NetworkChanges.describe(listOf(mobile), listOf(vpn)),
        )
    }

    @Test
    fun noChangeGivesNoEvents() {
        assertEquals(emptyList(), NetworkChanges.describe(listOf(wifi, mobile), listOf(wifi, mobile)))
    }
}
