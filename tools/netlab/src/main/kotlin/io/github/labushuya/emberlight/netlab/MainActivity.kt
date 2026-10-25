package io.github.labushuya.emberlight.netlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.netlab.ui.NetlabScreens

/** Einziger Bildschirm des Messwerkzeugs. Die Logik liegt im [NetlabViewModel]. */
class MainActivity : ComponentActivity() {
    private val viewModel: NetlabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    NetlabScreens(
                        state = state,
                        onRefresh = viewModel::refreshCapabilities,
                        onRole = viewModel::chooseRole,
                        onStartHost = viewModel::startHost,
                        onStopHost = viewModel::stopHost,
                        onAcceptJoin = viewModel::acceptJoinText,
                        onConnectGuest = viewModel::connectGuest,
                        onRunGuestTests = viewModel::runGuestTests,
                        onDisconnectGuest = viewModel::disconnectGuest,
                        onBuildReport = viewModel::buildReport,
                        onCloseReport = viewModel::closeReport,
                    )
                }
            }
        }
        viewModel.refreshCapabilities()
    }
}
