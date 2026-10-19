package io.github.labushuya.emberlight.feature.space

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Typsichere Route der Spaces-Liste. Auf großen Displays steht das Detail daneben, auf dem Handy als eigenes Ziel. */
@Serializable
data object SpacesRoute

/** Detail eines Space. [id] ist die Kennung als Text (Base64url, 22 Zeichen). */
@Serializable
data class SpaceDetailRoute(
    val id: String,
)

/** Formular für Anlegen (ohne [id]) oder Bearbeiten eines Entwurfs. */
@Serializable
data class SpaceEditRoute(
    val id: String? = null,
)

/**
 * Ziele der Spaces. [onCreate] öffnet das Formular für einen neuen Space, [onOpen] das Detail, [onEdit] das Formular eines
 * vorhandenen Space. [onBack] schließt Detail und Formular. Die Liste liegt als Ziel der Navigationsleiste (ADR 0002).
 */
fun NavGraphBuilder.spacesScreens(
    onCreate: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    composable<SpacesRoute> {
        SpacesScreen(onCreate = onCreate, onOpen = onOpen, onEdit = onEdit)
    }
    composable<SpaceDetailRoute> {
        SpaceDetailScreen(onEdit = onEdit, onBack = onBack)
    }
    composable<SpaceEditRoute> {
        SpaceEditScreen(onDone = onBack)
    }
}

fun NavController.navigateToSpaces(navOptions: NavOptions? = null) {
    navigate(route = SpacesRoute, navOptions = navOptions)
}

fun NavController.navigateToSpaceDetail(id: String) {
    navigate(route = SpaceDetailRoute(id = id))
}

fun NavController.navigateToSpaceEdit(id: String? = null) {
    navigate(route = SpaceEditRoute(id = id))
}
