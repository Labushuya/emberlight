package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Prüft die Kontraste des Schemas nach WCAG 2.x (relative Leuchtdichte, sRGB).
 *
 * Text mindestens 4.5:1 (WCAG AA), Grafik- und Umrisselemente mindestens 3:1 (Nicht-Text-Kontrast).
 */
class EmberlightColorContrastTest {
    private val scheme: ColorScheme = EmberlightColorScheme

    @Test
    fun luminanceFormulaMatchesWcagReferenceValues() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.0001)
    }

    @Test
    fun textPairsReachAa() {
        val pairs =
            listOf(
                "primary/onPrimary" to (scheme.primary to scheme.onPrimary),
                "primaryContainer/onPrimaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
                "secondary/onSecondary" to (scheme.secondary to scheme.onSecondary),
                "secondaryContainer/onSecondaryContainer" to
                    (scheme.secondaryContainer to scheme.onSecondaryContainer),
                "tertiary/onTertiary" to (scheme.tertiary to scheme.onTertiary),
                "tertiaryContainer/onTertiaryContainer" to (scheme.tertiaryContainer to scheme.onTertiaryContainer),
                "background/onBackground" to (scheme.background to scheme.onBackground),
                "surface/onSurface" to (scheme.surface to scheme.onSurface),
                "surfaceVariant/onSurfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
                "error/onError" to (scheme.error to scheme.onError),
                "errorContainer/onErrorContainer" to (scheme.errorContainer to scheme.onErrorContainer),
                "inverseSurface/inverseOnSurface" to (scheme.inverseSurface to scheme.inverseOnSurface),
            )
        assertAllAtLeast(pairs, MIN_TEXT_CONTRAST)
    }

    @Test
    fun textOnEverySurfaceContainerLevelReachesAa() {
        val surfaces =
            listOf(
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            )
        val pairs =
            surfaces.flatMap { (name, color) ->
                listOf(
                    "onSurface auf $name" to (color to scheme.onSurface),
                    "onSurfaceVariant auf $name" to (color to scheme.onSurfaceVariant),
                )
            }
        assertAllAtLeast(pairs, MIN_TEXT_CONTRAST)
    }

    @Test
    fun outlineOnSurfaceReachesNonTextContrast() {
        assertAllAtLeast(
            listOf("outline auf surface" to (scheme.surface to scheme.outline)),
            MIN_NON_TEXT_CONTRAST,
        )
    }

    private fun assertAllAtLeast(
        pairs: List<Pair<String, Pair<Color, Color>>>,
        minimum: Double,
    ) {
        val failures =
            pairs
                .map { (name, colors) -> name to contrastRatio(colors.first, colors.second) }
                .filter { (_, ratio) -> ratio < minimum }
                .map { (name, ratio) -> "$name: %.2f:1".format(ratio) }
        assertTrue(
            "Kontrast unter %.1f:1 bei: %s".format(minimum, failures.joinToString("; ")),
            failures.isEmpty(),
        )
    }

    private fun contrastRatio(
        first: Color,
        second: Color,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** WCAG 2.x: L = 0.2126 R + 0.7152 G + 0.0722 B mit linearisierten sRGB-Kanälen. */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_NON_TEXT_CONTRAST = 3.0
    }
}
