package io.github.labushuya.emberlight.feature.space.setup

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Speicher der Einrichtung: Selbstbestätigung der Hersteller-Freigabe und die Frage, ob eine Berechtigung schon einmal
 * angefragt wurde (Unterscheidung „nicht gefragt“ und „dauerhaft abgelehnt“). Keine Nutzerdaten, nur Schalter.
 */
class SetupStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun oemConfirmed(): Boolean = prefs.getBoolean(KEY_OEM_CONFIRMED, false)

    fun setOemConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_OEM_CONFIRMED, confirmed).apply()
    }

    fun wasAsked(permission: String): Boolean = prefs.getBoolean(askedKey(permission), false)

    fun markAsked(permission: String) {
        prefs.edit().putBoolean(askedKey(permission), true).apply()
    }

    private fun askedKey(permission: String): String = "asked:$permission"

    private companion object {
        const val FILE = "space_setup"
        const val KEY_OEM_CONFIRMED = "oem_confirmed"
    }
}

/**
 * Die Prüfungen gegen das echte Gerät. Die Berechtigungs-Rationale braucht eine Aktivität. Sie kommt von der Oberfläche
 * ([canAskAgain]), damit diese Klasse ohne Aktivität auskommt.
 */
class AndroidSetupEnvironment(
    private val context: Context,
    private val store: SetupStore,
    private val canAskAgain: (String) -> Boolean,
) : SetupEnvironment {
    override val sdkInt: Int = Build.VERSION.SDK_INT

    override val targetSdk: Int = context.applicationInfo.targetSdkVersion

    override val notificationsGranted: Boolean
        get() =
            Build.VERSION.SDK_INT < SetupPermissions.NOTIFICATIONS_SDK ||
                granted(SetupPermissions.NOTIFICATIONS)

    override val localNetworkGranted: Boolean
        get() = granted(SetupPermissions.LOCAL_NETWORK)

    override val batteryIgnored: Boolean
        get() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override val backgroundRestricted: Boolean
        get() = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /** Standby-Stufe der App. Eingeschränkt heißt: Android drosselt sie stark (API 28 und neuer). */
    override val standbyRestricted: Boolean
        get() {
            val manager = context.getSystemService(UsageStatsManager::class.java) ?: return false
            return manager.appStandbyBucket >= UsageStatsManager.STANDBY_BUCKET_RESTRICTED
        }

    override val oemConfirmed: Boolean
        get() = store.oemConfirmed()

    override fun wasAsked(permission: String): Boolean = store.wasAsked(permission)

    override fun canAskAgain(permission: String): Boolean = canAskAgain.invoke(permission)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Android-Umsetzung von [OemPort]: Komponenten prüfen, Intents starten. */
class ContextOemPort(
    private val context: Context,
) : OemPort {
    override val ownPackage: String = context.packageName

    override fun resolves(component: ComponentName): Boolean {
        val intent = Intent().setComponent(component)
        return intent.resolveActivity(context.packageManager) != null
    }

    override fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
