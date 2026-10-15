package io.github.labushuya.emberlight

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Muss vor super.onCreate stehen, damit der Splash beim Kaltstart greift.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Dunkle Symbole sind auf dem dunklen Hintergrund nicht lesbar: Systemleisten transparent mit hellen Symbolen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            EmberlightTheme {
                EmberlightApp()
            }
        }
    }
}
