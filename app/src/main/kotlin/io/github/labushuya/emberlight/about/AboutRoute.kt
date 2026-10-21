package io.github.labushuya.emberlight.about

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/**
 * Typsichere Route „Über & Lizenzen“ (ADR 0020). Ein Ziel über der Galerie, öffnet sich aus deren Überlaufmenü. Die Leiste
 * bleibt sichtbar, die Galerie bleibt dort markiert.
 */
@Serializable
data object AboutRoute

fun NavGraphBuilder.aboutScreen(onBack: () -> Unit) {
    composable<AboutRoute> {
        AboutScreen(onBack = onBack)
    }
}

fun NavController.navigateToAbout(navOptions: NavOptions? = null) {
    navigate(route = AboutRoute, navOptions = navOptions)
}
