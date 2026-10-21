package io.github.labushuya.emberlight.feature.space

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.labushuya.emberlight.feature.space.run.SpaceRunningScreen
import io.github.labushuya.emberlight.feature.space.setup.SpaceSetupScreen
import kotlinx.serialization.Serializable

/** Typsichere Route der Spaces-Liste. Auf großen Displays steht das Detail daneben, auf dem Handy als eigenes Ziel. */
@Serializable
data object SpacesRoute

/**
 * Detail eines Space. [id] ist die Kennung als Text (Base64url, 22 Zeichen). [confirmPurge] öffnet direkt die Bestätigung
 * „Jetzt löschen“, etwa aus einer Benachrichtigung (Paket 3.6b). Löschen geschieht erst nach der Bestätigung.
 */
@Serializable
data class SpaceDetailRoute(
    val id: String,
    val confirmPurge: Boolean = false,
)

/** Formular für Anlegen (ohne [id]) oder Bearbeiten eines Entwurfs. */
@Serializable
data class SpaceEditRoute(
    val id: String? = null,
)

/**
 * Einrichtung eines Entwurfs (Paket 3.3). Mit [startMode] steht der Start-Knopf unten, der Weg kommt aus „Space starten“
 * im Detail. Ohne [startMode] ist es die Einrichtung allein, etwa aus dem Laufenden Space.
 */
@Serializable
data class SpaceSetupRoute(
    val id: String,
    val startMode: Boolean = false,
)

/**
 * Laufender Space (Paket 3.3). Ohne [id] zeigt die Route den aktiven Space, etwa beim Tippen auf die Benachrichtigung.
 * [confirmEnd] öffnet direkt die Bestätigung zum Beenden. Beenden geschieht nie ohne sie.
 */
@Serializable
data class SpaceRunningRoute(
    val id: String? = null,
    val confirmEnd: Boolean = false,
)

/**
 * Ziele der Spaces. [onCreate] öffnet das Formular für einen neuen Space, [onOpen] das Detail, [onEdit] das Formular eines
 * vorhandenen Space. [onStart] und [onSetup] öffnen die Einrichtung, [onOpenRunning] den laufenden Space, [onStarted]
 * wechselt nach dem Start in den laufenden Space. [onBack] schließt Detail, Formular, Einrichtung und laufenden Space.
 * Die Liste liegt als Ziel der Navigationsleiste (ADR 0002).
 */
fun NavGraphBuilder.spacesScreens(
    onCreate: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onStart: (String) -> Unit = {},
    onSetup: (String) -> Unit = {},
    onOpenRunning: (String) -> Unit = {},
    onRunning: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    composable<SpacesRoute> {
        SpacesScreen(onCreate = onCreate, onOpen = onOpen, onEdit = onEdit)
    }
    composable<SpaceDetailRoute> {
        SpaceDetailScreen(
            onEdit = onEdit,
            onBack = onBack,
            onStart = onStart,
            onSetup = onSetup,
            onOpenRunning = onOpenRunning,
        )
    }
    composable<SpaceEditRoute> {
        SpaceEditScreen(onDone = onBack)
    }
    composable<SpaceSetupRoute> {
        SpaceSetupScreen(onBack = onBack, onRunning = onRunning, onEdit = onEdit)
    }
    composable<SpaceRunningRoute> { entry ->
        val route = entry.toRoute<SpaceRunningRoute>()
        SpaceRunningScreen(
            id = route.id,
            confirmEnd = route.confirmEnd,
            onBack = onBack,
            onSetup = onSetup,
        )
    }
}

fun NavController.navigateToSpaces(navOptions: NavOptions? = null) {
    navigate(route = SpacesRoute, navOptions = navOptions)
}

fun NavController.navigateToSpaceDetail(
    id: String,
    confirmPurge: Boolean = false,
) {
    navigate(route = SpaceDetailRoute(id = id, confirmPurge = confirmPurge))
}

fun NavController.navigateToSpaceEdit(id: String? = null) {
    navigate(route = SpaceEditRoute(id = id))
}

fun NavController.navigateToSpaceSetup(
    id: String,
    startMode: Boolean = false,
) {
    navigate(route = SpaceSetupRoute(id = id, startMode = startMode))
}

/**
 * Öffnet den laufenden Space. Nach einem Start ([afterStart]) fliegt die Einrichtung aus dem Stapel, damit „Zurück“ im
 * laufenden Space nicht zur Einrichtung führt.
 */
fun NavController.navigateToSpaceRunning(
    id: String? = null,
    confirmEnd: Boolean = false,
    afterStart: Boolean = false,
) {
    navigate(route = SpaceRunningRoute(id = id, confirmEnd = confirmEnd)) {
        launchSingleTop = true
        if (afterStart) popUpTo<SpaceSetupRoute> { inclusive = true }
    }
}
