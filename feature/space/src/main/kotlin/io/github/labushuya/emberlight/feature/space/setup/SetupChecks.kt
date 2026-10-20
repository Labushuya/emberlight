package io.github.labushuya.emberlight.feature.space.setup

/** Die Punkte der Einrichtung (Mockup Abschnitt 3, ADR 0012, ADR 0017). Die Reihenfolge ist die der Anzeige. */
enum class CheckKind {
    NOTIFICATIONS,
    LOCAL_NETWORK,
    BATTERY,
    OEM_BACKGROUND,
    BACKGROUND_RESTRICTED,
    CHARGER,
}

/** Status eines Punktes. [CONFIRMED] und [NOT_CHECKABLE] gehören zur Hersteller-Freigabe, die sich nicht abfragen lässt. */
enum class CheckStatus {
    MET,
    OPEN,
    DENIED,
    PERMANENTLY_DENIED,
    CONFIRMED,
    NOT_CHECKABLE,
    INFO,
}

/** Ein Punkt mit Status. [required] bedeutet: ohne diesen Punkt startet der Space nicht. */
data class SetupItem(
    val kind: CheckKind,
    val status: CheckStatus,
    val required: Boolean,
)

/** Schranke vor dem Start: Pflicht-Punkte blockieren, Empfohlene lassen sich mit „Trotzdem starten“ überspringen. */
data class StartGate(
    val blocked: Boolean,
    val recommendedOpen: List<CheckKind>,
)

/**
 * Was die Prüfung vom Gerät braucht. Die Android-Umsetzung steht in [AndroidSetupEnvironment]. Tests setzen die Werte
 * direkt.
 */
interface SetupEnvironment {
    /** `Build.VERSION.SDK_INT` des Geräts. */
    val sdkInt: Int

    /** `targetSdkVersion` der App. */
    val targetSdk: Int

    val notificationsGranted: Boolean
    val localNetworkGranted: Boolean
    val batteryIgnored: Boolean
    val backgroundRestricted: Boolean
    val standbyRestricted: Boolean
    val oemConfirmed: Boolean

    /** `true`, wenn die Berechtigung schon einmal angefragt wurde. Ohne diese Angabe ist „nicht gefragt“ und „dauerhaft abgelehnt“ nicht zu unterscheiden. */
    fun wasAsked(permission: String): Boolean

    /** `true`, wenn Android eine erneute Anfrage zeigt (Begründung nötig). `false` nach „Nicht mehr fragen“. */
    fun canAskAgain(permission: String): Boolean
}

/** Schlüssel der Berechtigungen, die die Einrichtung prüft. */
object SetupPermissions {
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Ab dieser API braucht die Benachrichtigung eine Laufzeit-Erlaubnis. */
    const val NOTIFICATIONS_SDK = 33

    /** Ab dieser API (und mit targetSdk 37) gilt `ACCESS_LOCAL_NETWORK` zur Laufzeit. */
    const val LOCAL_NETWORK_SDK = 37
}

/** Auswertung der Einrichtung. Ohne Android, reine Logik. */
object SetupChecks {
    fun items(env: SetupEnvironment): List<SetupItem> =
        buildList {
            add(SetupItem(CheckKind.NOTIFICATIONS, notifications(env), required = false))
            if (localNetworkApplies(env)) {
                add(SetupItem(CheckKind.LOCAL_NETWORK, localNetwork(env), required = true))
            }
            add(
                SetupItem(
                    CheckKind.BATTERY,
                    if (env.batteryIgnored) CheckStatus.MET else CheckStatus.OPEN,
                    required = false,
                ),
            )
            add(
                SetupItem(
                    CheckKind.OEM_BACKGROUND,
                    if (env.oemConfirmed) CheckStatus.CONFIRMED else CheckStatus.OPEN,
                    required = false,
                ),
            )
            if (env.backgroundRestricted || env.standbyRestricted) {
                add(SetupItem(CheckKind.BACKGROUND_RESTRICTED, CheckStatus.OPEN, required = false))
            }
            add(SetupItem(CheckKind.CHARGER, CheckStatus.INFO, required = false))
        }

    /** Ob der Punkt „Lokales Netzwerk“ gilt. Vor API 37 oder mit älterem targetSdk ist er ausgeblendet (ADR 0012). */
    fun localNetworkApplies(env: SetupEnvironment): Boolean =
        env.sdkInt >= SetupPermissions.LOCAL_NETWORK_SDK && env.targetSdk >= SetupPermissions.LOCAL_NETWORK_SDK

    fun gate(items: List<SetupItem>): StartGate =
        StartGate(
            blocked = items.any { it.required && it.status != CheckStatus.MET },
            recommendedOpen =
                items
                    .filter { !it.required && it.status in OPEN_STATES }
                    .map { it.kind },
        )

    private fun notifications(env: SetupEnvironment): CheckStatus =
        if (env.sdkInt < SetupPermissions.NOTIFICATIONS_SDK) {
            CheckStatus.MET
        } else {
            permission(env.notificationsGranted, SetupPermissions.NOTIFICATIONS, env)
        }

    private fun localNetwork(env: SetupEnvironment): CheckStatus =
        permission(env.localNetworkGranted, SetupPermissions.LOCAL_NETWORK, env)

    /** Erteilt, offen, abgelehnt oder dauerhaft abgelehnt. Die letzten beiden unterscheidet `canAskAgain`. */
    private fun permission(
        granted: Boolean,
        permission: String,
        env: SetupEnvironment,
    ): CheckStatus =
        when {
            granted -> CheckStatus.MET
            !env.wasAsked(permission) -> CheckStatus.OPEN
            env.canAskAgain(permission) -> CheckStatus.DENIED
            else -> CheckStatus.PERMANENTLY_DENIED
        }

    private val OPEN_STATES = setOf(CheckStatus.OPEN, CheckStatus.DENIED, CheckStatus.PERMANENTLY_DENIED)
}
