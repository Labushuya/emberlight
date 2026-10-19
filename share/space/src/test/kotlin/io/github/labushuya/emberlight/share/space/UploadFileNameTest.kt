package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadFileNameTest {
    @Test
    fun separatorsAndQuotesAreRemoved() {
        assertEquals("etcpasswd", UploadFileName.sanitize("/etc/passwd"))
        assertEquals("abc", UploadFileName.sanitize("a\\b:\"c"))
    }

    @Test
    fun parentReferenceCannotSurvive() {
        assertEquals("passwd", UploadFileName.sanitize("../../passwd"))
    }

    @Test
    fun controlCharactersAreRemoved() {
        assertEquals("abc", UploadFileName.sanitize("a\u0000b\nc\u007F"))
    }

    @Test
    fun leadingDotsAreRemoved() {
        assertEquals("versteckt", UploadFileName.sanitize("...versteckt"))
    }

    @Test
    fun emptyResultFallsBack() {
        assertEquals(UploadFileName.FALLBACK, UploadFileName.sanitize("...  "))
        assertEquals(UploadFileName.FALLBACK, UploadFileName.sanitize("/:\\"))
    }

    @Test
    fun longNamesAreTruncatedByCodePoints() {
        val emoji = "\uD83D\uDE00" // ein Codepunkt, zwei UTF-16-Zeichen
        val result = UploadFileName.sanitize(emoji.repeat(200))

        assertEquals(UploadFileName.MAX_CODE_POINTS, result.codePointCount(0, result.length))
    }

    @Test
    fun unicodeIsNormalizedToNfc() {
        // e + kombinierender Akut wird zu é
        assertEquals("caf\u00E9.jpg", UploadFileName.sanitize("cafe\u0301.jpg"))
    }
}
