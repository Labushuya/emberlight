package io.github.labushuya.emberlight

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import io.github.labushuya.emberlight.core.design.motion.LocalSharedTransitionScope
import io.github.labushuya.emberlight.extensions.AppOverlay
import io.github.labushuya.emberlight.feature.gallery.GalleryRoute
import io.github.labushuya.emberlight.feature.gallery.album.albumScreen
import io.github.labushuya.emberlight.feature.gallery.album.navigateToAlbum
import io.github.labushuya.emberlight.feature.gallery.galleryScreen
import io.github.labushuya.emberlight.feature.gallery.navigateToGallery
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerRoute
import io.github.labushuya.emberlight.feature.gallery.viewer.navigateToViewer
import io.github.labushuya.emberlight.feature.gallery.viewer.popViewer
import io.github.labushuya.emberlight.feature.gallery.viewer.viewerScreen
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
    overlays: List<AppOverlay> = emptyList(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        EmberlightScaffold(navController = navController, modifier = Modifier.fillMaxSize())
        // Zusätzliche Anzeigen (etwa DEMO in Debug-Builds). Ohne Beiträge ist das leer und das Layout unverändert.
        overlays.forEach { overlay -> overlay.Overlay(Modifier.align(Alignment.TopEnd)) }
    }
}

@Composable
private fun EmberlightScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    // Im Viewer verschwindet die Leiste (Handy) bzw. die Rail (großes Display), der Inhalt nutzt den ganzen Bildschirm.
    val inViewer = currentDestination?.hasRoute<ViewerRoute>() == true
    // Außerhalb des Viewers: Leiste unten auf kompakten Breiten, sonst Rail. Der Standard der Bibliothek ergab, als Parameter
    // übergeben, ein anderes Leistenlayout im Golden. Deshalb ist die Breitenzuordnung hier ausgeschrieben.
    val density = LocalDensity.current
    val windowWidth =
        with(density) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    val compactWidth = windowWidth < COMPACT_WIDTH
    val defaultLayout = if (compactWidth) NavigationSuiteType.NavigationBar else NavigationSuiteType.NavigationRail

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = if (inViewer) NavigationSuiteType.None else defaultLayout,
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
        // Der Übergangs-Scope umschließt den NavHost. Raster und Viewer holen ihn sich über CompositionLocals (ADR 0010).
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                NavHost(navController = navController, startDestination = GalleryRoute) {
                    galleryScreen(
                        onAlbumClick = { bucketId -> navController.navigateToAlbum(bucketId) },
                        onMediaClick = { item ->
                            navController.navigateToViewer(mediaId = item.id, bucketId = null, scopeAll = true)
                        },
                    )
                    albumScreen(
                        onBack = { navController.popBackStack() },
                        // Im Album ist jedes Element aus diesem Ordner, bucketId kommt also von dort.
                        onMediaClick = { item ->
                            navController.navigateToViewer(
                                mediaId = item.id,
                                bucketId = item.bucketId,
                                scopeAll = false,
                            )
                        },
                    )
                    viewerScreen(onBack = { lastViewedMediaId -> navController.popViewer(lastViewedMediaId) })
                    spacesScreen()
                }
            }
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

/** Breite, unterhalb derer die Navigation unten liegt (Medium-Grenze der Fenstergrößen). */
private val COMPACT_WIDTH = 600.dp
