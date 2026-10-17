package io.github.labushuya.emberlight.feature.gallery.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.labushuya.emberlight.core.media.permission.requiredPermissions

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

/**
 * Systemdialog für die Medienberechtigungen. Liefert die Aktion zum Anfragen. [onResult] meldet die Antwort, [onResume]
 * jede Rückkehr in den Vordergrund. Beide Male mit dem Stand der Erklärung (ob der Dialog erneut erscheinen darf).
 * Galerie und Album nutzen dieselbe Anfrage.
 */
@Composable
internal fun rememberMediaPermissionRequest(
    onResult: (showRationale: Boolean) -> Unit,
    onResume: (showRationale: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val permissions = remember { requiredPermissions(Build.VERSION.SDK_INT) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Die Antwort selbst liest der Index neu. Hier zählt nur, ob ein erneuter Dialog noch möglich ist.
            onResult(activity.anyPermissionRationale(permissions))
        }

    val currentOnResume by rememberUpdatedState(onResume)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        currentOnResume(activity.anyPermissionRationale(permissions))
    }

    return remember(launcher, permissions) { { launcher.launch(permissions.toTypedArray()) } }
}

/** Öffnet die Systemeinstellungen dieser App. Für Fälle, in denen der Dialog nicht mehr erscheint. */
@Composable
internal fun rememberAppSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { context.startActivity(appSettingsIntent(context)) } }
}
