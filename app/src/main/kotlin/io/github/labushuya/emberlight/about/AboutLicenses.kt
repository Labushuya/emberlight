package io.github.labushuya.emberlight.about

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import com.mikepenz.aboutlibraries.Libs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Locale

/** Name der erzeugten Ressource `raw/aboutlibraries.json`. Das Gradle-Plugin legt sie an (ADR 0020). */
internal const val LIBRARY_RESOURCE = "aboutlibraries"

/** Schriftlizenzen aus den Assets von `core:design`. Die Dateien liegen dort mit der Schrift zusammen. */
internal val FONT_LICENSE_ASSETS =
    listOf(
        "Fraunces" to "licenses/fraunces_OFL.txt",
        "Inter" to "licenses/inter_OFL.txt",
    )

/** Volltext der GPL-3.0, Asset aus `core:design`. Dieselbe Datei wie `LICENSE` im Repo. */
internal const val GPL_ASSET = "licenses/gpl-3.0.txt"

/** Eine Lizenz einer Bibliothek. [text] fehlt, wenn das Plugin keinen Volltext geliefert hat. */
data class LicenseEntry(
    val name: String,
    val text: String?,
)

/** Eine Bibliothek der Liste, so wie der Bildschirm sie zeigt. [id] ist die Maven-Koordinate `gruppe:artefakt`. */
data class LibraryEntry(
    val id: String,
    val name: String,
    val version: String,
    val licenses: List<LicenseEntry>,
)

/** Ergebnis des Ladens der Bibliotheksliste. */
sealed interface LibraryList {
    data class Loaded(
        val libraries: List<LibraryEntry>,
    ) : LibraryList

    /** Der Build enthält keine Liste. Debug-Builds haben keine, weil dort Werkzeug-Abhängigkeiten dazukämen (ADR 0020). */
    data object NotIncluded : LibraryList

    /** Die Liste ist vorhanden, lässt sich aber nicht lesen. */
    data object Unreadable : LibraryList
}

/** Ein Lizenzdokument, etwa eine Schriftlizenz oder die GPL. [text] ist `null`, wenn die Datei fehlt. */
data class LicenseDocument(
    val title: String,
    val text: String?,
)

/** Alles, was der Bildschirm „Über & Lizenzen“ zeigt. */
data class AboutContent(
    val versionName: String,
    val libraries: LibraryList,
    val fonts: List<LicenseDocument>,
    val gpl: LicenseDocument,
)

/**
 * Liest das JSON der Bibliotheksliste. Ungültiges JSON ergibt [LibraryList.Unreadable] und keine Ausnahme. Eine Bibliothek
 * ohne Lizenzangabe bleibt in der Liste, mit leerer Lizenzliste. So fällt sie auf, statt zu verschwinden.
 */
internal fun parseLibraryList(json: String): LibraryList =
    try {
        // Die Bibliothek verwirft ungültiges JSON still und liefert eine leere Liste. Deshalb erst die Syntax prüfen.
        Json.parseToJsonElement(json)
        val libs = Libs.Builder().withJson(json).build()
        LibraryList.Loaded(
            libraries =
                libs.libraries
                    .map { library ->
                        LibraryEntry(
                            id = library.uniqueId,
                            name = library.name,
                            version = library.artifactVersion.orEmpty(),
                            licenses = library.licenses.map { LicenseEntry(name = it.name, text = it.licenseContent) },
                        )
                    }.sortedBy { it.name.lowercase(Locale.ROOT) },
        )
    } catch (e: SerializationException) {
        LibraryList.Unreadable
    }

/**
 * Lädt die Bibliotheksliste aus der Ressource. Fehlt die Ressource, ist das ein gewollter Zustand des Builds und kein Fehler.
 */
@SuppressLint("DiscouragedApi") // Die Ressource gibt es nur in Builds mit Liste. Dieser Lookup prüft das zur Laufzeit.
internal fun loadLibraryList(context: Context): LibraryList {
    val id = context.resources.getIdentifier(LIBRARY_RESOURCE, "raw", context.packageName)
    if (id == 0) return LibraryList.NotIncluded
    return try {
        val json =
            context.resources
                .openRawResource(id)
                .bufferedReader()
                .use { it.readText() }
        parseLibraryList(json)
    } catch (e: IOException) {
        LibraryList.Unreadable
    } catch (e: Resources.NotFoundException) {
        LibraryList.Unreadable
    }
}

/** Liest eine Textdatei aus den Assets. Fehlt sie oder ist sie nicht lesbar, ist das Ergebnis `null`. */
internal fun readAsset(
    context: Context,
    path: String,
): String? =
    try {
        context.assets
            .open(path)
            .bufferedReader()
            .use { it.readText() }
    } catch (e: IOException) {
        null
    }

/** Versionsname der installierten App (`versionName`). Ohne Eintrag ein Platzhalter, nie eine Ausnahme. */
internal fun readVersionName(context: Context): String =
    try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

/** Sammelt den gesamten Inhalt des Bildschirms. Blockiert, deshalb nur auf einem Hintergrund-Dispatcher aufrufen. */
internal fun loadAboutContent(context: Context): AboutContent =
    AboutContent(
        versionName = readVersionName(context),
        libraries = loadLibraryList(context),
        fonts = FONT_LICENSE_ASSETS.map { (title, path) -> LicenseDocument(title, readAsset(context, path)) },
        gpl = LicenseDocument(title = "GPL-3.0-or-later", text = readAsset(context, GPL_ASSET)),
    )
