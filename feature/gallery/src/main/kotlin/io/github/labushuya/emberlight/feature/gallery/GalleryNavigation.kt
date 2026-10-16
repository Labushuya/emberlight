package io.github.labushuya.emberlight.feature.gallery

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Typsichere Route der Galerie. Start-Ziel der App. */
@Serializable
data object GalleryRoute

/**
 * Ziel der Galerie. [onAlbumClick] meldet das Antippen eines Albums mit seiner Ordner-ID (`null` für „Sonstige“). Der Viewer
 * ist kein eigenes Ziel: Er liegt als Overlay über dem Raster (ADR 0010).
 */
fun NavGraphBuilder.galleryScreen(onAlbumClick: (Long?) -> Unit = {}) {
    composable<GalleryRoute> {
        GalleryScreen(onAlbumClick = onAlbumClick)
    }
}

fun NavController.navigateToGallery(navOptions: NavOptions? = null) {
    navigate(route = GalleryRoute, navOptions = navOptions)
}
