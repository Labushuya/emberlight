package io.github.labushuya.emberlight.debugserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTokensTest {
    private val host = "0123456789abcdef".repeat(4)
    private val guest = "fedcba9876543210".repeat(4)

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
    fun acceptsHostAndGuest() {
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
    fun rejectsEqualHostAndGuest() {
        assertRejected(DebugTokens.check(host, host))
    }

    @Test
    fun rejectionReasonsNeverContainTheTokenValues() {
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

    @Test
    fun hashIsSha256HexInLowercase() {
        // SHA-256("abc") nach FIPS 180-2, Testvektor.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DebugTokens.sha256Hex("abc"),
        )
        assertTrue(Regex("[0-9a-f]{64}").matches(DebugTokens.sha256Hex(host)))
    }

    private fun assertRejected(result: TokenCheck) {
        assertTrue("abgelehnt erwartet, war $result", result is TokenCheck.Rejected)
    }
}
