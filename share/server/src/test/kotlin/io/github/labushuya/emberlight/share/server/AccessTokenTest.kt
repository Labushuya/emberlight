package io.github.labushuya.emberlight.share.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTokenTest {
    @Test
    fun matchesOnlyTheExactValue() {
        val token = AccessToken.of("abcdefghijklmnop")
        assertTrue(token.matches("abcdefghijklmnop"))
        assertFalse(token.matches("abcdefghijklmnoq"))
        assertFalse(token.matches("abcdefghijklmno"))
        assertFalse(token.matches(""))
        assertFalse(token.matches(null))
    }

    @Test
    fun generatedTokensDifferAndAcceptThemselvesOnly() {
        // Der Wert bleibt privat. Gleichheit läuft über equals, dort werden die Bytes verglichen.
        val first = AccessToken.generate()
        val second = AccessToken.generate()
        assertNotEquals(first, second)
        assertEquals(first, first)
    }

    @Test
    fun rejectsShortAndWhitespaceValues() {
        assertTrue(runCatching { AccessToken.of("kurz") }.isFailure)
        assertTrue(runCatching { AccessToken.of("abcdefghij klmnop") }.isFailure)
    }

    @Test
    fun toStringNeverShowsTheValue() {
        val token = AccessToken.of("geheimgeheimgeheim")
        assertEquals("AccessToken(***)", token.toString())
        assertFalse(token.toString().contains("geheim"))
    }

    @Test
    fun bearerHeaderIsParsedCaseInsensitively() {
        assertEquals("abc", bearerToken("Bearer abc"))
        assertEquals("abc", bearerToken("bearer abc"))
        assertEquals(null, bearerToken("Basic abc"))
        assertEquals(null, bearerToken("Bearer "))
        assertEquals(null, bearerToken(null))
    }

    @Test
    fun serverConfigRejectsPortOutOfRange() {
        val token = AccessToken.of("abcdefghijklmnop")
        assertTrue(runCatching { ServerConfig(port = 65536, token = token) }.isFailure)
        assertTrue(runCatching { ServerConfig(port = -1, token = token) }.isFailure)
        assertEquals(0, ServerConfig(token = token).port)
    }
}
