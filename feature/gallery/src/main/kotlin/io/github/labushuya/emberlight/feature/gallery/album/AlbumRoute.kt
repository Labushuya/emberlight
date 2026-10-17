package io.github.labushuya.emberlight.feature.gallery.album

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/**
 * Typsichere Route der Album-Ansicht. Trägt nur die Ordner-ID, den Namen liest die Ansicht aus dem Index. `null` steht für
 * das Sammelalbum „Sonstige“ (Einträge ohne Ordner).
 */
@Serializable
data class AlbumRoute(
    val bucketId: Long?,
)

/** Ziel der Album-Ansicht. [onBack] führt zurück in die Alben-Übersicht. Der Viewer liegt als Overlay über dem Raster. */
fun NavGraphBuilder.albumScreen(onBack: () -> Unit) {
    composable<AlbumRoute> {
        AlbumScreen(onBack = onBack)
    }
}

/** Öffnet das Album mit der Ordner-ID, `null` für „Sonstige“. */
fun NavController.navigateToAlbum(
    bucketId: Long?,
    navOptions: NavOptions? = null,
) {
    navigate(route = AlbumRoute(bucketId = bucketId), navOptions = navOptions)
}
