package io.github.labushuya.emberlight.feature.space.run

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.StartProblem
import io.github.labushuya.emberlight.share.space.run.StartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Ergebnis des Startversuchs. Die Oberfläche zeigt je Fall einen eigenen Text. */
sealed interface StartOutcome {
    /** Der Space läuft, der Dienst ist gestartet. */
    data object Started : StartOutcome

    /** Kein WLAN mit IPv4-Adresse. Der Server kann nicht binden. */
    data object NoWifi : StartOutcome

    /** Eine Vorbedingung im Betrieb ist verletzt (zweiter aktiver Space, Speicher, Ende in der Vergangenheit, …). */
    data class Refused(
        val problem: StartProblem,
    ) : StartOutcome

    /** Android hat den Dienst nicht in den Vordergrund gelassen. Der Space ist wieder Entwurf. */
    data object ServiceRefused : StartOutcome
}

/**
 * Startet einen Entwurf (Paket 3.3). Prüft WLAN und Betrieb, setzt den Space auf aktiv und startet den Dienst. Die
 * Einrichtung (Berechtigungen, Akku, Hersteller) prüft die Oberfläche davor. Die Anforderungen an den Dienst prüft er selbst.
 */
class SpaceStarter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val runs: SpaceRunRepository,
        private val storage: StorageProbe,
        private val wifi: WifiNetworkProvider,
    ) {
        suspend fun start(id: SpaceId): StartOutcome =
            withContext(Dispatchers.IO) {
                if (wifi.currentAddress() == null) return@withContext StartOutcome.NoWifi
                when (val result = runs.start(id, storage.usableBytes())) {
                    is StartResult.Refused -> StartOutcome.Refused(result.problem)
                    is StartResult.Started -> launch(id)
                }
            }

        private suspend fun launch(id: SpaceId): StartOutcome =
            try {
                ContextCompat.startForegroundService(context, SpaceServerService.startIntent(context, id))
                StartOutcome.Started
            } catch (failure: IllegalStateException) {
                runs.abortStart(id)
                StartOutcome.ServiceRefused
            } catch (failure: SecurityException) {
                runs.abortStart(id)
                StartOutcome.ServiceRefused
            }
    }
