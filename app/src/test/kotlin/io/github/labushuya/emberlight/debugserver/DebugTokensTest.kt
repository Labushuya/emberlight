package io.github.labushuya.emberlight.debugserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTokensTest {
    // SHA-256 über die UTF-8-Bytes von "host-test-token" bzw. "guest-test-token" (sha256sum-Ausgabe).
    private val host = "1ecf73525cdb0100111fd4a8d8b88d21cd8562902bcdef91d1c862e1642f4279"
    private val guest = "2b2d8e931fd3ecfb0a60fc4c8f8f9ba4ddef76fbe8150cd065f2a59a8e584989"

    @Test
    fun acceptsExactly64LowercaseHexCharacters() {
        assertTrue(DebugTokens.isValid(host))
        assertTrue(DebugTokens.isValid("0".repeat(64)))
    }

    @Test
    fun rejectsWrongLengthUppercaseNonHexWhitespaceAndMissing() {
        listOf(
            null,
            "",
            host.dropLast(1),
            host + "0",
            host.uppercase(),
            host.replaceRange(0, 1, "g"),
            " $host",
            "$host\n",
            "-$host".take(64),
        ).forEach { candidate ->
            assertFalse("abgelehnt erwartet: ${candidate?.length}", DebugTokens.isValid(candidate))
        }
    }

    @Test
    fun acceptsHostOnly() {
        assertEquals(TokenCheck.Accepted(host = host, guest = null), DebugTokens.check(host, null))
    }

    @Test
    fun acceptsHostAndGuestAndPassesHashesThroughUnchanged() {
        assertEquals(TokenCheck.Accepted(host = host, guest = guest), DebugTokens.check(host, guest))
    }

    @Test
    fun rejectsMissingHost() {
        assertRejected(DebugTokens.check(null, null))
        assertRejected(DebugTokens.check(null, guest))
    }

    @Test
    fun rejectsInvalidHost() {
        assertRejected(DebugTokens.check("", null))
        assertRejected(DebugTokens.check(host.uppercase(), null))
        assertRejected(DebugTokens.check(host.dropLast(2), guest))
    }

    @Test
    fun rejectsInvalidGuestIncludingEmptyString() {
        assertRejected(DebugTokens.check(host, ""))
        assertRejected(DebugTokens.check(host, guest.dropLast(1)))
        assertRejected(DebugTokens.check(host, guest.uppercase()))
    }

    @Test
    fun rejectsEqualHostAndGuestHash() {
        assertRejected(DebugTokens.check(host, host))
    }

    @Test
    fun rejectionReasonsNeverContainTheHashValues() {
        val invalid = host.uppercase()
        listOf(
            DebugTokens.check(invalid, guest),
            DebugTokens.check(host, invalid),
            DebugTokens.check(host, host),
        ).forEach { result ->
            val reason = (result as TokenCheck.Rejected).reason
            assertFalse(reason.contains(invalid))
            assertFalse(reason.contains(host))
            assertFalse(reason.contains(guest))
        }
    }

    private fun assertRejected(result: TokenCheck) {
        assertTrue("abgelehnt erwartet, war $result", result is TokenCheck.Rejected)
    }
}
