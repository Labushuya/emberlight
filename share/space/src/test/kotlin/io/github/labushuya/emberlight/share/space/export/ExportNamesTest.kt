package io.github.labushuya.emberlight.share.space.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamesTest {
    @Test
    fun aPlainSpaceNameStaysAsItIs() {
        assertEquals("Urlaub", ExportNames.folderName("Urlaub"))
        assertEquals("Ä Ö ü", ExportNames.folderName("Ä Ö ü"))
    }

    @Test
    fun forbiddenCharactersAndControlCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", ExportNames.folderName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("a_b", ExportNames.folderName("a\u0007b"))
    }

    @Test
    fun whitespaceIsCollapsedAndTrimmed() {
        assertEquals("Leerzeichen", ExportNames.folderName("   Leerzeichen   "))
        assertEquals("a b", ExportNames.folderName("a \t\n b"))
    }

    @Test
    fun dotsAndEmptyNamesGetAFallbackAndNoRelativeSegments() {
        assertEquals("Space", ExportNames.folderName("  .. "))
        assertEquals("Space", ExportNames.folderName(""))
        assertEquals("Space", ExportNames.folderName("..."))
        assertEquals("Space", ExportNames.folderName("."))
    }

    @Test
    fun longNamesAreCutToEightyCharacters() {
        val name = ExportNames.folderName("x".repeat(200))
        assertEquals(80, name.length)
    }

    @Test
    fun fileNameKeepsTheOriginalAndDropsAnyPathPart() {
        assertEquals("bild.jpg", ExportNames.fileName("bild.jpg", "jpg"))
        assertEquals("passwd", ExportNames.fileName("../../etc/passwd", null))
        assertEquals("dateien.jpg", ExportNames.fileName("ordner\\dateien.jpg", "jpg"))
    }

    @Test
    fun anEmptyFileNameFallsBackWithTheExtensionWhenKnown() {
        assertEquals("Medium.jpg", ExportNames.fileName("", "jpg"))
        assertEquals("Medium", ExportNames.fileName("x/", null))
        assertEquals("Medium", ExportNames.fileName("...", ""))
    }

    @Test
    fun uniqueKeepsAFreeNameAndNumbersATakenOne() {
        assertEquals("a.jpg", ExportNames.unique("a.jpg", emptySet()))
        assertEquals("a (2).jpg", ExportNames.unique("a.jpg", setOf("A.jpg")))
        assertEquals("a (2)", ExportNames.unique("a", setOf("a")))
        assertEquals("a (3).jpg", ExportNames.unique("a.jpg", setOf("a.jpg", "a (2).jpg")))
    }

    @Test
    fun aLeadingDotIsNotAnExtension() {
        assertEquals(".hidden (2)", ExportNames.unique(".hidden", setOf(".hidden")))
    }

    @Test
    fun theRelativePathLivesUnderPicturesEmberlight() {
        assertEquals("Pictures/Emberlight/Urlaub/", ExportNames.relativePath("Urlaub"))
        assertTrue(ExportNames.relativePath("x").startsWith("Pictures/"))
    }
}
