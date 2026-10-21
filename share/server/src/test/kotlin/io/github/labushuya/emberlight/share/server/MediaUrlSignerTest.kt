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

/** Signierte Medien-URLs (ADR 0019): Signatur, Zweck, Ablauf und Teilnehmerstatus. Mit Fake-Uhr, ohne Sleeps. */
class MediaUrlSignerTest {
    private var now = 1_000_000L
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val signer = MediaUrlSigner.forTest(key) { now }
    private val media = "mediaAAAAAAAAAAAAAAAAAAAAAA"
    private val guest = ParticipantId.generate()

    private fun verify(
        purpose: MediaPurpose,
        grantSig: String,
        exp: Long,
        mediaId: String = media,
        participant: String = guest.value,
        using: MediaUrlSigner = signer,
    ) = using.verify(mediaId, purpose, participant, exp, grantSig)

    @Test
    fun aFreshGrantVerifiesUntilItExpires() {
        val grant = signer.grant(media, MediaPurpose.PREVIEW, guest)
        assertEquals(now + 600, grant.expiresAt)
        assertEquals(MediaVerdict.VALID, verify(MediaPurpose.PREVIEW, grant.signature, grant.expiresAt))
        now = grant.expiresAt - 1
        assertEquals(MediaVerdict.VALID, verify(MediaPurpose.PREVIEW, grant.signature, grant.expiresAt))
        now = grant.expiresAt
        assertEquals(MediaVerdict.EXPIRED, verify(MediaPurpose.PREVIEW, grant.signature, grant.expiresAt))
    }

    @Test
    fun streamGrantsLastThirtyMinutesAndOthersTenMinutes() {
        assertEquals(now + 1_800, signer.grant(media, MediaPurpose.STREAM, guest).expiresAt)
        assertEquals(now + 600, signer.grant(media, MediaPurpose.VIEW, guest).expiresAt)
        assertEquals(now + 600, signer.grant(media, MediaPurpose.DOWNLOAD, guest).expiresAt)
    }

    @Test
    fun changingAnySignedFieldBreaksTheSignature() {
        val grant = signer.grant(media, MediaPurpose.DOWNLOAD, guest)
        val other = ParticipantId.generate().value
        assertEquals(
            MediaVerdict.BAD_SIGNATURE,
            verify(MediaPurpose.DOWNLOAD, grant.signature, grant.expiresAt, mediaId = "mediaBBBBBBBBBBBBBBBBBBBBBB"),
        )
        assertEquals(
            MediaVerdict.BAD_SIGNATURE,
            verify(MediaPurpose.DOWNLOAD, grant.signature, grant.expiresAt, participant = other),
        )
        assertEquals(MediaVerdict.BAD_SIGNATURE, verify(MediaPurpose.DOWNLOAD, grant.signature, grant.expiresAt + 1))
        assertEquals(MediaVerdict.BAD_SIGNATURE, verify(MediaPurpose.VIEW, grant.signature, grant.expiresAt))
    }

    @Test
    fun aSignatureDoesNotCarryOverToAnotherPurpose() {
        val grant = signer.grant(media, MediaPurpose.PREVIEW, guest)
        assertEquals(MediaVerdict.BAD_SIGNATURE, verify(MediaPurpose.STREAM, grant.signature, grant.expiresAt))
    }

    @Test
    fun aSignatureFromAnotherKeyIsRejected() {
        val grant = signer.grant(media, MediaPurpose.VIEW, guest)
        val otherKey = MediaUrlSigner.forTest(ByteArray(32) { 9 }) { now }
        assertEquals(
            MediaVerdict.BAD_SIGNATURE,
            verify(MediaPurpose.VIEW, grant.signature, grant.expiresAt, using = otherKey),
        )
    }

    @Test
    fun aRandomKeyIsThirtyTwoBytesAndDiffersBetweenRuns() {
        val a = MediaUrlSigner.random { now }.grant(media, MediaPurpose.VIEW, guest).signature
        val b = MediaUrlSigner.random { now }.grant(media, MediaPurpose.VIEW, guest).signature
        assertFalse(a == b)
        assertThrows(IllegalArgumentException::class.java) { MediaUrlSigner.forTest(ByteArray(16)) { now } }
    }

