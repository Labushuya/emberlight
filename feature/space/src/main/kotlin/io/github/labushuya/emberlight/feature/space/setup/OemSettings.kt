package io.github.labushuya.emberlight.feature.space.setup

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.Locale

/**
 * Hersteller-Familien für die Hintergrund-Freigabe (ADR 0017). Die Erkennung nutzt `Build.MANUFACTURER` und `Build.BRAND`,
 * kleingeschrieben. Ein unbekannter Hersteller bekommt die allgemeine Anleitung und dieselbe Fallback-Kette.
 */
enum class OemFamily(
    val candidates: List<ComponentName>,
) {
    HONOR(
        listOf(
            component("com.hihonor.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", ".optimize.process.ProtectActivity"),
            component("com.huawei.systemmanager", ".power.ui.HwPowerManagerActivity"),
        ),
    ),
    HUAWEI(
        listOf(
            component("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", ".optimize.process.ProtectActivity"),
            component("com.huawei.systemmanager", ".power.ui.HwPowerManagerActivity"),
        ),
    ),
    XIAOMI(
        listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
    ),
    SAMSUNG(
        listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
    ),
    ONEPLUS(
        listOf(
            component("com.oneplus.security", ".chainlaunch.view.ChainLaunchAppListActivity"),
        ),
    ),
    OPPO(
        listOf(
            component("com.coloros.safecenter", ".permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", ".startupapp.StartupAppListActivity"),
            component("com.oppo.safe", ".permission.startup.StartupAppListActivity"),
        ),
    ),
    VIVO(
        listOf(
            component("com.vivo.permissionmanager", ".activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", ".ui.phoneoptimize.BgStartUpManager"),
            component("com.iqoo.secure", ".ui.phoneoptimize.AddWhiteListActivity"),
        ),
    ),
    ASUS(
        listOf(
            component("com.asus.mobilemanager", ".autostart.AutoStartActivity"),
            component("com.asus.mobilemanager", ".powersaver.PowerSaverSettings"),
        ),
    ),
    NOKIA(
        listOf(
            component("com.evenwell.powersaving.g3", ".exception.PowerSaverExceptionActivity"),
        ),
    ),
    MOTOROLA(emptyList()),
    SONY(emptyList()),
    PIXEL(emptyList()),
    OTHER(emptyList()),
    ;

    companion object {
        /** Erkennt die Familie aus Hersteller und Marke. Beides klein, Teilwörter wie „redmi“ zählen mit. */
        fun detect(
            manufacturer: String,
            brand: String,
        ): OemFamily {
            val text = "$manufacturer $brand".lowercase(Locale.ROOT)
            return when {
                "honor" in text -> HONOR
                "huawei" in text -> HUAWEI
                "xiaomi" in text || "redmi" in text || "poco" in text -> XIAOMI
                "samsung" in text -> SAMSUNG
                "oneplus" in text -> ONEPLUS
                "oppo" in text || "realme" in text -> OPPO
                "vivo" in text || "iqoo" in text -> VIVO
                "asus" in text -> ASUS
                "nokia" in text || "hmd" in text -> NOKIA
                "motorola" in text -> MOTOROLA
                "sony" in text -> SONY
                "google" in text -> PIXEL
                else -> OTHER
            }
        }

        /**
         * Pakete der Hersteller-Einstellungen. Stehen im Manifest unter `<queries>`, damit `resolveActivity` sie sieht.
         */
        val PACKAGES: List<String> =
            listOf(
                "com.hihonor.systemmanager",
                "com.huawei.systemmanager",
                "com.miui.securitycenter",
                "com.samsung.android.lool",
                "com.oneplus.security",
                "com.coloros.safecenter",
                "com.oppo.safe",
                "com.vivo.permissionmanager",
                "com.iqoo.secure",
                "com.asus.mobilemanager",
                "com.evenwell.powersaving.g3",
            )
    }
}

/** Ergebnis des Öffnens. Die Oberfläche sagt dem Nutzer, was geklappt hat. */
sealed interface OemOpenResult {
    /** Die Herstellerseite hat sich geöffnet. */
    data class Vendor(
        val component: ComponentName,
    ) : OemOpenResult

    /** Die App-Info öffnete sich. Dort liegt die Akku- und Hintergrund-Einstellung oft. */
    data object AppDetails : OemOpenResult

    /** Die allgemeine Akku-Liste öffnete sich. */
    data object BatteryList : OemOpenResult

    /** Nichts ließ sich öffnen. Der Nutzer geht von Hand in die Einstellungen. */
    data object Failed : OemOpenResult
}

/** Zugriff auf Pakete und Aktivitäten. Die Android-Umsetzung ist [ContextOemPort], Tests setzen eigene Ports. */
interface OemPort {
    /** `true`, wenn sich die Komponente öffnen lässt (`resolveActivity`). */
    fun resolves(component: ComponentName): Boolean

    /** Startet die Aktivität. Wirft [ActivityNotFoundException] oder [SecurityException], wenn das nicht geht. */
    fun start(intent: Intent)

    /** Der eigene Paketname, für die App-Info. */
    val ownPackage: String
}

/**
 * Öffnet die Hersteller-Einstellung mit der Fallback-Kette aus ADR 0017:
 * 1. Kandidaten je Hersteller, nur wenn die Komponente aufgelöst wird. Fehler beim Starten sind abzufangen.
 * 2. App-Info des eigenen Pakets.
 * 3. Allgemeine Akku-Liste.
 */
class OemSettingsLauncher(
    private val port: OemPort,
) {
    fun open(family: OemFamily): OemOpenResult {
        for (component in family.candidates) {
            if (!port.resolves(component)) continue
            val started = attempt(Intent().setComponent(component))
            if (started) return OemOpenResult.Vendor(component)
        }
        if (attempt(appDetailsIntent())) return OemOpenResult.AppDetails
        if (attempt(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return OemOpenResult.BatteryList
        return OemOpenResult.Failed
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", port.ownPackage, null))

    private fun attempt(intent: Intent): Boolean =
        try {
            port.start(intent)
            true
        } catch (failure: ActivityNotFoundException) {
            false
        } catch (failure: SecurityException) {
            false
        }
}

private fun component(
    packageName: String,
    className: String,
): ComponentName = ComponentName(packageName, packageName + className)
