package io.github.labushuya.emberlight

import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.space.retention.RetentionRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ziele aus der Erinnerung (Härtung vor v0.3.0-rc.1, ADR 0021 und 0022). Der Stapel muss die Liste der Spaces enthalten, damit
 * „Zurück“ dorthin führt und nicht zur Galerie. Auf breiten Displays steht der Space im Detail daneben, ohne eigenes
 * Detail-Ziel. Geprüft werden die Schritte und die Breitenregel, die Ausführung ist eine Zeile je Schritt.
 */
class LaunchRouteNavigationTest {
    private val target = RetentionRoutes.parse(RetentionRoutes.open(ID))!!

    @Test
    fun onAPhoneTheListGoesFirstAndTheDetailOnTop() {
        val steps = retentionLaunchSteps(target, detailBeside = false)

        assertEquals(
            listOf(
                LaunchStep.Spaces(selectedId = null, confirmPurge = false),
                LaunchStep.Detail(ID, confirmPurge = false),
            ),
            steps,
        )
    }

    @Test
    fun onAPhoneExportGoesOnTopOfTheList() {
        val steps = retentionLaunchSteps(RetentionRoutes.parse(RetentionRoutes.export(ID))!!, detailBeside = false)

        assertEquals(listOf(LaunchStep.Spaces(null, false), LaunchStep.Export(ID)), steps)
    }

    @Test
    fun onAWideDisplayPurgeSelectsTheSpaceAndNoDetailRouteIsPushed() {
        val steps = retentionLaunchSteps(RetentionRoutes.parse(RetentionRoutes.purge(ID))!!, detailBeside = true)

        assertEquals(listOf(LaunchStep.Spaces(selectedId = ID, confirmPurge = true)), steps)
    }

    @Test
    fun onAWideDisplayExportSelectsTheSpaceAndThenOpensTheExport() {
        val steps = retentionLaunchSteps(RetentionRoutes.parse(RetentionRoutes.export(ID))!!, detailBeside = true)

        assertEquals(listOf(LaunchStep.Spaces(ID, confirmPurge = false), LaunchStep.Export(ID)), steps)
    }

    @Test
    fun theDetailBesideDependsOnTheContentWidthNotOnTheWindowAlone() {
        // Ab 600 dp Fensterbreite ist die Schiene da, der Inhalt hat dann 520 dp und reicht nicht für zwei Spalten.
        assertFalse(spacesShowDetailBeside(412.dp))
        assertFalse(spacesShowDetailBeside(600.dp))
        assertTrue(spacesShowDetailBeside(680.dp))
        assertTrue(spacesShowDetailBeside(840.dp))
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
