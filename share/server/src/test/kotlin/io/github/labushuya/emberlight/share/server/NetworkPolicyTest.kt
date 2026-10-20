package io.github.labushuya.emberlight.share.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WLAN-Entscheidung (ADR 0013, ADR 0017): Dasselbe Netz heißt gleiche IPv4, gleiches Präfix und gleiches Gateway. */
class NetworkPolicyTest {
    private val home = WifiAddress(ipv4 = "192.168.1.20", prefixLength = 24, gateway = "192.168.1.1")

    @Test
    fun theSameAddressPrefixAndGatewayIsTheSameNetwork() {
        assertTrue(NetworkPolicy.sameNetwork(home, home.copy()))
    }

    @Test
    fun anotherAddressInTheSameSubnetIsNotTheSameNetwork() {
        // Ein DHCP-Wechsel in demselben WLAN stoppt die Annahme: Der Server bindet nur an die alte Adresse.
        assertFalse(NetworkPolicy.sameNetwork(home, home.copy(ipv4 = "192.168.1.21")))
    }

    @Test
    fun aChangedPrefixOrGatewayIsAnotherNetwork() {
        assertFalse(NetworkPolicy.sameNetwork(home, home.copy(prefixLength = 16)))
        assertFalse(NetworkPolicy.sameNetwork(home, home.copy(gateway = "192.168.1.254")))
        assertFalse(NetworkPolicy.sameNetwork(home, home.copy(gateway = null)))
    }

    @Test
    fun aRunningServerKeepsItsNetworkAndPausesOnLossOrChange() {
        assertEquals(NetworkAction.Keep, NetworkPolicy.whileRunning(home, home.copy()))
        assertEquals(NetworkAction.Lost, NetworkPolicy.whileRunning(home, null))
        val other = home.copy(ipv4 = "10.0.0.5", gateway = "10.0.0.1")
        assertEquals(NetworkAction.Changed(other), NetworkPolicy.whileRunning(home, other))
    }

    @Test
    fun aPausedServerResumesOnTheSameNetworkOnly() {
        assertEquals(NetworkAction.Resume(home), NetworkPolicy.whilePaused(home, home.copy()))
        val other = home.copy(ipv4 = "10.0.0.5", gateway = "10.0.0.1")
        assertEquals(NetworkAction.Changed(other), NetworkPolicy.whilePaused(home, other))
    }

    @Test
    fun aPausedServerStaysPausedWithoutWifi() {
        assertEquals(NetworkAction.Keep, NetworkPolicy.whilePaused(home, null))
    }
}
