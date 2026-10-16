@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package io.github.labushuya.emberlight.core.design.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Der gemeinsame Übergangs-Scope der App. Die App legt ihn um den NavHost (`SharedTransitionLayout`). Ohne Scope, etwa in
 * Tests oder Vorschauen, fällt jedes gemeinsame Element auf „kein Element“ zurück (ADR 0010).
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Der Sichtbarkeits-Scope des aktuellen Navigationsziels. Ein `composable {}` reicht seinen Receiver hierüber weiter, weil
 * navigation-compose selbst keinen eigenen Local dafür bereitstellt.
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Macht das Element zum gemeinsamen Element mit dem Schlüssel [key] (ein Schlüssel pro Medium, etwa `media-<id>`). Zwei
 * Elemente mit demselben Schlüssel, Raster-Zelle und Viewer-Seite, werden über die Übergänge verbunden.
 *
 * Die Grenzen wachsen mit [SharedTransitionScope.ResizeMode.scaleToBounds] und [contentScale]: Beide Seiten nutzen denselben
 * Zuschnitt, dadurch gibt es keine Verzerrung und keinen Sprung. Das Element bleibt an seiner Stelle im Baum
 * (`renderInOverlayDuringTransition = false`), damit eine Verschiebung der Oberfläche (Wegwischen) den Rückweg mitnimmt.
 *
 * Fehlt ein Scope oder ist [key] `null`, bleibt das Modifier unverändert.
 */
@Composable
fun Modifier.sharedMediaBounds(
    key: String?,
    contentScale: ContentScale = ContentScale.Crop,
): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    if (key == null) return this
    return with(sharedScope) {
        this@sharedMediaBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = { _, _ -> EmberlightMotion.sharedSpec() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(contentScale),
            renderInOverlayDuringTransition = false,
        )
    }
}
