package io.github.labushuya.emberlight.share.space.participants

import java.text.Normalizer
import java.util.Locale

/**
 * Anzeigename eines Teilnehmers (ADR 0018, Namensregeln).
 *
 * Ablauf: NFC, Leerzeichen-Kategorien auf ein ASCII-Leerzeichen, Ränder trimmen, interne Folgen auf ein Leerzeichen
 * reduzieren. Danach gilt die Zeichenprüfung und die Länge in Codepunkten (1 bis [MAX_CODEPOINTS]).
 *
 * Erlaubt sind Buchstaben, Ziffern (Nd), Marks, das Leerzeichen und `- _ . '`. Alles andere, insbesondere Steuer-,
 * Format- (also Bidi- und ZWJ-), Zeilen- und Absatztrenner sowie private und nicht zugewiesene Zeichen, ist verboten.
 *
 * Der Eindeutigkeitsschlüssel ist NFKC und danach `lowercase(Locale.ROOT)`. Zwei Namen mit demselben Schlüssel gelten als
 * gleich, auch bei Groß- und Kleinschreibung oder Kompatibilitätszeichen.
 */
object ParticipantName {
    const val MAX_CODEPOINTS = 24

    /** Präfix des Schlüssels entfernter Teilnehmer. Er gibt den Namen wieder frei (ADR 0018). */
    const val REVOKED_KEY_PREFIX = "#revoked:"

    private const val ASCII_SPACE = ' '
    private const val SPECIAL_ALLOWED = "-_.'"

    enum class Reason { EMPTY, TOO_LONG, FORBIDDEN_CHARACTER }

    sealed interface Result {
        /** [display] ist der gespeicherte Name, [key] der Eindeutigkeitsschlüssel. */
        data class Valid(
            val display: String,
            val key: String,
        ) : Result

        data class Invalid(
            val reason: Reason,
        ) : Result
    }

    fun normalize(raw: String): Result {
        val composed = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val mapped =
            buildString {
                composed.codePoints().forEach { cp ->
                    appendCodePoint(if (Character.getType(cp) == SPACE_SEPARATOR) ASCII_SPACE.code else cp)
                }
            }
        val display = mapped.trim(ASCII_SPACE).replace(MULTI_SPACE, ASCII_SPACE.toString())
        if (display.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (!display.codePoints().allMatch(::isAllowed)) return Result.Invalid(Reason.FORBIDDEN_CHARACTER)
        if (display.codePointCount(0, display.length) > MAX_CODEPOINTS) return Result.Invalid(Reason.TOO_LONG)
        return Result.Valid(display, keyOf(display))
    }

    /** Eindeutigkeitsschlüssel für einen bereits normalisierten Namen. */
    fun keyOf(display: String): String = Normalizer.normalize(display, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * Vorschlag für einen vergebenen Namen (ADR 0018): `Anna 2`, `Anna 3` und so weiter. Der Grundname wird gekürzt,
     * damit der Vorschlag die Längengrenze einhält. [isTaken] bekommt den Schlüssel eines Kandidaten.
     */
    fun suggestAlternative(
        display: String,
        isTaken: (key: String) -> Boolean,
    ): String {
        for (number in 2..MAX_SUFFIX) {
            val suffix = " $number"
            val room = MAX_CODEPOINTS - suffix.codePointCount(0, suffix.length)
            val base = display.codePointsPrefix(room).trimEnd(ASCII_SPACE)
            val candidate = base + suffix
            if (!isTaken(keyOf(candidate))) return candidate
        }
        error("Kein freier Namensvorschlag gefunden.")
    }

    private fun isAllowed(cp: Int): Boolean =
        when (Character.getType(cp)) {
            Character.UPPERCASE_LETTER.toInt(),
            Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> cp == ASCII_SPACE.code || (cp < 128 && SPECIAL_ALLOWED.indexOf(cp.toChar()) >= 0)
        }

    private fun String.codePointsPrefix(count: Int): String {
        val end = offsetByCodePoints(0, minOf(count, codePointCount(0, length)))
        return substring(0, end)
    }

    private const val MAX_SUFFIX = 999
    private val MULTI_SPACE = Regex(" {2,}")
    private const val SPACE_SEPARATOR = Character.SPACE_SEPARATOR.toInt()
}
