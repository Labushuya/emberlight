package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.ui.graphics.Color

/**
 * Die acht Akzentfarben eines Space (Paket 3.2). Gespeichert wird nur der Index in dieser Liste, nie die Farbe selbst.
 * Alle Farben sind für das dunkle Schema gewählt: Dunkler Text auf dem Akzent erreicht mindestens 4,5:1, der Akzent auf
 * der Oberfläche mindestens 3:1 (`SpaceAccentContrastTest`).
 */
object SpaceAccents {
    val colors: List<Color> =
        listOf(
            Color(0xFFFF8A3D),
            Color(0xFFFFC27A),
            Color(0xFFFF8F75),
            Color(0xFFE8A33D),
            Color(0xFFE38AA0),
            Color(0xFFD9774A),
            Color(0xFFA8B86B),
            Color(0xFF8FA8C8),
        )

    /** Text und Symbole auf einem Akzent. Dunkel, wie der Rahmen der Marke. */
    val onAccent: Color = EmberlightPalette.FrameBrown

    /** Farbe für einen gespeicherten Index. Ungültige Indizes fallen auf die erste Farbe zurück. */
    fun colorAt(index: Int): Color = colors.getOrElse(index) { colors.first() }
}