    @Test
    fun signaturesAreUrlSafeWithoutPadding() {
        val sig = signer.grant(media, MediaPurpose.VIEW, guest).signature
        assertEquals(43, sig.length)
        assertTrue(sig.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    private val guestPrincipal = Principal(guest, Role.GUEST)

    private fun authorizer(active: Map<ParticipantId, Principal>) = SignedMediaAuthorizer(signer) { active[it] }

    @Test
    fun noSignatureParametersMeansAbsent() {
        val auth = authorizer(mapOf(guest to guestPrincipal))
        assertEquals(SignedMediaAccess.Absent, auth.authorize(media, MediaPurpose.VIEW, null, null, null))
    }

    @Test
    fun partialParametersAreInvalid() {
        val auth = authorizer(mapOf(guest to guestPrincipal))
        assertEquals(
            SignedMediaAccess.Denied(SignedMediaAuthorizer.URL_INVALID),
            auth.authorize(media, MediaPurpose.VIEW, guest.value, null, "abc"),
        )
        assertEquals(
            SignedMediaAccess.Denied(SignedMediaAuthorizer.URL_INVALID),
            auth.authorize(media, MediaPurpose.VIEW, "not-an-id!", "1", "abc"),
        )
        assertEquals(
            SignedMediaAccess.Denied(SignedMediaAuthorizer.URL_INVALID),
            auth.authorize(media, MediaPurpose.VIEW, guest.value, "soon", "abc"),
        )
    }

    @Test
    fun validGrantForActiveParticipantIsGranted() {
        val grant = signer.grant(media, MediaPurpose.STREAM, guest)
        val auth = authorizer(mapOf(guest to guestPrincipal))
        val result =
            auth.authorize(
                media,
                MediaPurpose.STREAM,
                guest.value,
                grant.expiresAt.toString(),
                grant.signature,
            )
        assertEquals(SignedMediaAccess.Granted(guestPrincipal), result)
    }

    @Test
    fun expiredGrantSaysSo() {
        val grant = signer.grant(media, MediaPurpose.VIEW, guest)
        now = grant.expiresAt
        val auth = authorizer(mapOf(guest to guestPrincipal))
        assertEquals(
            SignedMediaAccess.Denied(SignedMediaAuthorizer.URL_EXPIRED),
            auth.authorize(media, MediaPurpose.VIEW, guest.value, grant.expiresAt.toString(), grant.signature),
        )
    }

    @Test
    fun removedParticipantLosesAccessAtOnce() {
        val grant = signer.grant(media, MediaPurpose.DOWNLOAD, guest)
        val auth = authorizer(emptyMap())
        assertEquals(
            SignedMediaAccess.Denied(SignedMediaAuthorizer.PARTICIPANT_INACTIVE),
            auth.authorize(media, MediaPurpose.DOWNLOAD, guest.value, grant.expiresAt.toString(), grant.signature),
        )
    }

    @Test
    fun routeTemplatesMapToPurposes() {
        assertEquals(MediaPurpose.PREVIEW, mediaRouteOf("/api/v1/media/$media/preview")?.purpose)
        assertEquals(MediaPurpose.VIEW, mediaRouteOf("/api/v1/media/$media/view")?.purpose)
        assertEquals(MediaPurpose.DOWNLOAD, mediaRouteOf("/api/v1/media/$media/content")?.purpose)
        assertEquals(MediaPurpose.STREAM, mediaRouteOf("/api/v1/media/$media/stream")?.purpose)
        assertEquals(media, mediaRouteOf("/api/v1/media/$media/view")?.id)
        assertNull(mediaRouteOf("/api/v1/media/$media"))
        assertNull(mediaRouteOf("/api/v1/media/$media/view/extra"))
        assertNull(mediaRouteOf("/api/v1/space"))
    }
}
