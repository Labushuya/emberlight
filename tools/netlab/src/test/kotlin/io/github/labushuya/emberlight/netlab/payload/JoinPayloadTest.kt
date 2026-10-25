package io.github.labushuya.emberlight.netlab.payload

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JoinPayloadTest {
    private val token = "abcDEF_123-xyzABCDEF0123"
    private val sample = JoinPayload("EL-Hotspot", "geheimes1", "192.168.49.1", 8787, token)

    @Test
    fun roundTripKeepsAllFields() {
        val decoded = JoinPayload.decode(sample.encode())
        assertEquals(JoinPayload.DecodeResult.Success(sample), decoded)
    }

    @Test
    fun roundTripKeepsSpecialCharacters() {
        val payload = sample.copy(ssid = "Netz & Co=Test+Ä ü", passphrase = "pa ss&wo=rd+%#")
        val decoded = JoinPayload.decode(payload.encode())
        assertEquals(JoinPayload.DecodeResult.Success(payload), decoded)
    }

    @Test
    fun encodedStartsWithSchemeAndVersion() {
        val encoded = sample.encode()
        assertTrue(encoded.startsWith("emberlight-netlab://join?v=1&"))
    }

    @Test
    fun rejectsOtherScheme() {
        val result = JoinPayload.decode("https://example.org/join?v=1")
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.WRONG_FORMAT), result)
    }

    @Test
    fun rejectsOtherVersion() {
        val result = JoinPayload.decode(sample.encode().replace("v=1", "v=2"))
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.WRONG_VERSION), result)
    }

    @Test
    fun rejectsMissingField() {
        val result = JoinPayload.decode(sample.encode().replace("&token=", "&tok="))
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.MISSING_FIELD), result)
    }

    @Test
    fun rejectsDuplicateKey() {
        val result = JoinPayload.decode(sample.encode() + "&port=9000")
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.WRONG_FORMAT), result)
    }

    @Test
    fun rejectsBrokenEncoding() {
        val result = JoinPayload.decode("emberlight-netlab://join?v=1&ssid=%ZZ")
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.WRONG_FORMAT), result)
    }

    @Test
    fun rejectsHostnameInsteadOfIpv4() {
        val result = JoinPayload.decode(sample.copy(host = "gastgeber.local").encode())
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.INVALID_HOST), result)
    }

    @Test
    fun rejectsOutOfRangeOctet() {
        val result = JoinPayload.decode(sample.copy(host = "192.168.49.256").encode())
        assertEquals(JoinPayload.DecodeResult.Failure(PayloadError.INVALID_HOST), result)
    }

    @Test
    fun rejectsPrivilegedAndTooHighPorts() {
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_PORT),
            JoinPayload.decode(sample.copy(port = 80).encode()),
        )
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_PORT),
            JoinPayload.decode(sample.encode().replace("port=8787", "port=70000")),
        )
    }

    @Test
    fun rejectsShortPassphraseAndLongPassphrase() {
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_PASSPHRASE),
            JoinPayload.decode(sample.copy(passphrase = "kurz").encode()),
        )
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_PASSPHRASE),
            JoinPayload.decode(sample.copy(passphrase = "x".repeat(64)).encode()),
        )
    }

    @Test
    fun rejectsSsidOverThirtyTwoBytes() {
        val tooLong = "ä".repeat(17)
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_SSID),
            JoinPayload.decode(sample.copy(ssid = tooLong).encode()),
        )
    }

    @Test
    fun rejectsEmptySsid() {
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_SSID),
            JoinPayload.decode(sample.copy(ssid = "").encode()),
        )
    }

    @Test
    fun rejectsTokenWithForbiddenCharacters() {
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_TOKEN),
            JoinPayload.decode(sample.copy(token = "abc def/ghi+jkl=mno").encode()),
        )
        assertEquals(
            JoinPayload.DecodeResult.Failure(PayloadError.INVALID_TOKEN),
            JoinPayload.decode(sample.copy(token = "kurz").encode()),
        )
    }

    @Test
    fun trimsSurroundingWhitespaceOfScannedText() {
        val decoded = JoinPayload.decode("  ${sample.encode()}\n")
        assertIs<JoinPayload.DecodeResult.Success>(decoded)
    }

    @Test
    fun ipv4ValidationCoversEdgeCases() {
        assertTrue(JoinPayload.isIpv4("0.0.0.0"))
        assertTrue(JoinPayload.isIpv4("255.255.255.255"))
        assertFalse(JoinPayload.isIpv4("1.2.3"))
        assertFalse(JoinPayload.isIpv4("1.2.3.4.5"))
        assertFalse(JoinPayload.isIpv4("a.b.c.d"))
        assertFalse(JoinPayload.isIpv4("::1"))
        assertFalse(JoinPayload.isIpv4(""))
    }

    @Test
    fun generatedTokenIsValidAndUnique() {
        val random = SecureRandom()
        val first = JoinPayload.newToken(random)
        val second = JoinPayload.newToken(random)
        assertEquals(22, first.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(first != second)
        assertIs<JoinPayload.DecodeResult.Success>(JoinPayload.decode(sample.copy(token = first).encode()))
    }
}
