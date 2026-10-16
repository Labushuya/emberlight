package io.github.labushuya.emberlight.feature.gallery.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Systemseite „App-Info“, dort lassen sich Berechtigungen dauerhaft verweigerter Anfragen ändern. */
internal fun appSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Die Activity hinter einem Kontext, falls vorhanden. Compose liefert oft einen ContextWrapper. */
internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * `true`, wenn eine der Berechtigungen eine Erklärung verlangt, also erneut anfragbar ist. Ohne Activity gibt es keine
 * Auskunft, dann gilt `false` und die UI führt in die Einstellungen.
 */
internal fun Activity?.anyPermissionRationale(permissions: List<String>): Boolean =
    this != null && permissions.any { shouldShowRequestPermissionRationale(it) }
