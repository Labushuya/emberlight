package io.github.labushuya.emberlight

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.extensions.AppOverlay
import io.github.labushuya.emberlight.extensions.LaunchIntentHandler
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Beiträge zur Auswertung von Start-Intents. Im Release-Build leer. */
    @Inject
    lateinit var launchIntentHandlers: Set<@JvmSuppressWildcards LaunchIntentHandler>

    /** Zusätzliche Anzeigen über der Oberfläche. Im Release-Build leer. */
    @Inject
    lateinit var appOverlays: Set<@JvmSuppressWildcards AppOverlay>

    override fun onCreate(savedInstanceState: Bundle?) {
        // Muss vor super.onCreate stehen, damit der Splash beim Kaltstart greift.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        // Dunkle Symbole sind auf dem dunklen Hintergrund nicht lesbar: Systemleisten transparent mit hellen Symbolen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            EmberlightTheme {
                EmberlightApp(overlays = appOverlays.toList())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        launchIntentHandlers.forEach { it.onLaunchIntent(intent) }
    }
}
