package io.github.labushuya.emberlight.core.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable

/** Wurzel-Theme von emberlight: dunkles Schema, Fraunces und Inter, Standard-Shapes von Material 3. */
@Composable
fun EmberlightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EmberlightColorScheme,
        typography = EmberlightTypography,
        shapes = Shapes(),
        content = content,
    )
}
