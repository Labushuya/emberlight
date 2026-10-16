@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package io.github.labushuya.emberlight.core.design.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sichtbarkeit der Navigation. Ein Vollbild-Viewer über dem Raster setzt [hidden], die App blendet die Leiste oder die Rail
 * animiert aus und wieder ein (ADR 0010). Die Galerie besitzt diesen Zustand nicht, sie meldet nur.
 */
@Stable
class NavigationChrome {
    var hidden: Boolean by mutableStateOf(false)
}

/** Der Zustand der Navigation, den die App bereitstellt. Ohne App (Tests, Vorschauen) ist er `null`. */
val LocalNavigationChrome = compositionLocalOf<NavigationChrome?> { null }
