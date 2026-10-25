package io.github.labushuya.emberlight.netlab.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitiesTest {
    private fun facts(
        sdk: Int = 34,
        staAp: Boolean? = true,
        staLocal: Boolean? = true,
        band5: Boolean? = true,
        networks: List<NetworkFact> = emptyList(),
        permissions: List<PermissionFact> = emptyList(),
        location: Boolean? = true,
    ) = DeviceFacts(
        sdkInt = sdk,
        manufacturer = "Acme",
        model = "Phone 1",
        staApConcurrency = staAp,
        staConcurrencyLocalOnly = staLocal,
        band5GHz = band5,
        networks = networks,
        permissions = permissions,
        locationServicesEnabled = location,
    )

    @Test
    fun missingApiIsUnavailableNotNo() {
        val summary = Capabilities.summarize(facts(staAp = null, staLocal = null, band5 = null, location = null))
        assertEquals(Flag.UNAVAILABLE, summary.staAp)
        assertEquals(Flag.UNAVAILABLE, summary.staLocalOnly)
        assertEquals(Flag.UNAVAILABLE, summary.band5GHz)
        assertEquals(Flag.UNAVAILABLE, summary.locationServices)
    }

    @Test
    fun supportedAndUnsupportedAreMappedDirectly() {
        val summary = Capabilities.summarize(facts(staAp = false, staLocal = true))
        assertEquals(Flag.NO, summary.staAp)
        assertEquals(Flag.YES, summary.staLocalOnly)
    }

    @Test
    fun defaultNetworkIsSortedFirstAndFound() {
        val mobile = NetworkFact(setOf(Transport.CELLULAR), validated = true, isDefault = true)
        val wifi = NetworkFact(setOf(Transport.WIFI), validated = false, isDefault = false)
        val summary = Capabilities.summarize(facts(networks = listOf(wifi, mobile)))
        assertEquals(mobile, summary.networks.first())
        assertEquals(mobile, summary.defaultNetwork)
    }

    @Test
    fun cellularNeedsValidationToCount() {
        val unvalidated =
            Capabilities.summarize(
                facts(networks = listOf(NetworkFact(setOf(Transport.CELLULAR), validated = false, isDefault = false))),
            )
        assertFalse(unvalidated.cellularValidated)

        val validated =
            Capabilities.summarize(
                facts(networks = listOf(NetworkFact(setOf(Transport.CELLULAR), validated = true, isDefault = false))),
            )
        assertTrue(validated.cellularValidated)
    }

    @Test
    fun detectsWifiAndVpnTransports() {
        val summary =
            Capabilities.summarize(
                facts(
                    networks =
                        listOf(
                            NetworkFact(setOf(Transport.WIFI), validated = false, isDefault = false),
                            NetworkFact(setOf(Transport.VPN, Transport.WIFI), validated = true, isDefault = true),
                        ),
                ),
            )
        assertTrue(summary.wifiActive)
        assertTrue(summary.vpnActive)
    }

    @Test
    fun notApplicablePermissionsAreHidden() {
        val summary =
            Capabilities.summarize(
                facts(
                    permissions =
                        listOf(
                            PermissionFact("ACCESS_FINE_LOCATION", granted = true, applies = true),
                            PermissionFact("NEARBY_WIFI_DEVICES", granted = false, applies = false),
                        ),
                ),
            )
        assertEquals(listOf("ACCESS_FINE_LOCATION"), summary.permissions.map { it.name })
    }

    @Test
    fun notesWarnAboutMissingConcurrencyAndVpn() {
        val summary =
            Capabilities.summarize(
                facts(
                    staAp = false,
                    networks = listOf(NetworkFact(setOf(Transport.VPN), validated = true, isDefault = true)),
                ),
            )
        val notes = Capabilities.notes(summary)
        assertTrue(notes.any { it.startsWith("STA+AP-Betrieb wird vom Gerät nicht unterstützt") })
        assertTrue(notes.any { it.startsWith("VPN aktiv") })
    }

    @Test
    fun noNotesWhenEverythingIsSupported() {
        assertEquals(emptyList(), Capabilities.notes(Capabilities.summarize(facts())))
    }
}
