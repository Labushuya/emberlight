package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import io.github.labushuya.emberlight.core.design.motion.LocalNavAnimatedVisibilityScope
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaScope
import kotlinx.serialization.Serializable

/**
 * Typsichere Route des vollbildigen Viewers.
 *
 * [mediaId] ist das Element, das geöffnet wird. [scopeAll] `true` heißt: alle Einträge der Galerie (Zeitleiste), sonst die
 * Einträge des Albums [bucketId] (`null` für „Sonstige“). Der Viewer zeigt damit genau die Reihenfolge des Rasters, aus dem
 * er geöffnet wurde.
 */
@Serializable
data class ViewerRoute(
    val mediaId: Long,
    val bucketId: Long?,
    val scopeAll: Boolean,
)

/** Geltungsbereich des Viewers, derselbe wie im Raster, aus dem er geöffnet wurde. */
internal fun ViewerRoute.scope(): MediaScope = if (scopeAll) MediaScope.All else MediaScope.Bucket(bucketId)

/**
 * Ziel des Viewers. [onBack] führt zurück ins Raster und meldet die zuletzt angesehene Medien-ID (`null`, wenn keine mehr
 * da ist). Das Raster scrollt dorthin, siehe [popViewer].
 */
fun NavGraphBuilder.viewerScreen(onBack: (lastViewedMediaId: Long?) -> Unit) {
    composable<ViewerRoute>(
        enterTransition = { viewerEnterTransition() },
        popExitTransition = { viewerPopExitTransition() },
    ) {
        // Der Sichtbarkeits-Scope dieses Ziels, damit die Seiten die gemeinsamen Elemente finden (ADR 0010).
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
            ViewerScreen(onBack = onBack)
        }
    }
}

/** Öffnet den Viewer mit dem Element [mediaId] im Geltungsbereich des Rasters: alle Einträge oder ein Album. */
fun NavController.navigateToViewer(
    mediaId: Long,
    bucketId: Long?,
    scopeAll: Boolean,
    navOptions: NavOptions? = null,
) {
    navigate(route = ViewerRoute(mediaId = mediaId, bucketId = bucketId, scopeAll = scopeAll), navOptions = navOptions)
}

/**
 * Zurück ins Raster. [lastViewedMediaId] wird im Eintrag darunter abgelegt, damit das Raster das Element sichtbar macht.
 * Ohne Wert (etwa bei entzogener Berechtigung) bleibt die Scrollposition des Rasters unverändert.
 */
fun NavController.popViewer(lastViewedMediaId: Long?) {
    if (lastViewedMediaId != null) {
        previousBackStackEntry?.savedStateHandle?.set(LAST_VIEWED_MEDIA_ID, lastViewedMediaId)
    }
    popBackStack()
}

/** Schlüssel im Eintrag des Rasters für die zuletzt angesehene Medien-ID. */
internal const val LAST_VIEWED_MEDIA_ID = "gallery.lastViewedMediaId"

/**
 * Zuletzt angesehene Medien-ID für ein Raster. [mediaId] ist `null`, solange der Viewer nichts gemeldet hat. [onHandled]
 * löscht die Meldung, damit ein späteres Wiederkommen nicht erneut springt.
 */
@Immutable
internal data class LastViewedMediaId(
    val mediaId: Long?,
    val onHandled: () -> Unit,
) {
    companion object {
        val None: LastViewedMediaId = LastViewedMediaId(mediaId = null, onHandled = {})
    }
}

/** Liest die zuletzt angesehene Medien-ID aus dem Eintrag des Rasters. */
@Composable
internal fun rememberLastViewedMediaId(entry: NavBackStackEntry): LastViewedMediaId {
    val handle = entry.savedStateHandle
    val mediaId by handle.getStateFlow<Long?>(LAST_VIEWED_MEDIA_ID, null).collectAsStateWithLifecycle()
    return LastViewedMediaId(mediaId = mediaId, onHandled = { handle[LAST_VIEWED_MEDIA_ID] = null })
}
