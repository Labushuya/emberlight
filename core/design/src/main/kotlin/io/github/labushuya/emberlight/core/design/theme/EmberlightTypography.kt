package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.labushuya.emberlight.core.design.R

/**
 * Fraunces (Variable Font, OFL) für Display-, Headline- und TitleLarge-Stile, Inter (Variable Font, OFL)
 * für alle übrigen Stile. Beide Schriften liegen im APK, es werden keine Schriften nachgeladen.
 *
 * Fraunces hat eine opsz-Achse: große Titel nutzen die feine Display-Optik (144), mittlere Überschriften
 * die Text-Optik (48). Inter wird nur über die Gewichtsachse variiert.
 */
private fun frauncesFamily(opticalSize: Float): FontFamily =
    FontFamily(
        Font(
            resId = R.font.fraunces_roman,
            weight = FontWeight.Light,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(300),
                    FontVariation.Setting("opsz", opticalSize),
                ),
        ),
    )

private val FrauncesDisplay = frauncesFamily(opticalSize = 144f)
private val FrauncesHeading = frauncesFamily(opticalSize = 48f)

private val InterFamily =
    FontFamily(
        Font(
            resId = R.font.inter_roman,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            resId = R.font.inter_roman,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            resId = R.font.inter_roman,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
    )

private val BaseTypography = Typography()

private fun TextStyle.fraunces(family: FontFamily): TextStyle = copy(fontFamily = family, fontWeight = FontWeight.Light)

private fun TextStyle.inter(weight: FontWeight): TextStyle = copy(fontFamily = InterFamily, fontWeight = weight)

internal val EmberlightTypography =
    Typography(
        displayLarge = BaseTypography.displayLarge.fraunces(FrauncesDisplay),
        displayMedium = BaseTypography.displayMedium.fraunces(FrauncesDisplay),
        displaySmall = BaseTypography.displaySmall.fraunces(FrauncesDisplay),
        headlineLarge = BaseTypography.headlineLarge.fraunces(FrauncesHeading),
        headlineMedium = BaseTypography.headlineMedium.fraunces(FrauncesHeading),
        headlineSmall = BaseTypography.headlineSmall.fraunces(FrauncesHeading),
        titleLarge = BaseTypography.titleLarge.fraunces(FrauncesHeading),
        titleMedium = BaseTypography.titleMedium.inter(FontWeight.Medium),
        titleSmall = BaseTypography.titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = BaseTypography.bodyLarge.inter(FontWeight.Normal),
        bodyMedium = BaseTypography.bodyMedium.inter(FontWeight.Normal),
        bodySmall = BaseTypography.bodySmall.inter(FontWeight.Normal),
        labelLarge = BaseTypography.labelLarge.inter(FontWeight.Medium),
        labelMedium = BaseTypography.labelMedium.inter(FontWeight.Medium),
        labelSmall = BaseTypography.labelSmall.inter(FontWeight.SemiBold),
    )
