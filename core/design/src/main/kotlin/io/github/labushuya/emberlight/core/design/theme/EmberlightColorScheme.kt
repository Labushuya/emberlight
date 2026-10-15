package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme

/**
 * Das einzige Schema von emberlight. Immer dunkel, keine Dynamic Colors, damit die Marke auf jedem Gerät gleich wirkt.
 */
internal val EmberlightColorScheme: ColorScheme =
    with(EmberlightPalette) {
        darkColorScheme(
            primary = GlowOrange,
            onPrimary = FrameBrown,
            primaryContainer = PrimaryContainer,
            onPrimaryContainer = SparkGold,
            inversePrimary = InversePrimary,
            secondary = FrameGold,
            onSecondary = FrameBrown,
            secondaryContainer = SecondaryContainer,
            onSecondaryContainer = OnSecondaryContainer,
            tertiary = Tertiary,
            onTertiary = OnTertiary,
            tertiaryContainer = TertiaryContainer,
            onTertiaryContainer = OnTertiaryContainer,
            background = BackgroundDeep,
            onBackground = TitleCream,
            surface = Surface,
            onSurface = TitleCream,
            surfaceVariant = SurfaceVariant,
            onSurfaceVariant = Tagline,
            surfaceTint = GlowOrange,
            surfaceDim = BackgroundDeep,
            surfaceBright = SurfaceBright,
            surfaceContainerLowest = SurfaceContainerLowest,
            surfaceContainerLow = SurfaceContainerLow,
            surfaceContainer = SurfaceContainer,
            surfaceContainerHigh = SurfaceContainerHigh,
            surfaceContainerHighest = SurfaceVariant,
            outline = Outline,
            outlineVariant = OutlineVariant,
            inverseSurface = TitleCream,
            inverseOnSurface = FrameBrown,
            error = Error,
            onError = OnError,
            errorContainer = ErrorContainer,
            onErrorContainer = OnErrorContainer,
            scrim = Scrim,
        )
    }
