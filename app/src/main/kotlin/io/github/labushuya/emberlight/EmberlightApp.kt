package io.github.labushuya.emberlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import io.github.labushuya.emberlight.about.aboutScreen
import io.github.labushuya.emberlight.about.navigateToAbout
import io.github.labushuya.emberlight.core.design.motion.EmberlightMotion
import io.github.labushuya.emberlight.core.design.motion.LocalNavigationChrome
import io.github.labushuya.emberlight.core.design.motion.NavigationChrome
import io.github.labushuya.emberlight.extensions.AppOverlay
import io.github.labushuya.emberlight.feature.gallery.GalleryRoute
import io.github.labushuya.emberlight.feature.gallery.album.albumScreen
import io.github.labushuya.emberlight.feature.gallery.album.navigateToAlbum
import io.github.labushuya.emberlight.feature.gallery.galleryScreen
import io.github.labushuya.emberlight.feature.gallery.navigateToGallery
import io.github.labushuya.emberlight.feature.gallery.viewer.LocalViewerImageSources
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerImageSourceContribution
import io.github.labushuya.emberlight.feature.space.navigateToSpaceDetail
import io.github.labushuya.emberlight.feature.space.retention.RetentionRoutes
import io.github.labushuya.emberlight.feature.space.navigateToSpaceEdit
import io.github.labushuya.emberlight.feature.space.navigateToSpaceRunning
import io.github.labushuya.emberlight.feature.space.navigateToSpaceSetup
import io.github.labushuya.emberlight.feature.space.navigateToSpaces
import io.github.labushuya.emberlight.feature.space.spacesScreens
import io.github.labushuya.emberlight.share.server.SpaceServerService

/**
 * Wurzel der Oberfläche: Navigation (unten am Handy, Rail auf großen Displays) und der NavHost mit den Top-Level-Zielen.
 *
 * Die Leiste ist eine eigene Komponente und nicht die Navigation-Suite-Bibliothek, weil sie sich animiert ausblenden muss,
 * wenn ein Vollbild-Viewer offen ist (ADR 0010). Ihr Zustand kommt über [LocalNavigationChrome]. Der Inhalt liegt im
 * verbleibenden Platz. Die Rasterzeilen bleiben oben verankert, Spalten brechen dabei nicht neu um.
 */
@Composable
fun EmberlightApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    overlays: List<AppOverlay> = emptyList(),
    viewerImageSources: List<ViewerImageSourceContribution> = emptyList(),
    launchRoute: String? = null,
    onLaunchRouteConsume: () -> Unit = {},
) {
    // Ziel aus einer Benachrichtigung des Dienstes (Paket 3.3). Es wird einmal geöffnet, danach ist es verbraucht.
    val routeHandled = rememberUpdatedState(onLaunchRouteConsume)
    LaunchedEffect(launchRoute) {
        val route = launchRoute ?: return@LaunchedEffect
        navController.openLaunchRoute(route)
        routeHandled.value()
    }
    Box(modifier = modifier.fillMaxSize()) {
        // Zusätzliche Bildquellen des Viewers (ADR 0011). Ohne Beiträge gilt die Standardquelle.
        CompositionLocalProvider(LocalViewerImageSources provides viewerImageSources) {
            EmberlightScaffold(navController = navController, modifier = Modifier.fillMaxSize())
        }
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
    val chrome = remember { NavigationChrome() }
    val density = LocalDensity.current
    val windowWidth =
        with(density) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    val compact = windowWidth < COMPACT_WIDTH
    val shown = !chrome.hidden

    CompositionLocalProvider(LocalNavigationChrome provides chrome) {
        if (compact) {
            Column(modifier = modifier) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Solange die Leiste da ist, trägt sie die Insets unten. Der Inhalt darüber übernimmt sie nicht noch einmal.
                            .then(if (shown) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                ) {
                    AppNavHost(navController)
                }
                AnimatedVisibility(
                    visible = shown,
                    enter = expandVertically(EmberlightMotion.chromeSpec()) + fadeIn(EmberlightMotion.chromeSpec()),
                    exit = shrinkVertically(EmberlightMotion.chromeSpec()) + fadeOut(EmberlightMotion.chromeSpec()),
                ) {
                    NavigationBar(
                        // Die Oberkante merkt sich der Zustand: Beim Rückweg aus dem Viewer braucht das Raster die Grenze (ADR 0010).
                        // Ausblenden misst die Leiste auf Höhe 0 (Top 0). Diese Werte werden nicht übernommen, sonst gälte jede Kachel als unsichtbar.
                        modifier =
                            Modifier.onGloballyPositioned { coords ->
                                if (!chrome.hidden) chrome.bottomBarTopPx = coords.boundsInRoot().top
                            },
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination.isTopLevel(destination)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(destination) },
                                icon = { DestinationIcon(destination, selected) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            }
        } else {
            SideEffect { chrome.bottomBarTopPx = null }
            Row(modifier = modifier) {
                AnimatedVisibility(
                    visible = shown,
                    enter = expandHorizontally(EmberlightMotion.chromeSpec()) + fadeIn(EmberlightMotion.chromeSpec()),
                    exit = shrinkHorizontally(EmberlightMotion.chromeSpec()) + fadeOut(EmberlightMotion.chromeSpec()),
                ) {
                    NavigationRail {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination.isTopLevel(destination)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(destination) },
                                icon = { DestinationIcon(destination, selected) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AppNavHost(navController)
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(
    destination: TopLevelDestination,
    selected: Boolean,
) {
    Icon(
        painter = painterResource(if (selected) destination.selectedIcon else destination.unselectedIcon),
        // Das Label beschreibt das Ziel bereits, das Icon ist dekorativ.
        contentDescription = null,
    )
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = GalleryRoute) {
        galleryScreen(
            onAlbumClick = { bucketId -> navController.navigateToAlbum(bucketId) },
            onAbout = { navController.navigateToAbout() },
        )
        albumScreen(onBack = { navController.popBackStack() })
        aboutScreen(onBack = { navController.popBackStack() })
        spacesScreens(
            onCreate = { navController.navigateToSpaceEdit() },
            onOpen = { id -> navController.navigateToSpaceDetail(id) },
            onEdit = { id -> navController.navigateToSpaceEdit(id) },
            onStart = { id -> navController.navigateToSpaceSetup(id, startMode = true) },
            onSetup = { id -> navController.navigateToSpaceSetup(id) },
            onOpenRunning = { id -> navController.navigateToSpaceRunning(id) },
            onRunning = { id -> navController.navigateToSpaceRunning(id, afterStart = true) },
            onBack = { navController.popBackStack() },
        )
    }
}

/** Übersetzt das Ziel aus einer Benachrichtigung (ADR 0017). Unbekannte Ziele werden ignoriert. */
private fun NavHostController.openLaunchRoute(route: String) {
    when (route) {
        SpaceServerService.ROUTE_CONFIRM_END -> navigateToSpaceRunning(confirmEnd = true)
        SpaceServerService.ROUTE_OPEN_SPACE, SpaceServerService.ROUTE_CONFIRM_NETWORK -> navigateToSpaceRunning()
        else ->
            RetentionRoutes.parse(route)?.let { target ->
                navigateToSpaceDetail(target.spaceId, confirmPurge = target.confirmPurge)
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
