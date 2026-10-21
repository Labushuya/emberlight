package io.github.labushuya.emberlight

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.extensions.AppOverlay
import io.github.labushuya.emberlight.extensions.LaunchIntentHandler
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerImageSourceContribution
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.server.SpaceServerService
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Beiträge zur Auswertung von Start-Intents. Im Release-Build leer. */
    @Inject
    lateinit var launchIntentHandlers: Set<@JvmSuppressWildcards LaunchIntentHandler>

    /** Zusätzliche Anzeigen über der Oberfläche. Im Release-Build leer. */
    @Inject
    lateinit var appOverlays: Set<@JvmSuppressWildcards AppOverlay>

    /** Zusätzliche Bildquellen des Vollbild-Viewers. Im Release-Build leer. */
    @Inject
    lateinit var retentionReminders: RetentionReminders

    @Inject
    lateinit var viewerImageSources: Set<@JvmSuppressWildcards ViewerImageSourceContribution>

    /** Ziel aus einer Benachrichtigung des Dienstes (Paket 3.3). Die Oberfläche öffnet es und setzt es zurück. */
    private val launchRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Muss vor super.onCreate stehen, damit der Splash beim Kaltstart greift.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Beim Start: unterbrochene Löschungen fortsetzen und verpasste Erinnerungen nachholen (Paket 3.6b).
        lifecycleScope.launch { retentionReminders.startupCatchUp() }
        handleLaunchIntent(intent)

        // Dunkle Symbole sind auf dem dunklen Hintergrund nicht lesbar: Systemleisten transparent mit hellen Symbolen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            EmberlightTheme {
                EmberlightApp(
                    overlays = appOverlays.toList(),
                    viewerImageSources = viewerImageSources.toList(),
                    launchRoute = launchRoute.value,
                    onLaunchRouteConsume = { launchRoute.value = null },
                )
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
        // Das Ziel gilt einmal: Nach dem Lesen entfernt, damit eine Neuerzeugung der Activity es nicht wiederholt.
        intent.getStringExtra(SpaceServerService.EXTRA_ROUTE)?.let { route ->
            intent.removeExtra(SpaceServerService.EXTRA_ROUTE)
            launchRoute.value = route
        }
    }
}
