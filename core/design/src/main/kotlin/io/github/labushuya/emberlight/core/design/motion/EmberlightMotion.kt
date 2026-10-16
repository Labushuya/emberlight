package io.github.labushuya.emberlight.core.design.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Bewegungswerte der App (ADR 0010, Übergänge).
 *
 * Die Dauer des Shared-Element-Übergangs liegt bei 350 ms, im Bereich 300 bis 400 ms, den Material 3 für große, raumgreifende
 * Bewegungen vorsieht. Die Kurve ist die „emphasized“-Kurve (0,2 / 0 / 0 / 1): ein Bild startet zügig aus der Kachel und
 * kommt sanft im Vollbild an. Ein Feder-Modell wurde verworfen, weil sich ein Bild aus einem Quadrat in ein Rechteck
 * verwandelt und eine Feder dabei über das Ziel hinausschwingt.
 */
object EmberlightMotion {
    /** Dauer des Shared-Element-Übergangs und des Rückwegs. */
    const val SHARED_MS: Int = 350

    /** Dauer des Einblendens des Vollbilds. Kürzer als der Übergang, damit das Bild früh sichtbar ist. */
    const val FADE_MS: Int = 200

    /** Material-3-„emphasized“-Kurve. */
    val Emphasized: CubicBezierEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Gemeinsame Animation der Grenzen eines Shared Elements. */
    fun <T> sharedSpec(): FiniteAnimationSpec<T> = tween(durationMillis = SHARED_MS, easing = Emphasized)
}
