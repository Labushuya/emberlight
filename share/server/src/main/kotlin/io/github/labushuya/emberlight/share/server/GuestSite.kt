package io.github.labushuya.emberlight.share.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Quelle der statischen Gäste-Dateien (ADR 0018, Gäste-Webseite W1). Die Produktion liest die App-Assets unter
 * `guest/`, Tests lesen denselben Ordner aus dem Modul. Der Server kennt keine Android-Typen.
 */
fun interface GuestAssets {
    /** Inhalt der Datei [name] relativ zu `guest/`, `null` wenn es sie nicht gibt. */
    fun read(name: String): ByteArray?
}

/**
 * Gäste-Seite. Die Dateien liegen ohne Zugriffsprüfung unter festen Pfaden, weil sie keine Daten des Space enthalten.
 * Jede Antwort trägt die Richtlinie [CONTENT_SECURITY_POLICY] und `Referrer-Policy: no-referrer`. Die Seite enthält keine
 * Inline-Skripte und keine Inline-Stile. Ausgegeben werden Daten nur per `textContent` (ADR 0018, Escaping).
 */
internal object GuestSite {
    const val CONTENT_SECURITY_POLICY =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; " +
            "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

    private const val HTML = "text/html; charset=utf-8"
    private const val CSS = "text/css; charset=utf-8"
    private const val JS = "text/javascript; charset=utf-8"
    private const val JSON = "application/json; charset=utf-8"

    /** Pfad, Dateiname und Content-Type. Die Gäste-Seite ist nur über genau diese Pfade erreichbar. */
    val FILES: Map<String, Pair<String, String>> =
        mapOf(
            "/" to ("index.html" to HTML),
            "/guest.css" to ("guest.css" to CSS),
            "/guest.js" to ("guest.js" to JS),
            "/i18n/de.json" to ("i18n/de.json" to JSON),
            "/i18n/en.json" to ("i18n/en.json" to JSON),
        )

    fun isPublicPath(path: String): Boolean = path in FILES
}

/** Routen der Gäste-Seite unter `/`. Liegt vor der Space-API, hinter derselben Host- und Netzprüfung. */
internal fun Route.guestSite(assets: GuestAssets) {
    GuestSite.FILES.forEach { (path, spec) ->
        val (file, type) = spec
        get(path) {
            val bytes = assets.read(file)
            if (bytes == null) {
                call.respondText("", status = HttpStatusCode.NotFound)
                return@get
            }
            call.response.headers.append("Content-Security-Policy", GuestSite.CONTENT_SECURITY_POLICY)
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondBytes(bytes, ContentType.parse(type))
        }
    }
}

/** Gäste-Dateien aus den App-Assets unter `guest/`. Fehlende Dateien liefern `null`. */
internal class AssetGuestAssets(
    private val assets: android.content.res.AssetManager,
) : GuestAssets {
    override fun read(name: String): ByteArray? =
        try {
            assets.open("$FOLDER/$name").use { it.readBytes() }
        } catch (missing: java.io.IOException) {
            null
        }

    private companion object {
        const val FOLDER = "guest"
    }
}
