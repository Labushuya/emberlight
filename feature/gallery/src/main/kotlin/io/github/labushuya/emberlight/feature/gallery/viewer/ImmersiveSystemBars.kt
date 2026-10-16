package io.github.labushuya.emberlight.feature.gallery.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Blendet die Systemleisten im Vollbild aus ([immersive] `true`) oder zeigt sie. Ein Wisch vom Rand zeigt sie vorübergehend.
 *
 * Beim Verlassen der Komposition, also bei Zurück, bei Navigation und bei einem Konfigurationswechsel, stellt der Effekt den
 * normalen Zustand wieder her: Systemleisten sichtbar und das vorige Verhalten. Ein Neustart des Prozesses beginnt ohnehin
 * mit der Standardanzeige der Activity.
 */
@Composable
internal fun ImmersiveSystemBars(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousBehavior = controller.systemBarsBehavior
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller.systemBarsBehavior = previousBehavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
