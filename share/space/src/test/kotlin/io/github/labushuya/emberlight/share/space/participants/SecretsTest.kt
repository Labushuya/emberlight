package io.github.labushuya.emberlight.share.space.participants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {
    @Test
    fun generatedSecretsHaveTheDocumentedFormAndDiffer() {
        val first = Secrets.generate()
        val second = Secrets.generate()
        assertEquals(43, first.length)
        assertTrue(Secrets.isWellFormed(first))
        assertNotEquals(first, second)
    }

    @Test
    fun wrongFormIsRejectedBeforeComparison() {
        assertFalse(Secrets.isWellFormed(""))
        assertFalse(Secrets.isWellFormed("a".repeat(42)))
        assertFalse(Secrets.isWellFormed("a".repeat(43) + "="))
        assertFalse(Secrets.isWellFormed("a".repeat(42) + "+"))
    }

    @Test
    fun hashIsSha256HexInLowercase() {
        // SHA-256("abc") ist ein bekannter Testvektor.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Secrets.hashHex("abc"))
    }

    @Test
    fun hashComparisonMatchesOnlyEqualHashes() {
        val hash = Secrets.hashHex("abc")
        assertTrue(Secrets.hashesEqual(hash, hash))
        assertFalse(Secrets.hashesEqual(hash, Secrets.hashHex("abd")))
    }
}
