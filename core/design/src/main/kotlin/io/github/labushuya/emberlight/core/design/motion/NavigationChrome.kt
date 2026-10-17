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

    /**
     * Oberkante der unteren Leiste in Root-Koordinaten, zuletzt gemessen während sie sichtbar war. Solange der Viewer die Leiste
     * ausgeblendet hat, bleibt der Wert erhalten: Nach dem Schließen rückt die Leiste wieder dorthin. `null`, wenn es keine
     * untere Leiste gibt (breite Displays mit Rail). Kein State: Der Wert wird nur gelesen, wenn ein Übergang startet.
     */
    var bottomBarTopPx: Float? = null
}

/** Der Zustand der Navigation, den die App bereitstellt. Ohne App (Tests, Vorschauen) ist er `null`. */
val LocalNavigationChrome = compositionLocalOf<NavigationChrome?> { null }
