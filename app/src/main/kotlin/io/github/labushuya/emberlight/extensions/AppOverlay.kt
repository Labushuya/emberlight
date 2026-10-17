package io.github.labushuya.emberlight.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Zusätzliche Anzeige über der ganzen Oberfläche, etwa der Hinweis „DEMO“ im Demo-Modus (ADR 0011). Im Release-Build gibt es keine
 * Beiträge, dann bleibt die Oberfläche unverändert.
 */
interface AppOverlay {
    @Composable
    fun Overlay(modifier: Modifier)
}
