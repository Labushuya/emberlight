package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Reine Regeln ohne Android und ohne Socket: Subnetz, Token, Parallelität, Übertragungsgrenzen, Header. */
class ServerUnitTests {
    // --- Ipv4Subnet ---------------------------------------------------------------

    @Test
    fun subnetContainsAddressesInsideTheNetworkOnly() {
        val subnet = Ipv4Subnet("192.168.1.20", 24)

        assertTrue(subnet.contains("192.168.1.1"))
        assertTrue(subnet.contains("192.168.1.255"))
        assertFalse(subnet.contains("192.168.2.1"))
    }

    @Test
    fun subnetEdgeCasesSlash30AndSlash32() {
        val slash30 = Ipv4Subnet("10.0.0.5", 30)
        assertTrue(slash30.contains("10.0.0.4"))
        assertTrue(slash30.contains("10.0.0.7"))
        assertFalse(slash30.contains("10.0.0.8"))

        val slash32 = Ipv4Subnet("10.0.0.5", 32)
        assertTrue(slash32.contains("10.0.0.5"))
        assertFalse(slash32.contains("10.0.0.4"))
        assertFalse(slash32.contains("10.0.0.6"))
    }

    @Test
    fun subnetRejectsNonIpv4AndNullAndMalformed() {
        val subnet = Ipv4Subnet("10.0.0.1", 8)

        assertFalse(subnet.contains(null))
        assertFalse(subnet.contains("::1"))
        assertFalse(subnet.contains("10.0.0"))
        assertFalse(subnet.contains("10.0.0.256"))
    }

    @Test
    fun subnetRejectsInvalidPrefixAndAddress() {
        assertThrows(IllegalArgumentException::class.java) { Ipv4Subnet("10.0.0.1", 33) }
        assertThrows(IllegalArgumentException::class.java) { Ipv4Subnet("10.0.0", 8) }
        assertThrows(IllegalArgumentException::class.java) { Ipv4Subnet("10.0.0.300", 8) }
    }

    @Test
    fun slash0ContainsEveryIpv4Address() {
        assertTrue(Ipv4Subnet("10.0.0.1", 0).contains("203.0.113.9"))
    }

    // --- TokenAuthorizer ----------------------------------------------------------

    private val hostPrincipal = Principal(ParticipantId.generate(), Role.HOST)

