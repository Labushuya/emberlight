package io.github.labushuya.emberlight

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import io.github.labushuya.emberlight.feature.gallery.GalleryRoute
import io.github.labushuya.emberlight.feature.gallery.album.albumScreen
import io.github.labushuya.emberlight.feature.gallery.album.navigateToAlbum
import io.github.labushuya.emberlight.feature.gallery.galleryScreen
import io.github.labushuya.emberlight.feature.gallery.navigateToGallery
import io.github.labushuya.emberlight.feature.space.navigateToSpaces
import io.github.labushuya.emberlight.feature.space.spacesScreen

/**
 * Wurzel der Oberfläche: Navigationsleiste (unten am Handy, Rail auf großen Displays) und der NavHost
 * mit den Top-Level-Zielen.
 */
@Composable
fun EmberlightApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentDestination.isTopLevel(destination)
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = {
                        Icon(
                            painter =
                                painterResource(
                                    if (selected) destination.selectedIcon else destination.unselectedIcon,
                                ),
                            // Das Label beschreibt das Ziel bereits, das Icon ist dekorativ.
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = GalleryRoute) {
            galleryScreen(onAlbumClick = { bucketId -> navController.navigateToAlbum(bucketId) })
            albumScreen(onBack = { navController.popBackStack() })
            spacesScreen()
        }
    }
}

/** Prüft, ob das aktuelle Ziel (oder einer seiner Vorfahren) zum Top-Level-Ziel gehört. */
private fun NavDestination?.isTopLevel(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { entry -> destination.routes.any { entry.hasRoute(it) } } == true

/**
 * Standardmuster für Top-Level-Wechsel: Start-Ziel mit Zustand sichern, keine Kopien des Ziels stapeln,
 * beim erneuten Antippen den gesicherten Zustand wiederherstellen.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    val topLevelOptions =
        navOptions {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    when (destination) {
        TopLevelDestination.GALLERY -> navigateToGallery(topLevelOptions)
        TopLevelDestination.SPACES -> navigateToSpaces(topLevelOptions)
    }
}
