package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regeln der vertikalen Wischgesten: Achse, Richtung und das Einrasten des Infobereichs (ADR 0010, Gesten-Schwellen in dp).
 * Die Schwellen sind dp-Werte und gelten unabhängig von Höhe und Dichte des Geräts.
 */
class InfoSheetRulesTest {
    private val phone = GesturePx.of(Density(DENSITY_OUTER))

    @Test
    fun aMovementUpwardsIsTheInfoSheetNotADismiss() {
        assertEquals(DragIntent.INFO, dragIntent(totalX = 0f, totalY = -60f, slopPx = 8f))
        assertEquals(DragIntent.INFO, dragIntent(totalX = 10f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun aMovementDownwardsStaysTheDismiss() {
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 0f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun aHorizontalOrDiagonalMovementGoesToThePager() {
        assertEquals(DragIntent.PASS, dragIntent(totalX = 80f, totalY = -20f, slopPx = 8f))
        assertEquals(DragIntent.PASS, dragIntent(totalX = 41f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun aSmallMovementIsStillPending() {
        assertEquals(DragIntent.PENDING, dragIntent(totalX = 1f, totalY = -4f, slopPx = 8f))
    }

    @Test
    fun aDragOfTheOpenDistanceFromClosedOpens() {
        assertTrue(infoSheetOpens(startOpen = false, travelUpPx = dp(56f), velocityYPerSecond = 0f, thresholds = phone))
        assertFalse(
            infoSheetOpens(startOpen = false, travelUpPx = dp(55f), velocityYPerSecond = 0f, thresholds = phone),
        )
    }

    @Test
    fun aDownwardDragFromOpenClosesAtTheDistance() {
        assertFalse(
            infoSheetOpens(startOpen = true, travelUpPx = -dp(56f), velocityYPerSecond = 0f, thresholds = phone),
        )
        assertTrue(infoSheetOpens(startOpen = true, travelUpPx = -dp(55f), velocityYPerSecond = 0f, thresholds = phone))
    }

    @Test
    fun aShortSlowDragSpringsBackToItsStartState() {
        // 15 dp in 600 ms: 25 dp/s, unter Weg und Geschwindigkeit. Der Bereich federt in seinen Ausgangsstand zurück.
        assertFalse(
            infoSheetOpens(startOpen = false, travelUpPx = dp(15f), velocityYPerSecond = dp(25f), thresholds = phone),
        )
        assertTrue(
            infoSheetOpens(startOpen = true, travelUpPx = -dp(15f), velocityYPerSecond = -dp(25f), thresholds = phone),
        )
    }

    @Test
    fun aLongSlowDragOpens() {
        // 80 dp in 800 ms: 100 dp/s, über dem Weg von 56 dp.
        assertTrue(
            infoSheetOpens(startOpen = false, travelUpPx = dp(80f), velocityYPerSecond = -dp(100f), thresholds = phone),
        )
    }

    @Test
    fun aFastUpwardFlingOpensWithTheMinimumDistance() {
        assertTrue(
            infoSheetOpens(startOpen = false, travelUpPx = dp(12f), velocityYPerSecond = -dp(250f), thresholds = phone),
        )
        // Unter dem Mindestweg zählt der Schnellschluss nicht.
        assertFalse(
            infoSheetOpens(startOpen = false, travelUpPx = dp(11f), velocityYPerSecond = -dp(250f), thresholds = phone),
        )
    }

    @Test
    fun aFastDownwardFlingClosesAnOpenSheet() {
        assertFalse(
            infoSheetOpens(startOpen = true, travelUpPx = -dp(12f), velocityYPerSecond = dp(300f), thresholds = phone),
        )
    }

    @Test
    fun theSameDpDragDecidesTheSameOnEveryDevice() {
        // Gleiche Strecke in dp, gleiche Entscheidung: unabhängig von Höhe und Dichte.
        for (device in DEVICES) {
            val t = GesturePx.of(Density(device.density))
            for (dpValue in listOf(15f, 40f, 56f, 80f)) {
                val expectedOpen = dpValue >= ViewerGestureDp.INFO_OPEN_DP
                val opens =
                    infoSheetOpens(
                        startOpen = false,
                        travelUpPx = dpValue * device.density,
                        velocityYPerSecond = 0f,
                        thresholds = t,
                    )
                assertEquals("${device.name}, $dpValue dp", expectedOpen, opens)
            }
        }
    }

    @Test
    fun theInfoThresholdsAreTheDocumentedValues() {
        // 56 dp entsprechen dem Positionsschwellwert der Material-3-Bottom-Sheets (BottomSheetDefaults.PositionalThreshold).
        assertEquals(56f, ViewerGestureDp.INFO_OPEN_DP, 0f)
        assertEquals(200f, ViewerGestureDp.INFO_FLING_DP_PER_SECOND, 0f)
        assertEquals(12f, ViewerGestureDp.INFO_FLING_MIN_DP, 0f)
    }

    private fun dp(value: Float): Float = value * DENSITY_OUTER

    private data class Device(
        val name: String,
        val density: Float,
    )

    private companion object {
        /** Außendisplay Honor Magic V2: 1060 x 2376 px bei 480 dpi, also Dichte 3. */
        const val DENSITY_OUTER = 3f

        val DEVICES =
            listOf(
                Device("1060x2376 @ 480 dpi", density = 3f),
                Device("1080x1920 @ 420 dpi", density = 2.625f),
                Device("Tablet 2560x1600 @ 320 dpi", density = 2f),
            )
    }
}
