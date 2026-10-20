package io.github.labushuya.emberlight.share.space.participants

import io.github.labushuya.emberlight.share.space.participants.ParticipantName.Reason
import io.github.labushuya.emberlight.share.space.participants.ParticipantName.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Namensregeln und Eindeutigkeit (ADR 0018, Grenzen). Reine JVM-Tests, keine Datenbank. */
class ParticipantNameTest {
    /** U+1D49C (mathematisches Skript-A, Lu) als Ersatzzeichenpaar: ein Codepunkt, zwei UTF-16-Einheiten. */
    private val wideLetter = "𝒜"

    @Test
    fun trimsEdgesAndCollapsesInnerSpaces() {
        assertValid("Anna Maria", "  Anna    Maria ")
    }

    @Test
    fun mapsSpaceSeparatorsToOrdinarySpace() {
        assertValid("Anna Maria", "Anna  Maria")
    }

    @Test
    fun composesToNfc() {
        // "e" mit kombinierendem Akut wird zu "é".
        assertValid("été", "été")
    }

    @Test
    fun acceptsOneAndTwentyFourCodepoints() {
        assertValid("A", "A")
        assertValid(
            wideLetter.repeat(ParticipantName.MAX_CODEPOINTS),
            wideLetter.repeat(ParticipantName.MAX_CODEPOINTS),
        )
    }

    @Test
    fun rejectsTwentyFiveCodepointsEvenWhenUtf16LengthIsLonger() {
        // 25 Codepunkte sind 50 UTF-16-Einheiten. Gezählt werden Codepunkte.
        assertEquals(Result.Invalid(Reason.TOO_LONG), ParticipantName.normalize(wideLetter.repeat(25)))
    }

    @Test
    fun rejectsEmptyAndSpaceOnly() {
        assertEquals(Result.Invalid(Reason.EMPTY), ParticipantName.normalize(""))
        assertEquals(Result.Invalid(Reason.EMPTY), ParticipantName.normalize("     "))
    }

    @Test
    fun rejectsControlFormatSeparatorAndPrivateCharacters() {
        val forbidden =
            listOf(
                "Anna\u0000", // Cc: NUL
                "Anna\nBen", // Cc: Zeilenumbruch
                "Anna\tBen", // Cc: Tabulator
                "Anna​Ben", // Cf: Zero Width Space
                "Anna‍Ben", // Cf: ZWJ
                "Anna‮Ben", // Cf: Bidi-Override
                "Anna⁦Ben", // Cf: Bidi-Isolate
                "Anna Ben", // Zl: Zeilentrenner
                "Anna Ben", // Zp: Absatztrenner
                "Anna", // Co: privat
                "Anna͸", // Cn: nicht zugewiesen
                "<img src=x onerror=alert(1)>", // Spitzklammern, Gleichheitszeichen, Klammern
                "Anna/Ben",
                "😀", // Emoji (So)
            )
        forbidden.forEach { raw ->
            assertEquals(
                "Erwartet abgelehnt: $raw",
                Result.Invalid(Reason.FORBIDDEN_CHARACTER),
                ParticipantName.normalize(raw),
            )
        }
    }

    @Test
    fun allowsLettersDigitsMarksAndTheFourSpecials() {
        assertValid("Jörg-Émil_O.Brien'2", "Jörg-Émil_O.Brien'2")
        // Devanagari mit Virama (Mn): bleibt nach NFC erhalten.
        assertValid("क्ष", "क्ष")
    }

    @Test
    fun keyIsCaseAndCompatibilityInsensitive() {
        val upper = ParticipantName.normalize("ANNA") as Result.Valid
        val lower = ParticipantName.normalize("anna") as Result.Valid
        val fullWidth = ParticipantName.normalize("Ａnna") as Result.Valid
        assertEquals(lower.key, upper.key)
        assertEquals(lower.key, fullWidth.key)
        assertEquals("anna", lower.key)
    }

    @Test
    fun suggestionCountsUpFromTwo() {
        val taken = setOf("anna", "anna 2")
        assertEquals("Anna 3", ParticipantName.suggestAlternative("Anna") { it in taken })
    }

    @Test
    fun suggestionKeepsTheLengthLimit() {
        val name = "A".repeat(ParticipantName.MAX_CODEPOINTS)
        val suggestion = ParticipantName.suggestAlternative(name) { it == ParticipantName.keyOf(name) }
        assertTrue(suggestion.codePointCount(0, suggestion.length) <= ParticipantName.MAX_CODEPOINTS)
        assertTrue(suggestion.endsWith(" 2"))
    }

    private fun assertValid(
        expected: String,
        raw: String,
    ) {
        val result = ParticipantName.normalize(raw)
        assertTrue("Erwartet gültig: $raw, war $result", result is Result.Valid)
        assertEquals(expected, (result as Result.Valid).display)
    }
}
