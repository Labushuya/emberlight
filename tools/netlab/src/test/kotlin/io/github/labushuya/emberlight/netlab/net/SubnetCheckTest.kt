package io.github.labushuya.emberlight.netlab.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubnetCheckTest {
    @Test
    fun sameSlash24MatchesAnyCandidate() {
        assertTrue(SubnetCheck.sameSlash24("192.168.43.1", listOf("10.0.0.5", "192.168.43.77")))
    }

    @Test
    fun otherSubnetDoesNotMatch() {
        assertFalse(SubnetCheck.sameSlash24("192.168.43.1", listOf("192.168.44.2", "10.0.0.5")))
    }

    @Test
    fun malformedAddressesNeverMatch() {
        assertFalse(SubnetCheck.sameSlash24("192.168.43", listOf("192.168.43.2")))
        assertFalse(SubnetCheck.sameSlash24("192.168.43.1", listOf("fe80::1")))
        assertFalse(SubnetCheck.sameSlash24("192.168.43.1", listOf("192.168.43.999")))
    }
}
