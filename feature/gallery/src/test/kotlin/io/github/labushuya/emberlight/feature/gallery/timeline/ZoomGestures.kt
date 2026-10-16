package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.pinch

/**
 * Das Raster einer Ansicht im Compose-Test: das einzige Element mit Spaltenbeschreibung. Galerie und Album nutzen dasselbe
 * Raster, daher gilt die Suche für beide.
 */
internal fun ComposeContentTestRule.mediaGrid(): SemanticsNodeInteraction =
    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))

/** Finger auseinander, vom Mittelpunkt aus. Die Abstände richten sich nach der Breite, damit beide Finger im Raster liegen. */
internal fun TouchInjectionScope.pinchApart() {
    val near = width * 0.04f
    val far = width * 0.2f
    pinch(
        start0 = center + Offset(-near, 0f),
        end0 = center + Offset(-far, 0f),
        start1 = center + Offset(near, 0f),
        end1 = center + Offset(far, 0f),
    )
}

internal fun TouchInjectionScope.pinchTogether() {
    val near = width * 0.04f
    val far = width * 0.2f
    pinch(
        start0 = center + Offset(-far, 0f),
        end0 = center + Offset(-near, 0f),
        start1 = center + Offset(far, 0f),
        end1 = center + Offset(near, 0f),
    )
}
