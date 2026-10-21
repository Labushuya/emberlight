package io.github.labushuya.emberlight.share.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Browser-Tests der Gäste-Seite (Paket 3.5a-3, ADR 0019). Der Test startet den echten Server auf Loopback, bereitet den
 * Space vor und übergibt Port, Einladung und Sitzung an Node (`src/test/browser`). Node steuert Chrome headless per CDP.
 *
 * Fehlen Node (22 oder neuer) oder Chrome, werden die Tests übersprungen. Mit `CI=true` schlagen sie dann fehl.
 * Braucht ein Test eine Änderung am Server (Ende, Abschalten), meldet sich Node über stderr (`EMBERLIGHT-CONTROL`).
 * Der Test führt die Aktion aus und antwortet auf stdin mit `ok`.
 */
@RunWith(RobolectricTestRunner::class)
class GuestBrowserTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun joinInvitationRecallAndLeave() {
        runScenario(
            file = "join.test.mjs",
            spaceName = SPACE_NAME,
            endsInSeconds = null,
        ) { emptyMap() }
    }

    @Test
    fun galleryOnPhoneAndPcWithdrawWithConfirmation() {
        runScenario(
            file = "gallery.test.mjs",
            spaceName = SPACE_NAME,
            endsInSeconds = 5_400,
        ) { scene ->
            val lena = scene.join("Lena")
            val jonas = scene.join("Jonas")
            val omar = scene.join("Omar")
            val ben = scene.join("Ben")
            val uploads =
                listOf(
                    scene.upload(lena.token, "lena.jpg", BrowserScene.jpeg(0x3366CC)),
                    scene.upload(jonas.token, "jonas.jpg", BrowserScene.jpeg(0xCC6633)),
                    scene.upload(jonas.token, "jonas.mp4", BrowserScene.clip()),
                    scene.upload(omar.token, "omar.heic", MediaFixtures.unremovableHeic(), keepLocation = true),
                    scene.upload(ben.token, "ben.jpg", BrowserScene.jpeg(0x339966)),
                )
            // Mit Vorschauen wie im Betrieb. Ob eine Vorschau gelingt, entscheidet der Renderer. Der Browser-Test
            // richtet seine Erwartungen danach aus der API aus (gallery.test.mjs).
            val previews = scene.renderPreviews(uploads)
            // Das Foto aus dem Raster muss eine Vorschau bekommen. Sonst prüft der Test nur die Platzhalter.
            assertEquals("Vorschau des Fotos", "READY", previews.getValue(uploads.first()))
            assertEquals("Vorschau des Videos ohne Frame", "FAILED", previews.getValue(uploads[2]))
            scene.removeGuest(ben.id)
            mapOf(
                "session" to session(scene, jonas),
                "expect" to
                    buildJsonObject {
                        put("total", 5)
                        put("mine", 2)
                    },
            )
        }
    }

    @Test
    fun bannerTenMinutesBeforeTheEnd() {
        runScenario(file = "banner.test.mjs", spaceName = SPACE_NAME, endsInSeconds = 590) { scene ->
            mapOf("session" to session(scene, scene.join("Lena")))
        }
    }

    @Test
    fun dialogOneMinuteBeforeTheEndThenTheEndingView() {
        runScenario(file = "ending.test.mjs", spaceName = "Ende-Test", endsInSeconds = 12) { scene ->
            mapOf("session" to session(scene, scene.join("Lena")))
        }
    }

    @Test
    fun endingByAGoneSpaceAnswersWithTheEndingView() {
        runScenario(file = "closed.test.mjs", spaceName = SPACE_NAME, endsInSeconds = 3_600) { scene ->
            mapOf("session" to session(scene, scene.join("Lena")))
        }
    }

    @Test
    fun connectionLossShowsTheHintAndKeepsTheSpace() {
        runScenario(file = "connection.test.mjs", spaceName = SPACE_NAME, endsInSeconds = null) { scene ->
            mapOf("session" to session(scene, scene.join("Lena")))
        }
    }

    /** Ein Durchlauf: Space vorbereiten, Server starten, Node mit der Datei laufen lassen, Ergebnis prüfen. */
    private fun runScenario(
        file: String,
        spaceName: String,
        endsInSeconds: Long?,
        prepare: (BrowserScene) -> Map<String, JsonElement>,
    ) {
        val chrome = requireBrowserTools()
        val endsAt = endsInSeconds?.let { System.currentTimeMillis() + it * 1_000 }
        BrowserScene(folder.newFolder("spaces"), spaceName, endsAt).use { scene ->
            // Der Server läuft vor der Vorbereitung, Gäste und Uploads gehen über die echte Schnittstelle.
            val port = scene.start()
            val extra = prepare(scene)
            val config =
                buildJsonObject {
                    put("base", "http://127.0.0.1:$port")
                    put("invite", scene.inviteSecret)
                    put("spaceName", spaceName)
                    extra.forEach { (key, value) -> put(key, value) }
                }
            val run = NodeBrowserRun(scene, chrome)
            val exitCode = run.execute(file, config)
            assertEquals("Browser-Test $file (Ausgabe unten)\n${run.output()}", 0, exitCode)
        }
    }

    private fun session(
        scene: BrowserScene,
        guest: BrowserScene.Guest,
    ): JsonObject =
        buildJsonObject {
            put("spaceId", scene.spaceId.value)
            put("participantId", guest.id.value)
            put("displayName", guest.displayName)
            put("token", guest.token)
            put("spaceName", scene.spaceName)
            put("accentColor", scene.access.accentColor)
            put("endsAt", scene.endsAtMillis?.let { Instant.ofEpochMilli(it).toString() })
        }

    companion object {
        const val SPACE_NAME = "Sommerfest im Garten"
    }
}

