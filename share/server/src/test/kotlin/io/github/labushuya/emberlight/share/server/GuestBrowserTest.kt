package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.SpaceLimits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun extensionWhileTheOneMinuteWarningIsOpenClosesItAndShowsTheBanner() {
        runScenario(file = "extension.test.mjs", spaceName = "Verlängert", endsInSeconds = 45) { scene ->
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

    @Test
    fun uploadInTheBrowserWithProgressQueueDuplicateRejectionAndLocation() {
        val files = folder.newFolder("upload-files")
        // Nach dem Browser-Lauf prüft der Fall den Gastgeber-Pfad der App: Beitritte und Uploader-Namen erscheinen dort.
        runScenario(
            file = "upload.test.mjs",
            spaceName = SPACE_NAME,
            endsInSeconds = null,
            verify = { scene -> assertHostSees(scene, "Lena", "Omar") },
        ) { scene ->
            val lena = scene.join("Lena")
            val omar = scene.join("Omar")
            scene.primaryGuest = lena.id
            val known = BrowserScene.jpeg(0x884422)
            val knownId = scene.upload(omar.token, "bekannt.jpg", known)
            mapOf(
                "session" to session(scene, lena),
                "knownId" to JsonPrimitive(knownId),
                "fixtures" to
                    fixtures(
                        files,
                        mapOf(
                            "foto" to BrowserScene.jpeg(0x3366CC),
                            "bekannt" to known,
                            "gross" to BrowserScene.photo(1, 1000, 800),
                            "gross2" to BrowserScene.photo(2, 1000, 800),
                            "gross3" to BrowserScene.photo(3, 1000, 800),
                            "gross4" to BrowserScene.photo(4, 1000, 800),
                            "falsch" to "Das ist kein Bild.".toByteArray(Charsets.UTF_8),
                            "zugross" to oversizedJpeg(),
                            "standort" to MediaFixtures.mp4WithGpmdTrack(),
                            "wiederholen" to BrowserScene.jpeg(0x339966),
                            "abbruch" to BrowserScene.jpeg(0x993366),
                            "ziehen" to BrowserScene.jpeg(0x6699AA),
                        ),
                    ),
            )
        }
    }

    @Test
    fun uploadStopsAtAFullSpaceAndKeepsTheRestWaiting() {
        val files = folder.newFolder("storage-files")
        // Drei echte Fotos. Das Limit ist ihre Summe, damit alle drei die Vorabprüfung bestehen. Der Füllstand lässt
        // danach keinen der drei mehr passen.
        val photos = listOf(1L, 2L, 3L).map { BrowserScene.photo(it, 300, 220) }
        val limit = photos.sumOf { it.size.toLong() }
        runScenario(
            file = "upload-storage.test.mjs",
            spaceName = SPACE_NAME,
            endsInSeconds = null,
            limits = SpaceLimits(storageLimitBytes = limit),
        ) { scene ->
            val lena = scene.join("Lena")
            scene.primaryGuest = lena.id
            scene.fillerBytes = (limit - photos.minOf { it.size } + 1).toInt()
            mapOf(
                "session" to session(scene, lena),
                "fixtures" to
                    fixtures(
                        files,
                        mapOf("eins" to photos[0], "zwei" to photos[1], "drei" to photos[2]),
                    ),
            )
        }
    }

    @Test
    fun uploadWaitingWhenTheSpaceEndsGetsTheEndingViewWithEndedAt() {
        val files = folder.newFolder("closed-files")
        runScenario(file = "upload-closed.test.mjs", spaceName = SPACE_NAME, endsInSeconds = 3_600) { scene ->
            val lena = scene.join("Lena")
            scene.primaryGuest = lena.id
            mapOf(
                "session" to session(scene, lena),
                "fixtures" to fixtures(files, mapOf("foto" to BrowserScene.jpeg(0x3366CC))),
            )
        }
    }

    @Test
    fun formatSignaturesAreJudgedTheSameWayInTheBrowser() {
        runScenario(file = "formats.test.mjs", spaceName = SPACE_NAME, endsInSeconds = null) { emptyMap() }
    }

    /** Schreibt die Testdateien in [dir]. Der Schlüssel ist der Name im Browser-Test, die Endung ergibt sich daraus. */
    private fun fixtures(
        dir: File,
        files: Map<String, ByteArray>,
    ): JsonObject =
        buildJsonObject {
            files.forEach { (name, bytes) ->
                val target = File(dir, "$name.${extensionOf(name)}")
                target.writeBytes(bytes)
                put(name, target.absolutePath)
            }
        }

    private fun extensionOf(name: String): String = if (name == "standort") "mp4" else "jpg"

    /** Beginnt wie ein JPEG und ist größer als die Bildgrenze. Die Größenprüfung im Browser lehnt sie vorab ab. */
    private fun oversizedJpeg(): ByteArray =
        ByteArray(SpaceLimits().imageMaxBytes.toInt() + 1).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
            it[2] = 0xFF.toByte()
        }

    /**
     * Prüft nach dem Browser-Lauf den Gastgeber-Pfad der App (Repository auf derselben Datenbank wie der Server). Jeder
     * genannte Name muss als aktiver Teilnehmer stehen und als Uploader eines Mediums erscheinen.
     */
    private fun assertHostSees(
        scene: BrowserScene,
        vararg names: String,
    ) {
        val repository = scene.hostRepository()
        val participants = runBlocking { repository.observeParticipants(scene.spaceId).first() }.map { it.displayName }
        val uploaders =
            runBlocking {
                repository.observeMedia(scene.spaceId, LIST_LIMIT).first()
            }.mapNotNull { it.uploaderName }
        names.forEach { name ->
            assertTrue("Teilnehmer $name fehlt im Gastgeber-Pfad: $participants", name in participants)
            assertTrue("Uploader $name fehlt im Gastgeber-Raster: $uploaders", name in uploaders)
        }
    }

    /** Ein Durchlauf: Space vorbereiten, Server starten, Node mit der Datei laufen lassen, Ergebnis prüfen. */
    private fun runScenario(
        file: String,
        spaceName: String,
        endsInSeconds: Long?,
        limits: SpaceLimits = SpaceLimits(),
        verify: (BrowserScene) -> Unit = {},
        prepare: (BrowserScene) -> Map<String, JsonElement>,
    ) {
        val chrome = requireBrowserTools()
        val endsAt = endsInSeconds?.let { System.currentTimeMillis() + it * 1_000 }
        BrowserScene(folder.newFolder("spaces"), spaceName, endsAt, limits).use { scene ->
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
            verify(scene)
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
        private const val LIST_LIMIT = 200
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
                    "extend-hour" -> scene.extendBy(HOUR_MILLIS)
                    "hold-uploads" -> scene.holdUploadSlots()
                    "release-uploads" -> scene.releaseUploadSlots()
                    "fill-storage" -> scene.fillStorage()
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
        const val HOUR_MILLIS = 3_600_000L
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
