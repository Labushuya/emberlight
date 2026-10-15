package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.ui.graphics.Color

/**
 * Rohfarben der Marke, abgeleitet aus docs/brand/banner-source.svg.
 *
 * Nur das Color-Scheme referenziert diese Konstanten. Komponenten greifen auf
 * MaterialTheme.colorScheme zu, nie direkt auf die Palette. Launcher-Icon und Splash-Hintergrund
 * sind Android-Ressourcen und pflegen ihre Werte separat.
 */
internal object EmberlightPalette {
    // Hintergrund: Anfang des Banner-Verlaufs
    val BackgroundDeep = Color(0xFF0B0810)

    // Glut und Funken
    val GlowOrange = Color(0xFFFF8A3D)
    val SparkGold = Color(0xFFFFD08A)
    val InversePrimary = Color(0xFFA0461F)

    // Foto-Rahmen und Goldrand
    val FrameBrown = Color(0xFF2A1410)
    val FrameGold = Color(0xFFFFC27A)

    // Schrift
    val TitleCream = Color(0xFFF6E6D4)
    val Tagline = Color(0xFFC9A88D)

    // Töne des Material-3-Schemas (Ausgangswerte aus der Designvorgabe)
    val PrimaryContainer = Color(0xFF5C2414)
    val SecondaryContainer = Color(0xFF4A3020)
    val OnSecondaryContainer = Color(0xFFFFE0B8)
    val Tertiary = Color(0xFFFF8F75)
    val OnTertiary = Color(0xFF3A0F06)
    val TertiaryContainer = Color(0xFF6B2414)
    val OnTertiaryContainer = Color(0xFFFFDAD0)
    val Surface = Color(0xFF120C11)
    val SurfaceVariant = Color(0xFF2E2124)
    val SurfaceBright = Color(0xFF2E2226)
    val SurfaceContainerLowest = Color(0xFF080609)
    val SurfaceContainerLow = Color(0xFF170F14)
    val SurfaceContainer = Color(0xFF1C1317)
    val SurfaceContainerHigh = Color(0xFF24191C)
    val Outline = Color(0xFF8C7262)
    val OutlineVariant = Color(0xFF3D2E2A)
    val Scrim = Color(0xFF000000)

    // Fehlerfarben: Material-3-Dark-Standard
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)
}