/**
 * Startet Node mit einer Testdatei und beantwortet ihre Steuerzeilen. Die Ausgabe von Node landet im Protokoll, damit
 * ein Fehlschlag im Build die Meldung zeigt.
 */
internal class NodeBrowserRun(
    private val scene: BrowserScene,
    private val chromePath: String,
) {
    private val output = StringBuffer()

    fun output(): String = output.toString()

    /** Liefert den Exit-Code. Nach der Zeitgrenze wird Node beendet und der Lauf gilt als fehlgeschlagen. */
    fun execute(
        file: String,
        config: JsonObject,
    ): Int {
        val node = System.getenv("EMBERLIGHT_NODE") ?: "node"
        val builder =
            ProcessBuilder(node, File("src/test/browser/$file").absolutePath)
                .directory(File(".").absoluteFile)
        builder.environment()["EMBERLIGHT_BROWSER_CONFIG"] = config.toString()
        // Profil-Ordner des Browsers: ein eigener Ordner aus der Umgebung, sonst der Temp-Ordner der JVM.
        if (builder.environment()["EMBERLIGHT_BROWSER_TMP"].isNullOrBlank()) {
            builder.environment()["EMBERLIGHT_BROWSER_TMP"] = System.getProperty("java.io.tmpdir") ?: ""
        }
        builder.environment()["EMBERLIGHT_CHROME"] = chromePath
        if (System.getenv("CI") == "true" &&
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("linux")
        ) {
            builder.environment()["EMBERLIGHT_BROWSER_NO_SANDBOX"] = "1"
        }
        val process = builder.start()
        val stdout = Thread { process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } }
        val stderr =
            Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith(CONTROL_PREFIX)) {
                        answer(line.removePrefix(CONTROL_PREFIX).trim(), process)
                    } else {
                        output.append(line).append('\n')
                    }
                }
            }
        stdout.start()
        stderr.start()
        val finished = process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            output.append("Zeitgrenze von $RUN_TIMEOUT_SECONDS s überschritten.\n")
            return -1
        }
        stdout.join()
        stderr.join()
        return process.exitValue()
    }

    private fun answer(
        name: String,
        process: Process,
    ) {
        val result =
            runCatching {
                when (name) {
                    "close-space" -> scene.markClosed()
                    "stop-server" -> scene.stopServer()
                    else -> error("unbekannte Steuerung: $name")
                }
            }
        output.append("Steuerung $name: ${if (result.isSuccess) "ok" else result.exceptionOrNull()?.message}\n")
        process.outputStream.bufferedWriter().apply {
            write(if (result.isSuccess) "ok\n" else "fehler\n")
            flush()
        }
    }

    private companion object {
        const val CONTROL_PREFIX = "EMBERLIGHT-CONTROL "
        const val RUN_TIMEOUT_SECONDS = 240L
    }
}

private val CHROME_CANDIDATES =
    listOf(
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    )

/**
 * Prüft Node (22 oder neuer) und Chrome. Fehlt etwas, werden die Tests übersprungen. Mit `CI=true` schlägt der Test fehl.
 * Liefert den Pfad von Chrome.
 */
private fun requireBrowserTools(): String {
    val node = System.getenv("EMBERLIGHT_NODE") ?: "node"
    val major = nodeMajor(node)
    val chrome =
        System.getenv("EMBERLIGHT_CHROME")?.takeIf { File(it).isFile }
            ?: CHROME_CANDIDATES.firstOrNull { File(it).isFile }
    val missing =
        buildList {
            if (major == null || major < MIN_NODE_MAJOR) add("Node $MIN_NODE_MAJOR oder neuer")
            if (chrome == null) add("Chrome")
        }
    if (missing.isEmpty()) return chrome!!
    val message = "Browser-Tests übersprungen, es fehlt: ${missing.joinToString(", ")}"
    check(System.getenv("CI") != "true") { "$message (CI=true, deshalb ein Fehler)" }
    Assume.assumeTrue(message, false)
    error("nicht erreichbar")
}

private const val MIN_NODE_MAJOR = 22

private fun nodeMajor(node: String): Int? =
    runCatching {
        val process = ProcessBuilder(node, "--version").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)
        Regex("v(\\d+)\\.")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
    }.getOrNull()
