package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import io.github.labushuya.emberlight.core.design.motion.EmberlightMotion
import io.github.labushuya.emberlight.core.design.motion.LocalNavAnimatedVisibilityScope

/**
 * Schlüssel des gemeinsamen Elements eines Mediums. Raster-Zelle und Viewer-Seite nutzen denselben Schlüssel (ADR 0010).
 */
internal fun viewerSharedKey(mediaId: Long): String = "media-$mediaId"

/*
 * Übergänge der Galerie-Ziele. Das Raster bleibt beim Öffnen stehen, der Viewer blendet über ihm ein, und das Element fliegt
 * zwischen beiden. Die Hilfen geben `null` zurück, wenn kein Viewer beteiligt ist, dann gilt der Standard des NavHost. Die
 * Ausgangs- und Eingangs-Übergänge halten die volle Dauer, ohne die Sicht zu verändern: Der Übergang bleibt so lange aktiv,
 * wie das Element fliegt.
 */

/** Das Raster, das den Viewer öffnet: bleibt voll sichtbar, bis der Viewer darüber steht. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.gridExitTowardsViewer(): ExitTransition? =
    if (targetState.destination.hasRoute<ViewerRoute>()) holdExit() else null

/** Das Raster, zu dem der Viewer zurückkehrt: es ist schon voll sichtbar, während der Viewer den Grund freigibt. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.gridPopEnterFromViewer(): EnterTransition? =
    if (initialState.destination.hasRoute<ViewerRoute>()) holdEnter() else null

/** Der Viewer blendet über dem Raster ein. Der Hintergrund ist schwarz, das Bild erscheint früh. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.viewerEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = EmberlightMotion.FADE_MS))

/** Der Viewer verlässt den Bildschirm: die Dauer hält den Rückweg, der Grund wird über das Wegwischen ausgeblendet. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.viewerPopExitTransition(): ExitTransition = holdExit()

/** Ausgang ohne Ausblenden: Die Dauer bleibt, das Element fliegt. */
private fun holdExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(durationMillis = EmberlightMotion.SHARED_MS, easing = EmberlightMotion.Emphasized),
        targetAlpha = 1f,
    )

/** Eingang ohne Einblenden: Das Raster ist sofort voll sichtbar. */
private fun holdEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(durationMillis = EmberlightMotion.SHARED_MS, easing = EmberlightMotion.Emphasized),
        initialAlpha = 1f,
    )

/**
 * `true`, wenn die Seite ruht: Der Übergang ist beendet und der Viewer steht nicht im Rückweg. Ohne Navigations-Scope (Tests,
 * Vorschauen) ruht die Seite immer. Während des Übergangs zeigt die Seite das Vorschaubild als gemeinsames Element, das
 * hochauflösende Bild wartet.
 */
@Composable
internal fun rememberPageSettled(): State<Boolean> {
    val visibility = LocalNavAnimatedVisibilityScope.current
    return remember(visibility) {
        derivedStateOf {
            val transition = visibility?.transition
            transition == null ||
                (
                    transition.currentState == EnterExitState.Visible &&
                        transition.targetState == EnterExitState.Visible &&
                        !transition.isRunning
                )
        }
    }
}
