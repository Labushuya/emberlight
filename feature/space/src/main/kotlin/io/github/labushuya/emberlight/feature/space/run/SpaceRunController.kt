package io.github.labushuya.emberlight.feature.space.run

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.SpaceId
import javax.inject.Inject

/**
 * Befehle der Oberfläche an den laufenden Dienst: Ende und Bestätigung eines neuen WLAN. Das Ende aus der
 * Benachrichtigung geht nie direkt, sondern über die App mit Bestätigung (Paket 3.3).
 */
class SpaceRunController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Beendet den laufenden Space. Die Bestätigung hat die Oberfläche schon eingeholt. */
        fun end() {
            context.startService(SpaceServerService.stopIntent(context))
        }

        /** Bestätigt den Wechsel in ein neues WLAN. Der Server bindet dann an die neue Adresse. */
        fun confirmNetwork() {
            context.startService(SpaceServerService.confirmNetworkIntent(context))
        }

        /** Quittiert den Hinweis zur automatisch erneuerten Einladung. Nur aufrufen, solange ein Server läuft. */
        fun acknowledgeInvite() {
            context.startService(SpaceServerService.inviteSettledIntent(context))
        }

        /** Verlängert den laufenden Space auf eine neue Endzeit. Die Prüfung und das Speichern macht der Dienst (Paket 3.6a). */
        fun extend(
            spaceId: SpaceId,
            endsAtMillis: Long,
        ) {
            context.startService(SpaceServerService.extendIntent(context, spaceId, endsAtMillis))
        }

        /**
         * Schließt einen Space ab, dessen Frist um ist, während kein Server läuft. Die Oberfläche ruft das nur, wenn die Frist
         * nach der Uhr schon erreicht ist. Der Dienst prüft die Frist selbst noch einmal.
         */
        fun expire() {
            context.startService(SpaceServerService.expireIntent(context))
        }
    }
