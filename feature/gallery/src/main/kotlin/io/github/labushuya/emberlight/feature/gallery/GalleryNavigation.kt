package io.github.labushuya.emberlight.feature.gallery

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import io.github.labushuya.emberlight.core.media.model.MediaItem
import kotlinx.serialization.Serializable

/** Typsichere Route der Galerie. Start-Ziel der App. */
@Serializable
data object GalleryRoute

/**
 * Ziel der Galerie. [onAlbumClick] meldet das Antippen eines Albums mit seiner Ordner-ID (`null` für „Sonstige“),
 * [onMediaClick] das eines Mediums.
 */
fun NavGraphBuilder.galleryScreen(
    onAlbumClick: (Long?) -> Unit = {},
    onMediaClick: (MediaItem) -> Unit = {},
) {
    composable<GalleryRoute> {
        GalleryScreen(onAlbumClick = onAlbumClick, onMediaClick = onMediaClick)
    }
}

fun NavController.navigateToGallery(navOptions: NavOptions? = null) {
    navigate(route = GalleryRoute, navOptions = navOptions)
}
