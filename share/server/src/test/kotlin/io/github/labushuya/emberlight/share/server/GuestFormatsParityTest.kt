package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ContentInspector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Gleichlauf der Signaturprüfung (Paket 3.5b, Drift vermeiden). Dieselben Fälle in `src/test/fixtures/guest-formats.json`
 * bewertet die Gäste-Seite (`guest/guest-upload.js`, Browser-Test `formats.test.mjs`) nach `guest/formats.json`. Hier
 * prüft der Test zwei Dinge: `ContentInspector` liefert das erwartete Ergebnis, und `formats.json` ergibt mit derselben
 * Auswertung dasselbe wie `ContentInspector`. Weicht eine der beiden Listen ab, schlägt einer der Tests fehl.
 */
class GuestFormatsParityTest {
    private val rules = readJson("src/main/assets/guest/formats.json")
    private val cases = readJson("src/test/fixtures/guest-formats.json")["cases"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun contentInspectorGivesTheExpectedResultForEveryFixture() {
        cases.forEach { case ->
            val name = case.text("name")
            val actual =
                ContentInspector.identify(hex(case.text("head")))?.let {
                    it.mimeType to
                        it.kind.name.lowercase()
                }
            assertEquals(name, expectedOf(case), actual)
        }
    }

    @Test
    fun formatsJsonGivesTheSameResultAsContentInspector() {
        cases.forEach { case ->
            val name = case.text("name")
            val head = hex(case.text("head"))
            val byRules = identifyByRules(head)
            val byInspector = ContentInspector.identify(head)?.let { it.mimeType to it.kind.name.lowercase() }
            assertEquals(name, byInspector, byRules)
        }
    }

    /** Auswertung von `formats.json` wie in `guest-upload.js`: erste passende Signatur, sonst die ftyp-Marke. */
    private fun identifyByRules(head: ByteArray): Pair<String, String>? {
        rules["signatures"]!!.jsonArray.map { it.jsonObject }.forEach { signature ->
            val matched = signature["checks"]!!.jsonArray.all { matches(head, it.jsonObject) }
            if (matched) return signature.text("mime") to signature.text("kind")
        }
        val ftyp = rules.getValue("ftyp").jsonObject
        if (!matches(head, ftyp)) return null
        val brand = asciiAt(head, ftyp.number("brandOffset"), BRAND_LENGTH) ?: return null
        val entry = ftyp.getValue("brands").jsonObject[brand]?.jsonObject ?: return null
        return entry.text("mime") to entry.text("kind")
    }

    private fun matches(
        head: ByteArray,
        check: JsonObject,
    ): Boolean {
        val offset = check.number("offset")
        val expected =
            check["hex"]?.let { hex(it.jsonPrimitive.content) }
                ?: check.text("ascii").toByteArray(Charsets.ISO_8859_1)
        if (offset < 0 || offset + expected.size > head.size) return false
        return expected.indices.all { head[offset + it] == expected[it] }
    }

    private fun asciiAt(
        head: ByteArray,
        offset: Int,
        length: Int,
    ): String? {
        if (offset + length > head.size) return null
        return String(head, offset, length, Charsets.ISO_8859_1)
    }

    private fun expectedOf(case: JsonObject): Pair<String, String>? {
        val mime = case.getValue("mime").jsonPrimitive.contentOrNull ?: return null
        return mime to case.text("kind")
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int

    private fun readJson(path: String): JsonObject = Json.parseToJsonElement(File(path).readText()).jsonObject

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val BRAND_LENGTH = 4
    }
}
