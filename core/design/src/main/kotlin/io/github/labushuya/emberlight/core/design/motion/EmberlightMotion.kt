package io.github.labushuya.emberlight.core.design.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Bewegungswerte der App (ADR 0010, Overlay und Hero-Animation).
 *
 * Öffnen und Schließen des Vollbild-Viewers dauern 350 ms, im Bereich 300 bis 400 ms, den Material 3 für große Bewegungen
 * vorsieht. Die Kurve ist die „emphasized“-Kurve (0,2 / 0 / 0 / 1). Ein Feder-Modell wurde verworfen, weil sich ein Bild aus
 * einem Quadrat in ein Rechteck verwandelt und eine Feder dabei über das Ziel hinausschwingt.
 */
object EmberlightMotion {
    /** Dauer des Öffnens und Schließens des Viewers (Hero-Animation). */
    const val HERO_MS: Int = 350

    /** Dauer des Ein- und Ausblendens der Navigationsleiste. */
    const val CHROME_MS: Int = 300

    /** Material-3-„emphasized“-Kurve. */
    val Emphasized: CubicBezierEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Animation der Hero-Animation. */
    fun <T> heroSpec(): FiniteAnimationSpec<T> = tween(durationMillis = HERO_MS, easing = Emphasized)

    /** Animation der Navigationsleiste. */
    fun <T> chromeSpec(): FiniteAnimationSpec<T> = tween(durationMillis = CHROME_MS, easing = Emphasized)
}
