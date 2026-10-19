package io.github.labushuya.emberlight.share.server

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Dekodierter Dateiname aus `X-Emberlight-Filename`, oder `null` bei ungültiger Kodierung (400 `bad_filename`). */
object UploadFilenameHeader {
    const val NAME = "X-Emberlight-Filename"
    const val MAX_BYTES = 255

    /** `null` für fehlenden Header. Die Bereinigung folgt im Space-Modul. */
    fun decode(value: String?): String? {
        if (value == null) return ""
        val bytes = percentDecode(value) ?: return null
        if (bytes.size > MAX_BYTES) return null
        return strictUtf8(bytes)
    }

    /** Strikte Prozentdekodierung. Ungültige Escapes führen zu `null`. `+` bleibt `+`. */
    private fun percentDecode(value: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' -> {
                    if (index + 2 >= value.length) return null
                    val high = Character.digit(value[index + 1], 16)
                    val low = Character.digit(value[index + 2], 16)
                    if (high < 0 || low < 0) return null
                    out.write((high shl 4) or low)
                    index += 3
                }

                char.code in 0x21..0x7E -> {
                    out.write(char.code)
                    index++
                }

                else -> {
                    return null
                }
            }
        }
        return out.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String? {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (invalid: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}

/** Einwilligung, Standort unverändert zu speichern, wenn er nicht sicher entfernbar ist (ADR 0015). */
object KeepLocationHeader {
    const val NAME = "X-Emberlight-Keep-Location"
    const val YES = "1"
}

/** Antwort-Header `Content-Disposition` nach RFC 6266 (ADR 0013, Downloads). */
object ContentDispositionHeader {
    /**
     * `attachment` mit ASCII-Fallback in `filename` und dem UTF-8-Namen in `filename*` (RFC 5987). Nicht-ASCII-Zeichen
     * und Anführungszeichen im Fallback ersetzt `_`.
     */
    fun attachment(name: String): String {
        val fallback =
            buildString {
                name.forEach { char ->
                    append(if (char.code in 0x20..0x7E && char != '"' && char != '\\') char else '_')
                }
            }
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''${encodeExtValue(name)}"
    }

    /** Prozentkodierung nach RFC 5987: alles außer `attr-char` wird kodiert. */
    private fun encodeExtValue(name: String): String {
        val builder = StringBuilder()
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            if (isAttrChar(code)) {
                builder.append(code.toChar())
            } else {
                builder.append('%').append("%02X".format(code))
            }
        }
        return builder.toString()
    }

    private fun isAttrChar(code: Int): Boolean =
        code in 'a'.code..'z'.code ||
            code in 'A'.code..'Z'.code ||
            code in '0'.code..'9'.code ||
            code.toChar() in "!#\$&+-.^_`|~"
}
