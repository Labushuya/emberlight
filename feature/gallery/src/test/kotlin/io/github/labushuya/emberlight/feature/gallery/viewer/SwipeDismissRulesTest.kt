package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regeln des Wegwischens nach unten als reine Funktionen (ADR 0010, Gesten-Schwellen in dp): Achse, Schwelle, Geschwindigkeit,
 * Zoom. Die Schwellen sind dp-Werte. Die Tests rechnen sie mit der Dichte des Geräts in Pixel um.
 */
class SwipeDismissRulesTest {
    private val phone = thresholdsAt(density = DENSITY_OUTER)

    @Test
    fun aSmallMovementDoesNotDecideYet() {
        assertEquals(DragIntent.PENDING, dragIntent(totalX = 1f, totalY = 4f, slopPx = 8f))
    }

    @Test
    fun aPredominantlyDownwardMovementStartsTheDismiss() {
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 5f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun aHorizontalMovementGoesToThePager() {
        assertEquals(DragIntent.PASS, dragIntent(totalX = 60f, totalY = 20f, slopPx = 8f))
    }

    @Test
    fun anUpwardMovementOpensTheInfoSheetNotADismiss() {
        assertEquals(DragIntent.INFO, dragIntent(totalX = 0f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun theAxisRatioDecidesADiagonalMovement() {
        // Knapp über dem Verhältnis von 1,5: vertikal. Knapp darunter: diagonal, also beim Pager.
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 39f, totalY = 60f, slopPx = 8f))
        assertEquals(DragIntent.PASS, dragIntent(totalX = 41f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun releasingBelowTheThresholdSpringsBack() {
        assertFalse(shouldDismiss(offsetPx = phone.dismissPx - 1f, velocityYPerSecond = 0f, thresholds = phone))
    }

    @Test
    fun releasingAtTheThresholdCloses() {
        assertTrue(shouldDismiss(offsetPx = phone.dismissPx, velocityYPerSecond = 0f, thresholds = phone))
    }

    @Test
    fun aFastFlickClosesEvenBelowTheThreshold() {
        // Mindestens 24 dp Weg und Geschwindigkeit nach unten ab 400 dp/s, hier 500 dp/s.
        val fast = dpPx(500f, DENSITY_OUTER)
        assertTrue(shouldDismiss(offsetPx = dpPx(40f, DENSITY_OUTER), velocityYPerSecond = fast, thresholds = phone))
    }

    @Test
    fun aFastButTinyMovementDoesNotClose() {
        // Ein Zucken von 10 dp schließt den Viewer nicht, auch bei hoher Geschwindigkeit.
        assertFalse(shouldDismiss(offsetPx = dpPx(10f, DENSITY_OUTER), velocityYPerSecond = 2_000f, thresholds = phone))
    }

    @Test
    fun aSlowMovementBelowTheThresholdDoesNotClose() {
        // 30 dp langsam: unter der Schwelle und ohne Geschwindigkeit, also federt der Viewer zurück.
        assertFalse(shouldDismiss(offsetPx = dpPx(30f, DENSITY_OUTER), velocityYPerSecond = 100f, thresholds = phone))
    }

    @Test
    fun aFlickOfTheTestedSizeClosesOnTheOuterDisplay() {
        // 120 dp in 100 ms, also 1200 dp/s, über der Schwelle: schließt.
        val flick = dpPx(1_200f, DENSITY_OUTER)
        assertTrue(shouldDismiss(offsetPx = dpPx(120f, DENSITY_OUTER), velocityYPerSecond = flick, thresholds = phone))
    }

    @Test
    fun noOffsetNeverCloses() {
        assertFalse(shouldDismiss(offsetPx = 0f, velocityYPerSecond = 9_000f, thresholds = phone))
    }

    @Test
    fun theSameDpDistanceDecidesTheSameOnEveryDevice() {
        // Gleiche Strecke in dp ergibt auf jedem Gerät dieselbe Entscheidung, unabhängig von Höhe und Dichte.
        for (device in DEVICES) {
            val t = thresholdsAt(device.density)
            for (dpValue in listOf(60f, 95f, 96f, 120f)) {
                val expected = dpValue >= ViewerGestureDp.DISMISS_DP
                assertEquals(
                    "${device.name}, $dpValue dp",
                    expected,
                    shouldDismiss(dpPx(dpValue, device.density), 0f, t),
                )
            }
        }
    }

    @Test
    fun theDismissThresholdIsTheSameDistanceInDpOnEveryDevice() {
        for (device in DEVICES) {
            val t = thresholdsAt(device.density)
            assertEquals(device.name, ViewerGestureDp.DISMISS_DP, t.dismissPx / device.density, 1e-3f)
        }
    }

    @Test
    fun progressIsTheShareOfTheHeightClampedToZeroAndOne() {
        val height = 1_000f
        assertEquals(0f, dismissProgress(offsetPx = -20f, heightPx = height), 0f)
        assertEquals(0.25f, dismissProgress(offsetPx = 250f, heightPx = height), 1e-6f)
        assertEquals(1f, dismissProgress(offsetPx = 5_000f, heightPx = height), 0f)
        assertEquals(0f, dismissProgress(offsetPx = 5f, heightPx = 0f), 0f)
    }

    @Test
    fun dismissOnlyStartsWhenTheImageIsNotZoomed() {
        assertTrue(canStartDismiss(zoomFraction = null))
        assertTrue(canStartDismiss(zoomFraction = 0f))
        assertFalse(canStartDismiss(zoomFraction = 0.4f))
        assertFalse(canStartDismiss(zoomFraction = 1f))
    }

    @Test
    fun theThresholdsAreTheDocumentedValues() {
        assertEquals(96f, ViewerGestureDp.DISMISS_DP, 0f)
        assertEquals(400f, ViewerGestureDp.DISMISS_FLING_DP_PER_SECOND, 0f)
        assertEquals(24f, ViewerGestureDp.DISMISS_FLING_MIN_DP, 0f)
        assertEquals(0.1f, SHRINK_MAX, 0f)
    }

    private data class Device(
        val name: String,
        val density: Float,
    )

    private companion object {
        /** Außendisplay Honor Magic V2: 1060 × 2376 px bei 480 dpi, also Dichte 3. */
        const val DENSITY_OUTER = 3f

        val DEVICES =
            listOf(
                Device("1060x2376 @ 480 dpi", density = 3f),
                Device("1080x1920 @ 420 dpi", density = 2.625f),
                Device("Tablet 2560x1600 @ 320 dpi", density = 2f),
            )

        fun thresholdsAt(density: Float): GesturePx = GesturePx.of(Density(density))

        fun dpPx(
            dp: Float,
            density: Float,
        ): Float = dp * density
    }
}
