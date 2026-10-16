package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import io.github.labushuya.emberlight.core.design.motion.LocalNavAnimatedVisibilityScope
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.viewer.gridExitTowardsViewer
import io.github.labushuya.emberlight.feature.gallery.viewer.gridPopEnterFromViewer
import io.github.labushuya.emberlight.feature.gallery.viewer.rememberLastViewedMediaId
import kotlinx.serialization.Serializable

/** Typsichere Route der Galerie. Start-Ziel der App. */
@Serializable
data object GalleryRoute

/**
 * Ziel der Galerie. [onAlbumClick] meldet das Antippen eines Albums mit seiner Ordner-ID (`null` für „Sonstige“),
 * [onMediaClick] das eines Mediums. Der Viewer öffnet sich über die App, siehe `navigateToViewer`.
 */
fun NavGraphBuilder.galleryScreen(
    onAlbumClick: (Long?) -> Unit = {},
    onMediaClick: (MediaItem) -> Unit = {},
) {
    composable<GalleryRoute>(
        exitTransition = { gridExitTowardsViewer() },
        popEnterTransition = { gridPopEnterFromViewer() },
    ) { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
            GalleryScreen(
                onAlbumClick = onAlbumClick,
                onMediaClick = onMediaClick,
                lastViewed = rememberLastViewedMediaId(entry),
            )
        }
    }
}

fun NavController.navigateToGallery(navOptions: NavOptions? = null) {
    navigate(route = GalleryRoute, navOptions = navOptions)
}
