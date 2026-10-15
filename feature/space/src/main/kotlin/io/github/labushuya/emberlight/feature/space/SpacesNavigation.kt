package io.github.labushuya.emberlight.feature.space

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Typsichere Route der Spaces. */
@Serializable
data object SpacesRoute

fun NavGraphBuilder.spacesScreen() {
    composable<SpacesRoute> {
        SpacesScreen()
    }
}

fun NavController.navigateToSpaces(navOptions: NavOptions? = null) {
    navigate(route = SpacesRoute, navOptions = navOptions)
}
