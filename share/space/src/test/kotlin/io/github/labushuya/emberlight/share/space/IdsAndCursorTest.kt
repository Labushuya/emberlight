package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsAndCursorTest {
    @Test
    fun generatedIdsAreBase64UrlWithoutPaddingAnd22Characters() {
        val ids = List(50) { MediaId.generate().value }

        assertTrue(ids.all { it.length == 22 && it.matches(Regex("[A-Za-z0-9_-]{22}")) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun invalidIdsAreRejected() {
        assertNull(MediaId.parse("../../etc/passwd"))
        assertNull(MediaId.parse("a".repeat(21)))
        assertNull(MediaId.parse("a".repeat(22) + "="))
        assertThrows(IllegalArgumentException::class.java) { SpaceId("kurz") }
        assertNull(ParticipantId.parse("x"))
    }

    @Test
    fun cursorRoundTrips() {
        val cursor = MediaCursor(createdAt = 1_700_000_000_123L, id = MediaId.generate())

        assertEquals(cursor, MediaCursor.decode(cursor.encode()))
    }

    @Test
    fun foreignOrBrokenCursorsAreRejected() {
        assertNull(MediaCursor.decode(""))
        assertNull(MediaCursor.decode("%%%"))
        assertNull(
            MediaCursor.decode(
                java.util.Base64
                    .getUrlEncoder()
                    .encodeToString("nur-text".toByteArray()),
            ),
        )
        assertNull(
            MediaCursor.decode(
                java.util.Base64
                    .getUrlEncoder()
                    .encodeToString("-5:${"a".repeat(22)}".toByteArray()),
            ),
        )
        assertNull(
            MediaCursor.decode(
                java.util.Base64
                    .getUrlEncoder()
                    .encodeToString("1:../x".toByteArray()),
            ),
        )
    }
}