    private fun sha(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    @Test
    fun knownTokenYieldsItsPrincipal() {
        val authorizer = TokenAuthorizer(mapOf(sha("geheim-token-0123456789") to hostPrincipal))

        assertEquals(hostPrincipal, authorizer.authorize("Bearer geheim-token-0123456789"))
        assertEquals(hostPrincipal, authorizer.authorize("bearer geheim-token-0123456789"))
    }

    @Test
    fun wrongOrMalformedAuthorizationIsRejected() {
        val authorizer = TokenAuthorizer(mapOf(sha("geheim-token-0123456789") to hostPrincipal))

        assertNull(authorizer.authorize(null))
        assertNull(authorizer.authorize("Bearer falsch"))
        assertNull(authorizer.authorize("Basic geheim-token-0123456789"))
        assertNull(authorizer.authorize("Bearer "))
        assertNull(authorizer.authorize("Bearer geheim token"))
        assertNull(authorizer.authorize("Bearer ${"x".repeat(300)}"))
    }

    @Test
    fun denyAllRejectsEverything() {
        assertNull(RequestAuthorizer.DenyAll.authorize("Bearer irgendetwas"))
        assertNull(RequestAuthorizer.DenyAll.authorize(null))
    }

    // --- ServerGates --------------------------------------------------------------

    @Test
    fun requestGateAllowsSixteenAndRefusesTheSeventeenth() {
        val gates = ServerGates()
        repeat(ServerGates.REQUEST_LIMIT) { assertTrue(gates.tryEnterRequest()) }

        assertFalse(gates.tryEnterRequest())
        gates.leaveRequest()
        assertTrue(gates.tryEnterRequest())
    }

    @Test
    fun uploadGateLimitsTotalAndPerParticipant() {
        val gates = ServerGates()
        val a = ParticipantId.generate()
        val b = ParticipantId.generate()
        val c = ParticipantId.generate()

        assertTrue(gates.tryEnterUpload(a))
        assertTrue(gates.tryEnterUpload(a))
        assertFalse("Dritter Upload desselben Teilnehmers", gates.tryEnterUpload(a))
        assertTrue(gates.tryEnterUpload(b))
        assertTrue(gates.tryEnterUpload(c))
        assertFalse("Fünfter Upload insgesamt", gates.tryEnterUpload(ParticipantId.generate()))

        gates.leaveUpload(a)
        assertTrue(gates.tryEnterUpload(a))
    }

    // --- TransferGuard ------------------------------------------------------------

    private val second = 1_000_000_000L
    private val policy = TransferPolicy()

    @Test
    fun idleBodyIsAbortedAfterThirtySeconds() {
        val guard = TransferGuard(policy, startNanos = 0)

        assertNull(guard.check(29 * second))
        assertEquals(TransferViolation.IDLE, guard.check(30 * second))
    }

    @Test
    fun bytesResetTheIdleTimer() {
        val guard = TransferGuard(policy, startNanos = 0)
        // 1 MiB bei 25 s hält auch die Mindestrate ein, damit nur der Leerlauf geprüft wird.
        guard.onBytes(1024 * 1024, now = 25 * second)

        assertNull(guard.check(40 * second))
        assertEquals(TransferViolation.IDLE, guard.check(55 * second))
    }

    @Test
    fun noRateCheckDuringWarmup() {
        val guard = TransferGuard(policy, startNanos = 0)

        assertNull(guard.check(29 * second))
    }

    @Test
    fun slowBodyBelowMinimumRateIsAbortedAfterWarmup() {
        val guard = TransferGuard(policy, startNanos = 0)
        // 1 KiB über die ersten 29 Sekunden: unter 16 KiB/s, aber noch Anlaufzeit.
        repeat(29) { guard.onBytes(1024, now = it * second) }

        assertEquals(TransferViolation.TOO_SLOW, guard.check(30 * second))
    }

    @Test
    fun bodyAtMinimumRatePasses() {
        val guard = TransferGuard(policy, startNanos = 0)
        // 16 KiB je Sekunde, jede Sekunde ein Stück. Kein Leerlauf, keine Mindestratenverletzung.
        for (second in 0 until 90) {
            guard.onBytes(16 * 1024, now = second * this.second)
            assertNull("Sekunde $second", guard.check(second * this.second + 1))
        }
    }

    @Test
    fun totalDurationIsLimitedToSixtyMinutes() {
        val guard = TransferGuard(policy, startNanos = 0)
        // Dauerhaft genug Bytes, damit nur die Gesamtdauer greift.
        for (second in 0 until 3_600) guard.onBytes(64 * 1024, now = second * this.second)

        assertNull(guard.check(60 * 60 * second))
        assertEquals(TransferViolation.TOO_LONG, guard.check(60 * 60 * second + 1))
    }

    @Test
    fun policyRejectsNonsensicalValues() {
        assertThrows(IllegalArgumentException::class.java) { TransferPolicy(idleNanos = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            TransferPolicy(rateWarmupNanos = 61 * second, rateWindowNanos = 60 * second)
        }
    }

    // --- Header -------------------------------------------------------------------

    @Test
    fun filenameHeaderDecodesPercentEncodedUtf8() {
        assertEquals("ä.jpg", UploadFilenameHeader.decode("%C3%A4.jpg"))
        assertEquals("a+b.jpg", UploadFilenameHeader.decode("a%2Bb.jpg"))
    }

    @Test
    fun filenameHeaderRejectsBrokenEncodingsAndLongNames() {
        assertNull(UploadFilenameHeader.decode("%ZZ.jpg"))
        assertNull(UploadFilenameHeader.decode("%C3"))
        assertNull(UploadFilenameHeader.decode("%FF.jpg"))
        assertNull(UploadFilenameHeader.decode("roh mit Leerzeichen"))
        assertNull(UploadFilenameHeader.decode("a".repeat(256)))
    }

    @Test
    fun missingFilenameHeaderIsEmpty() {
        assertEquals("", UploadFilenameHeader.decode(null))
    }

    @Test
    fun contentDispositionHasAsciiFallbackAndRfc5987Name() {
        val header = ContentDispositionHeader.attachment("Übung \"x\".jpg")

        assertEquals("attachment; filename=\"_bung _x_.jpg\"; filename*=UTF-8''%C3%9Cbung%20%22x%22.jpg", header)
    }
}
