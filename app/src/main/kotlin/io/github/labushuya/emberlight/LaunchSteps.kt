package io.github.labushuya.emberlight

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.space.SPACES_WIDE_LAYOUT_MIN_WIDTH
import io.github.labushuya.emberlight.feature.space.retention.RetentionRoutes

/** Breite der Navigationsschiene links ab [COMPACT_WIDTH] (Standard der Material-3-Schiene). */
internal val NAVIGATION_RAIL_WIDTH = 80.dp

/** Breite, unterhalb derer die Navigation unten liegt (Medium-Grenze der Fenstergrößen). */
internal val COMPACT_WIDTH = 600.dp

/** Ein Schritt der Navigation aus einer Benachrichtigung. Die Entscheidung ist rein, das Ausführen folgt in `EmberlightApp.kt`. */
internal sealed interface LaunchStep {
    /** Die Liste der Spaces als Ziel im Stapel. [selectedId] wählt den Space im Detail daneben (breite Displays). */
    data class Spaces(
        val selectedId: String?,
        val confirmPurge: Boolean,
    ) : LaunchStep

    /** Das Detail eines Space als eigenes Ziel (Handy). */
    data class Detail(
        val spaceId: String,
        val confirmPurge: Boolean,
    ) : LaunchStep

    /** Die Auswahl zur Übernahme in die Galerie. */
    data class Export(
        val spaceId: String,
    ) : LaunchStep
}

/**
 * Ob die Liste der Spaces das Detail daneben zeigt. Die Breite des Inhalts ist das Fenster abzüglich der Schiene ab
 * [COMPACT_WIDTH].
 */
internal fun spacesShowDetailBeside(windowWidth: Dp): Boolean {
    val contentWidth = if (windowWidth < COMPACT_WIDTH) windowWidth else windowWidth - NAVIGATION_RAIL_WIDTH
    return contentWidth >= SPACES_WIDE_LAYOUT_MIN_WIDTH
}

/**
 * Schritte für „Ansehen“ oder „Jetzt löschen“ aus der Erinnerung (ADR 0021, ADR 0022). Die Liste der Spaces steht immer im
 * Stapel, damit „Zurück“ zu ihr führt und nicht zur Galerie. Auf breiten Displays steht der Space im Detail daneben, sonst
 * folgt das Detail als eigenes Ziel.
 */
internal fun retentionLaunchSteps(
    target: RetentionRoutes.Target,
    detailBeside: Boolean,
): List<LaunchStep> =
    if (detailBeside) {
        buildList {
            val purge = target.confirmPurge && !target.openExport
            add(LaunchStep.Spaces(selectedId = target.spaceId, confirmPurge = purge))
            if (target.openExport) add(LaunchStep.Export(target.spaceId))
        }
    } else {
        buildList {
            add(LaunchStep.Spaces(selectedId = null, confirmPurge = false))
            if (target.openExport) {
                add(LaunchStep.Export(target.spaceId))
            } else {
                add(LaunchStep.Detail(target.spaceId, target.confirmPurge))
            }
        }
    }
