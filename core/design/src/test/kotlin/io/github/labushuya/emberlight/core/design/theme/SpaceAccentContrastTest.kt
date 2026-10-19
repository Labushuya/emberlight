package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Prüft die acht Akzentfarben eines Space nach WCAG 2.x (Paket 3.2). Dunkler Text auf dem Akzent mindestens 4.5:1,
 * der Akzent auf der Oberfläche mindestens 3:1, weil er als Rahmen und Kennfarbe dient.
 */
class SpaceAccentContrastTest {
    @Test
    fun thereAreExactlyEightAccents() {
        assertEquals(8, SpaceAccents.colors.size)
    }

    @Test
    fun textOnEveryAccentReachesAa() {
        val failures =
            SpaceAccents.colors
                .withIndex()
                .map { (index, accent) -> index to contrastRatio(SpaceAccents.onAccent, accent) }
                .filter { (_, ratio) -> ratio < MIN_TEXT_CONTRAST }
                .map { (index, ratio) -> "Akzent $index: %.2f:1".format(ratio) }
        assertTrue("Kontrast unter 4.5:1 bei: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun everyAccentStandsOutOnTheSurface() {
        val surface = EmberlightColorScheme.surface
        val failures =
            SpaceAccents.colors
                .withIndex()
                .map { (index, accent) -> index to contrastRatio(surface, accent) }
                .filter { (_, ratio) -> ratio < MIN_NON_TEXT_CONTRAST }
                .map { (index, ratio) -> "Akzent $index: %.2f:1".format(ratio) }
        assertTrue("Kontrast unter 3:1 auf der Oberfläche bei: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun colorAtFallsBackToTheFirstAccentForUnknownIndex() {
        assertEquals(SpaceAccents.colors.first(), SpaceAccents.colorAt(-1))
        assertEquals(SpaceAccents.colors.first(), SpaceAccents.colorAt(8))
        assertEquals(SpaceAccents.colors[7], SpaceAccents.colorAt(7))
    }

    private fun contrastRatio(
        first: Color,
        second: Color,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

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
