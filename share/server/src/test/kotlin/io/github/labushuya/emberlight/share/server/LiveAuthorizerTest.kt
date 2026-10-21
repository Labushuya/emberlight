package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.participants.Credential
import io.github.labushuya.emberlight.share.space.participants.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zugriffsprüfung mit Widerruf (ADR 0018). Ein widerrufener Token ist sofort ungültig. */
class LiveAuthorizerTest {
    private val guest = ParticipantId.generate()
    private val guestToken = "tok-guest-0123456789-abcdef"
    private val host = ParticipantId.generate()
    private val hostToken = "tok-host-0123456789-abcdef"

    private fun credential(
        id: ParticipantId,
        role: Role,
        token: String,
    ) = Credential(Secrets.hashHex(token), id, role)

    @Test
    fun knownTokensAuthenticateAndUnknownOnesDoNot() {
        val authorizer = LiveAuthorizer(listOf(credential(guest, Role.GUEST, guestToken)))

        assertEquals(guest, authorizer.authorize("Bearer $guestToken")?.participantId)
        assertNull(authorizer.authorize("Bearer wrong-token-0123456789"))
        assertNull(authorizer.authorize(null))
    }

    @Test
    fun revokedTokenFailsImmediately() {
        val authorizer =
            LiveAuthorizer(
                listOf(credential(guest, Role.GUEST, guestToken), credential(host, Role.HOST, hostToken)),
            )

        authorizer.revoke(guest)

        assertNull(authorizer.authorize("Bearer $guestToken"))
        assertEquals(Role.HOST, authorizer.authorize("Bearer $hostToken")?.role)
    }

    @Test
    fun replaceRebuildsTheSnapshotFromTheDatabaseState() {
        val authorizer = LiveAuthorizer(listOf(credential(guest, Role.GUEST, guestToken)))

        authorizer.replace(listOf(credential(host, Role.HOST, hostToken)))

        assertNull(authorizer.authorize("Bearer $guestToken"))
        assertEquals(host, authorizer.authorize("Bearer $hostToken")?.participantId)
    }
}
