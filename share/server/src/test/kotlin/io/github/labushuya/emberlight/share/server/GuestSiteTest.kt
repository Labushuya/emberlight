package io.github.labushuya.emberlight.share.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gäste-Seite W1 (ADR 0018): Auslieferung ohne Zugriffsprüfung, Sicherheits-Header und statische Regeln
 * für die Dateien
 * (keine Inline-Skripte, keine Inline-Stile, keine externen Quellen, Ausgabe nur per textContent).
 */
class GuestSiteTest {
    private val assetDir = File("src/main/assets/guest")

    private val assets = GuestAssets { name -> File(assetDir, name).takeIf { it.isFile }?.readBytes() }

    private fun ApplicationTestBuilder.install() {
        application {
            installEmberlightModule(
                ServerEnvironment(
                    authorizer = RequestAuthorizer.DenyAll,
                    expectedHost = { "localhost" },
                    senderAllowed = { true },
                    gates = ServerGates(),
                    requestLog = {},
                    debugLogging = false,
                    closure = SpaceClosure.Open,
                ),
                routes = { guestSite(assets) },
            )
        }
    }

    private suspend fun HttpResponse.header(name: String): String? = headers[name]

    @Test
    fun theStartPageIsServedWithoutAuthorizationAndWithTheSecurityHeaders() =
        testApplication {
            install()

            val response = client.get("/") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
            assertEquals(GuestSite.CONTENT_SECURITY_POLICY, response.header("Content-Security-Policy"))
            assertEquals("no-referrer", response.header("Referrer-Policy"))
            assertEquals("nosniff", response.header("X-Content-Type-Options"))
        }

    @Test
    fun scriptsAndStylesCarryTheSameHeadersAndTheRightTypes() =
        testApplication {
            install()

            val script = client.get("/guest.js") { header(HttpHeaders.Host, "localhost") }
            val style = client.get("/guest.css") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.OK, script.status)
            assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))
            assertEquals(GuestSite.CONTENT_SECURITY_POLICY, script.header("Content-Security-Policy"))
            assertEquals("nosniff", script.header("X-Content-Type-Options"))
            assertEquals(HttpStatusCode.OK, style.status)
            assertTrue(style.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
        }

    @Test
    fun bothLanguageFilesAreServedAndHaveTheSameKeys() =
        testApplication {
            install()

            val german = client.get("/i18n/de.json") { header(HttpHeaders.Host, "localhost") }.bodyAsText()
            val english = client.get("/i18n/en.json") { header(HttpHeaders.Host, "localhost") }.bodyAsText()

            val germanKeys = Json.parseToJsonElement(german).jsonObject.keys
            assertEquals(germanKeys, Json.parseToJsonElement(english).jsonObject.keys)
            assertTrue(germanKeys.contains("note"))
        }

    @Test
    fun otherPathsStillNeedAuthorizationAndOnlyGetIsPublic() =
        testApplication {
            install()

            val unknown = client.get("/nicht-da") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/") { header(HttpHeaders.Host, "localhost") }.status)
        }

    @Test
    fun aForeignHostIsStillRefusedOnTheStartPage() =
        testApplication {
            install()

            assertEquals(HttpStatusCode.Forbidden, client.get("/") { header(HttpHeaders.Host, "evil.example") }.status)
        }

    @Test
    fun theStartPageHasNoInlineScriptsOrStylesAndNoExternalSources() {
        val html = File(assetDir, "index.html").readText()

        val inlineScript = Regex("<script(?![^>]*\\ssrc=)[^>]*>", RegexOption.IGNORE_CASE)
        assertFalse("Inline-Skript", inlineScript.containsMatchIn(html))
        assertFalse("<style>-Element", html.contains("<style", ignoreCase = true))
        assertFalse("style-Attribut", Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("Ereignis-Attribut", Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("externe Quelle", Regex("(https?:)?//", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    @Test
    fun stylesheetAndScriptUseNoExternalSources() {
        listOf("guest.css", "guest.js").forEach { name ->
            val text = File(assetDir, name).readText()
            assertFalse("$name lädt extern", Regex("https?://").containsMatchIn(text))
        }
    }

    @Test
    fun theScriptNeverWritesHtmlAndOnlyUsesTextOutput() {
        val script = File(assetDir, "guest.js").readText()
        val forbidden =
            listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")
        forbidden.forEach { term ->
            assertFalse("Verboten im Skript: $term", script.contains(term))
        }
        assertTrue(script.contains("textContent"))
    }

    @Test
    fun thePreviewUsesOnlyTheHexPaletteColorAndTheTextOfTheName() {
        val script = File(assetDir, "guest.js").readText()
        assertTrue(script.contains("/api/v1/join/preview"))
        // Die Farbe kommt nur als Hex-Wert durch die Prüfung, nie als freier CSS-Wert (ADR 0018).
        assertTrue(script.contains("ACCENT_PATTERN = /^#[0-9A-Fa-f]{6}$/"))
        assertTrue(script.contains("el.preview.textContent = text(\"previewJoin\""))
        assertFalse(script.contains("el.preview.innerHTML"))
    }

    @Test
    fun everyTranslatedKeyUsedInTheStartPageExistsInBothLanguages() {
        val html = File(assetDir, "index.html").readText()
        val used = Regex("data-i18n=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toSet()
        val german = Json.parseToJsonElement(File(assetDir, "i18n/de.json").readText()).jsonObject.keys
        val english = Json.parseToJsonElement(File(assetDir, "i18n/en.json").readText()).jsonObject.keys

        assertTrue("Fehlt auf Deutsch: ${used - german}", german.containsAll(used))
        assertTrue("Fehlt auf Englisch: ${used - english}", english.containsAll(used))
    }
}
