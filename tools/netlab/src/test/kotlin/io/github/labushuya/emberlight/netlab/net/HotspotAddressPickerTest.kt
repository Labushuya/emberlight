package io.github.labushuya.emberlight.netlab.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotspotAddressPickerTest {
    private fun c(
        name: String,
        address: String,
    ) = AddressCandidate(name, address)

    @Test
    fun prefersApInterfaceWithGatewayAddress() {
        val picked =
            HotspotAddressPicker.pick(
                listOf(c("wlan0", "192.168.1.23"), c("ap0", "192.168.49.1")),
            )
        assertEquals(c("ap0", "192.168.49.1"), picked)
    }

    @Test
    fun ignoresCellularInterfaces() {
        val ranked = HotspotAddressPicker.rank(listOf(c("rmnet_data0", "10.20.30.40"), c("wlan1", "192.168.43.1")))
        assertEquals(listOf(c("wlan1", "192.168.43.1")), ranked)
    }

    @Test
    fun ignoresPublicAndLoopbackAddresses() {
        assertFalse(HotspotAddressPicker.isPrivateIpv4("8.8.8.8"))
        assertFalse(HotspotAddressPicker.isPrivateIpv4("127.0.0.1"))
        assertFalse(HotspotAddressPicker.isPrivateIpv4("172.32.0.1"))
        assertTrue(HotspotAddressPicker.isPrivateIpv4("172.16.5.5"))
        assertTrue(HotspotAddressPicker.isPrivateIpv4("10.0.0.2"))
        assertFalse(HotspotAddressPicker.isPrivateIpv4("not-an-ip"))
    }

    @Test
    fun keepsOrderWhenScoresAreEqual() {
        val ranked = HotspotAddressPicker.rank(listOf(c("wlan0", "192.168.1.5"), c("wlan2", "192.168.2.5")))
        assertEquals("wlan0", ranked.first().interfaceName)
    }

    @Test
    fun noCandidateGivesNull() {
        assertNull(HotspotAddressPicker.pick(listOf(c("rmnet0", "10.1.1.1"))))
    }
}
