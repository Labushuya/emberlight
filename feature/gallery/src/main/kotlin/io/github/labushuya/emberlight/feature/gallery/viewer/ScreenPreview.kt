package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.Extras
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lange Kante der Vorschau in Bildschirmgröße (Pixel). Größer als eine Kachel, kleiner als das Original. Das Vollbild skaliert
 * es auf die Displaygröße, eine größere Vorschau kostet Speicher ohne sichtbaren Gewinn (ADR 0010, Korrektur 4).
 */
const val SCREEN_PREVIEW_EDGE_PX = 1440

/**
 * Extra der Anfrage: Sie gilt der Vorschau in Bildschirmgröße. Fetcher synthetischer Motive (Demo) rendern dann in
 * [SCREEN_PREVIEW_EDGE_PX] statt in der Kachelgröße.
 */
val ScreenPreviewExtra: Extras.Key<Boolean> = Extras.Key(false)

/** Dauer der Überblendung vom Vorschaubild zur Bildschirm-Vorschau im Hero (ms). */
internal const val SCREEN_FADE_MS = 100

/** Schlüssel im Speicher-Cache von Coil. Stabil je Medium und getrennt vom Schlüssel der Kachel. */
internal fun screenPreviewKey(uri: String): String = "media-screen:$uri"

/**
 * Anfrage für die Vorschau in Bildschirmgröße. Das Original bleibt unangetastet: Coil bekommt die Content-URI als
 * [MediaThumbnail]. Ab API 29 lädt das System die Vorschau über `loadThumbnail` und cached sie selbst. Der Disk-Cache ist für
 * diese Anfrage ausdrücklich aus, damit keine Kopie privater Medien im App-Cache landet (ADR 0008).
 */
internal fun screenPreviewRequest(
    context: Context,
    item: ViewerItem,
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(MediaThumbnail(uri = item.uri, type = item.thumbnailType()))
        .size(SCREEN_PREVIEW_EDGE_PX, SCREEN_PREVIEW_EDGE_PX)
        .memoryCacheKey(screenPreviewKey(item.uri))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .apply { extras.set(ScreenPreviewExtra, true) }
        .build()

/**
 * Vorschau in Bildschirmgröße der Viewer-Seiten (ADR 0010, Korrektur 4). Hält die Bitmaps des gezeigten Elements und seiner
 * Nachbarn (±1) und lädt sie über Coil. Der Speicher-Cache bekommt nur diese Einträge; alles andere wird beim Wechsel der
 * Seite wieder entfernt, damit Raster-Thumbnails nicht verdrängt werden.
 *
 * Priorität: Das gezeigte Element zuerst. Die Nachbarn starten erst, wenn es da ist, und ein Seitenwechsel bricht alle
 * Ladevorgänge ab, die nicht mehr gebraucht werden.
 */
@Stable
internal class ScreenPreviews(
    private val scope: CoroutineScope,
    private val imageLoader: ImageLoader,
    private val context: Context,
) {
    /** Zählt jede Änderung der gehaltenen Bitmaps. Zeichnung und Komposition lesen ihn und folgen dem Laden. */
    private var arrivals by mutableIntStateOf(0)

    /** Überblendung vom Vorschaubild zur Bildschirm-Vorschau im Hero, 0 bis 1. */
    var mix: Float by mutableFloatStateOf(1f)
        private set

    private val held = HashMap<String, ImageBitmap>()
    private val jobs = HashMap<String, Job>()
    private val owned = HashSet<String>()
    private var fade: Job? = null
    private var heroMarked: String? = null

    /**
     * Die Bildschirm-Vorschau von [uri], `null` solange sie nicht geladen ist. Der Zähler wird mitgelesen, damit Komposition und
     * Zeichnung dem Laden folgen. Der Zähler ist nie negativ, die Bedingung ist also immer wahr.
     */
    fun bitmapOf(uri: String): ImageBitmap? = held[uri].takeIf { arrivals >= 0 }

    /**
     * Wählt das gezeigte Element [index] aus [items] und lädt seine Vorschau sowie die der Nachbarn. Aufruf bei jedem Wechsel
     * und jedem Eintreffen, die Methode ist idempotent.
     */
    fun focus(
        items: List<ViewerItem>,
        index: Int,
    ) {
        val current = items.getOrNull(index) ?: return
        val neighbors = listOfNotNull(items.getOrNull(index - 1), items.getOrNull(index + 1))
        retain((neighbors + current).map { it.uri }.toSet())
        if (held.containsKey(current.uri)) {
            // Schon da, etwa nach dem Zurückblättern: kein Überblenden, dann die Nachbarn im Hintergrund.
            mix = 1f
            neighbors.forEach { load(it, primary = false) }
        } else {
            load(current, primary = true)
        }
    }

    /** Meldet, dass [uri] im Hero übernommen wurde. Die Marke erscheint einmal je Element. */
    fun markHeroUse(
        id: Long,
        uri: String,
    ) {
        if (heroMarked == uri) return
        heroMarked = uri
        ViewerTrace.mark("im Hero übernommen", id)
    }

    /** Gibt alles frei: Ladevorgänge, Bitmaps und die Einträge im Speicher-Cache. Aufruf beim Schließen des Viewers. */
    fun release() {
        fade?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        owned.forEach { uri -> imageLoader.memoryCache?.remove(MemoryCache.Key(screenPreviewKey(uri))) }
        owned.clear()
        held.clear()
        heroMarked = null
        arrivals++
    }

    private fun load(
        item: ViewerItem,
        primary: Boolean,
    ) {
        if (held.containsKey(item.uri) || jobs[item.uri]?.isActive == true) return
        if (primary) ViewerTrace.mark("Bildschirm-Vorschau angefordert", item.id)
        owned += item.uri
        jobs[item.uri] =
            scope.launch {
                val result = imageLoader.execute(screenPreviewRequest(context, item))
                val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@launch
                held[item.uri] = bitmap.asImageBitmap()
                arrivals++
                ViewerTrace.mark("Bildschirm-Vorschau bereit", item.id)
                if (primary) startFade()
            }
    }

    /** Hält nur noch die Elemente [wanted]. Alles andere wird abgebrochen und aus dem Speicher-Cache entfernt. */
    private fun retain(wanted: Set<String>) {
        jobs.keys.filterNot { it in wanted }.forEach { uri -> jobs.remove(uri)?.cancel() }
        val stale = owned.filterNot { it in wanted }
        if (stale.isEmpty()) return
        stale.forEach { uri ->
            owned.remove(uri)
            held.remove(uri)
            imageLoader.memoryCache?.remove(MemoryCache.Key(screenPreviewKey(uri)))
        }
        arrivals++
    }

    private fun startFade() {
        fade?.cancel()
        mix = 0f
        fade =
            scope.launch {
                animate(initialValue = 0f, targetValue = 1f, animationSpec = tween(SCREEN_FADE_MS)) { value, _ ->
                    mix =
                        value
                }
            }
    }
}
