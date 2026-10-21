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
 * Gäste-Seite (ADR 0018, ADR 0019, Paket 3.5a-3): Auslieferung ohne Zugriffsprüfung, Sicherheits-Header und statische Regeln
 * für alle Dateien der Seite (keine Inline-Skripte, keine Inline-Stile, keine externen Quellen, Ausgabe nur per textContent,
 * vollständige Übersetzungen, nur bekannte Element-IDs).
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

    private fun scripts(): List<File> =
        assetDir
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile && it.extension == "js"
            }.sortedBy { it.name }

    private fun keysOf(name: String) = Json.parseToJsonElement(File(assetDir, name).readText()).jsonObject.keys

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
    fun theSharedAndSpaceScriptsAreServedAsScriptsToo() =
        testApplication {
            install()

            listOf("/guest-core.js", "/guest-space.js").forEach { path ->
                val response = client.get(path) { header(HttpHeaders.Host, "localhost") }
                assertEquals(path, HttpStatusCode.OK, response.status)
                assertTrue(path, response.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))
                assertEquals(path, GuestSite.CONTENT_SECURITY_POLICY, response.header("Content-Security-Policy"))
            }
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
    fun everyFileOfTheFolderIsServedByARoute() {
        val served =
            GuestSite.FILES.values
                .map { it.first }
                .toSet()
        val onDisk =
            assetDir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(assetDir).path.replace('\\', '/') }
                .toSet()
        assertTrue("Nicht ausgeliefert: ${onDisk - served}", served.containsAll(onDisk))
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
    fun stylesheetAndScriptsUseNoExternalSources() {
        (scripts() + File(assetDir, "guest.css")).forEach { file ->
            assertFalse("${file.name} lädt extern", Regex("https?://").containsMatchIn(file.readText()))
        }
    }

    @Test
    fun noScriptWritesHtmlEvaluatesStringsOrUsesStyleAttributes() {
        val forbidden =
            listOf(
                "innerHTML",
                "outerHTML",
                "insertAdjacentHTML",
                "document.write",
                "eval(",
                "new Function",
                "setTimeout(\"",
                "setInterval(\"",
            )
        scripts().forEach { file ->
            val source = file.readText()
            forbidden.forEach { term ->
                assertFalse("Verboten in ${file.name}: $term", source.contains(term))
            }
            // Stile nur über element.style.x, nie als Attribut (CSP, ADR 0018).
            assertFalse(
                "setAttribute(style) in ${file.name}",
                Regex("setAttribute\\(\\s*[\"']style[\"']").containsMatchIn(source),
            )
        }
        assertTrue(scripts().any { it.readText().contains("textContent") })
    }

    @Test
    fun thePreviewUsesOnlyTheHexPaletteColorAndTheTextOfTheName() {
        val join = File(assetDir, "guest.js").readText()
        val core = File(assetDir, "guest-core.js").readText()
        assertTrue(join.contains("/api/v1/join/preview"))
        // Die Farbe kommt nur als Hex-Wert durch die Prüfung, nie als freier CSS-Wert (ADR 0018).
        assertTrue(core.contains("ACCENT_PATTERN = /^#[0-9A-Fa-f]{6}$/"))
        assertTrue(join.contains("el.preview.textContent = text(\"previewJoin\""))
        assertFalse(join.contains("el.preview.innerHTML"))
    }

    @Test
    fun everyElementIdTheScriptsUseExistsInTheMarkup() {
        val html = File(assetDir, "index.html").readText()
        val ids = Regex("\\sid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toSet()
        val used =
            scripts()
                .flatMap { file ->
                    Regex(
                        "(?:getElementById|byId)\\(\"([^\"]+)\"\\)",
                    ).findAll(file.readText()).map { it.groupValues[1] }
                }.toSet()
        assertTrue("Fehlende IDs im Markup: ${used - ids}", ids.containsAll(used))
    }

    @Test
    fun everyTranslationKeyUsedByThePagesExistsInBothLanguagesAndPluralsAreComplete() {
        val html = File(assetDir, "index.html").readText()
        val used = mutableSetOf<String>()
        Regex("data-i18n(?:-aria)?=\"([^\"]+)\"").findAll(html).forEach { used += it.groupValues[1] }
        val plurals = mutableSetOf<String>()
        scripts().forEach { file ->
            val source = file.readText()
            Regex("\\b(?:t|text)\\(\"([A-Za-z0-9]+)\"").findAll(source).forEach { used += it.groupValues[1] }
            Regex("plural\\(\"([A-Za-z0-9]+)\"").findAll(source).forEach { plurals += it.groupValues[1] }
        }
        plurals.forEach { base ->
            used += base + "One"
            used += base + "Other"
        }
        val german = keysOf("i18n/de.json")
        val english = keysOf("i18n/en.json")

        assertTrue("Fehlt auf Deutsch: ${used - german}", german.containsAll(used))
        assertTrue("Fehlt auf Englisch: ${used - english}", english.containsAll(used))
        assertEquals(german, english)
    }
}
