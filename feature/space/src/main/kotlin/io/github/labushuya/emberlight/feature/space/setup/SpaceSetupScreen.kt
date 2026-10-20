package io.github.labushuya.emberlight.feature.space.setup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.space.run.StartProblem

/**
 * Einrichtung eines Space (Mockup Abschnitt 3). Die Punkte werden bei jedem Zurückkehren neu geprüft. Im Start-Modus
 * startet der Knopf unten den Space, nach der Prüfung der Pflicht-Punkte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceSetupScreen(
    onBack: () -> Unit,
    onRunning: (String) -> Unit,
    onEdit: (String) -> Unit,
    viewModel: SpaceSetupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val family = remember { OemFamily.detect(Build.MANUFACTURER, Build.BRAND) }
    val env =
        remember(activity) {
            AndroidSetupEnvironment(context.applicationContext, viewModel.store) { permission ->
                activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true
            }
        }
    var rationaleFor by rememberSaveable { mutableStateOf<String?>(null) }
    var askAnyway by rememberSaveable { mutableStateOf(false) }
    var oemFailed by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(env) }
    androidx.compose.runtime.LaunchedEffect(env) { viewModel.refresh(env) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh(env) }
    val localNetworkLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh(env) }
    val batteryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh(env) }

    fun requestPermission(
        permission: String,
        launcher: ActivityResultLauncher<String>,
    ) {
        viewModel.markAsked(permission)
        launcher.launch(permission)
    }

    val onAction: (CheckKind) -> Unit = { kind ->
        when (kind) {
            CheckKind.NOTIFICATIONS -> {
                val item = state.items.firstOrNull { it.kind == kind }
                if (item?.status == CheckStatus.PERMANENTLY_DENIED) {
                    openAppSettings(context)
                } else {
                    requestPermission(SetupPermissions.NOTIFICATIONS, notificationLauncher)
                }
            }

            CheckKind.LOCAL_NETWORK -> {
                val item = state.items.firstOrNull { it.kind == kind }
                when (item?.status) {
                    CheckStatus.PERMANENTLY_DENIED -> openAppSettings(context)
                    CheckStatus.DENIED -> rationaleFor = SetupPermissions.LOCAL_NETWORK
                    else -> rationaleFor = SetupPermissions.LOCAL_NETWORK
                }
            }

            CheckKind.BATTERY -> {
                requestBatteryExemption(context, batteryLauncher)
            }

            CheckKind.BACKGROUND_RESTRICTED -> {
                openAppSettings(context)
            }

            CheckKind.OEM_BACKGROUND, CheckKind.CHARGER -> {}
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.space_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        SpaceSetupContent(
            items = state.items,
            gate = state.gate,
            startMode = viewModel.startMode,
            starting = state.starting,
            family = family,
            oemConfirmed = env.oemConfirmed,
            oemFailed = oemFailed,
            onAction = onAction,
            onOemOpen = {
                val result = OemSettingsLauncher(ContextOemPort(context.applicationContext)).open(family)
                oemFailed = result == OemOpenResult.Failed
            },
            onOemConfirm = { confirmed ->
                viewModel.confirmOem(confirmed)
                viewModel.refresh(env)
            },
            onStart = {
                if (state.gate.recommendedOpen.isEmpty()) {
                    viewModel.start(onStarted = { viewModel.spaceId?.let { onRunning(it.value) } })
                } else {
                    askAnyway = true
                }
            },
            modifier = Modifier.padding(padding),
        )
    }

    if (rationaleFor == SetupPermissions.LOCAL_NETWORK) {
        AlertDialog(
            onDismissRequest = { rationaleFor = null },
            title = { Text(stringResource(R.string.space_setup_rationale_title)) },
            text = { Text(stringResource(R.string.space_setup_rationale_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        rationaleFor = null
                        requestPermission(SetupPermissions.LOCAL_NETWORK, localNetworkLauncher)
                    },
                ) { Text(stringResource(R.string.space_setup_rationale_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { rationaleFor = null }) { Text(stringResource(R.string.space_cancel)) }
            },
        )
    }

    if (askAnyway) {
        AlertDialog(
            onDismissRequest = { askAnyway = false },
            title = { Text(stringResource(R.string.space_setup_start_anyway_title)) },
            text = { Text(stringResource(R.string.space_setup_start_anyway_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askAnyway = false
                        viewModel.start(onStarted = { viewModel.spaceId?.let { onRunning(it.value) } })
                    },
                ) { Text(stringResource(R.string.space_setup_start_anyway_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askAnyway = false }) { Text(stringResource(R.string.space_cancel)) }
            },
        )
    }

    state.outcome?.let { outcome ->
        StartOutcomeDialog(
            outcome = outcome,
            onDismiss = viewModel::dismissOutcome,
            onEdit = {
                viewModel.dismissOutcome()
                viewModel.spaceId?.let { onEdit(it.value) }
            },
        )
    }
}

/** Fehler des Startversuchs mit einem Text je Ursache. Bearbeiten führt zum Formular, wenn die Ursache dort liegt. */
@Composable
internal fun StartOutcomeDialog(
    outcome: StartOutcome,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_setup_error_title)) },
        text = { Text(outcomeText(outcome)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_setup_error_ok))
            }
        },
        dismissButton =
            if (outcomeNeedsEdit(outcome)) {
                {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.space_setup_error_edit)) }
                }
            } else {
                null
            },
    )
}

/**
 * Inhalt der Einrichtung ohne Zustand. Die Hülle [SpaceSetupScreen] reicht Ereignisse herein. So sind Anzeige und Goldens
 * ohne Berechtigungen und Aktivitäten prüfbar.
 */
@Composable
internal fun SpaceSetupContent(
    items: List<SetupItem>,
    gate: StartGate,
    startMode: Boolean,
    starting: Boolean,
    family: OemFamily,
    oemConfirmed: Boolean,
    oemFailed: Boolean,
    onAction: (CheckKind) -> Unit,
    onOemOpen: () -> Unit,
    onOemConfirm: (Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.space_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.space_setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SetupRow(
                    item = item,
                    family = family,
                    oemConfirmed = oemConfirmed,
                    oemFailed = oemFailed,
                    onAction = { onAction(item.kind) },
                    onOemOpen = onOemOpen,
                    onOemConfirm = onOemConfirm,
                )
            }
            if (startMode) {
                Spacer(Modifier.height(24.dp))
                if (gate.blocked) {
                    Text(
                        text = stringResource(R.string.space_setup_start_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onStart,
                    enabled = !gate.blocked && !starting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.space_setup_start))
                }
            }
        }
    }
}

/** Ein Punkt der Einrichtung: Titel, Pflicht oder Empfehlung, Status, Erklärung und Aktion. */
@Composable
private fun SetupRow(
    item: SetupItem,
    family: OemFamily,
    oemConfirmed: Boolean,
    oemFailed: Boolean,
    onAction: () -> Unit,
    onOemOpen: () -> Unit,
    onOemConfirm: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleRes(item.kind)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        if (item.required) R.string.space_setup_required else R.string.space_setup_recommended,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = statusText(item.status),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor(item.status),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = bodyText(item.kind, family),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (item.kind) {
            CheckKind.OEM_BACKGROUND -> {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOemOpen) { Text(stringResource(R.string.space_setup_oem_open)) }
                if (oemFailed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.space_setup_oem_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = oemConfirmed, onCheckedChange = onOemConfirm)
                    Text(
                        text = stringResource(R.string.space_setup_oem_done),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.space_setup_oem_done_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                actionLabel(item.kind, item.status)?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onAction) { Text(stringResource(label)) }
                }
            }
        }
    }
}

private fun titleRes(kind: CheckKind): Int =
    when (kind) {
        CheckKind.NOTIFICATIONS -> R.string.space_setup_notifications_title
        CheckKind.LOCAL_NETWORK -> R.string.space_setup_local_network_title
        CheckKind.BATTERY -> R.string.space_setup_battery_title
        CheckKind.OEM_BACKGROUND -> R.string.space_setup_oem_title
        CheckKind.BACKGROUND_RESTRICTED -> R.string.space_setup_background_title
        CheckKind.CHARGER -> R.string.space_setup_charger_title
    }

/** Erklärung je Punkt. Die Herstellerzeile wählt den Text der Familie. */
@Composable
private fun bodyText(
    kind: CheckKind,
    family: OemFamily,
): String =
    stringResource(
        when (kind) {
            CheckKind.NOTIFICATIONS -> R.string.space_setup_notifications_text
            CheckKind.LOCAL_NETWORK -> R.string.space_setup_local_network_text
            CheckKind.BATTERY -> R.string.space_setup_battery_text
            CheckKind.OEM_BACKGROUND -> oemTextRes(family)
            CheckKind.BACKGROUND_RESTRICTED -> R.string.space_setup_background_text
            CheckKind.CHARGER -> R.string.space_setup_charger_text
        },
    )

/** Text der Hersteller-Anleitung. Honor und Huawei teilen sich den Text, unbekannte Hersteller die allgemeine Anleitung. */
private fun oemTextRes(family: OemFamily): Int =
    when (family) {
        OemFamily.HONOR, OemFamily.HUAWEI -> R.string.space_setup_oem_honor
        OemFamily.XIAOMI -> R.string.space_setup_oem_xiaomi
        OemFamily.SAMSUNG -> R.string.space_setup_oem_samsung
        OemFamily.ONEPLUS -> R.string.space_setup_oem_oneplus
        OemFamily.OPPO -> R.string.space_setup_oem_oppo
        OemFamily.VIVO -> R.string.space_setup_oem_vivo
        OemFamily.ASUS -> R.string.space_setup_oem_asus
        OemFamily.NOKIA -> R.string.space_setup_oem_nokia
        OemFamily.MOTOROLA, OemFamily.SONY, OemFamily.PIXEL, OemFamily.OTHER -> R.string.space_setup_oem_generic
    }

@Composable
private fun statusText(status: CheckStatus): String =
    stringResource(
        when (status) {
            CheckStatus.MET -> R.string.space_setup_status_met
            CheckStatus.OPEN -> R.string.space_setup_status_open
            CheckStatus.DENIED -> R.string.space_setup_status_denied
            CheckStatus.PERMANENTLY_DENIED -> R.string.space_setup_status_permanently_denied
            CheckStatus.CONFIRMED -> R.string.space_setup_status_confirmed
            CheckStatus.NOT_CHECKABLE -> R.string.space_setup_status_not_checkable
            CheckStatus.INFO -> R.string.space_setup_status_info
        },
    )

@Composable
private fun statusColor(status: CheckStatus): androidx.compose.ui.graphics.Color =
    when (status) {
        CheckStatus.MET, CheckStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
        CheckStatus.DENIED, CheckStatus.PERMANENTLY_DENIED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** Beschriftung der Aktion je Punkt und Status. `null`, wenn es nichts zu tun gibt. */
private fun actionLabel(
    kind: CheckKind,
    status: CheckStatus,
): Int? =
    when (kind) {
        CheckKind.NOTIFICATIONS, CheckKind.LOCAL_NETWORK -> {
            when (status) {
                CheckStatus.OPEN, CheckStatus.DENIED -> R.string.space_setup_action_allow
                CheckStatus.PERMANENTLY_DENIED -> R.string.space_setup_action_app_settings
                else -> null
            }
        }

        CheckKind.BATTERY -> {
            if (status == CheckStatus.OPEN) R.string.space_setup_action_battery else null
        }

        CheckKind.BACKGROUND_RESTRICTED -> {
            if (status ==
                CheckStatus.OPEN
            ) {
                R.string.space_setup_action_app_info
            } else {
                null
            }
        }

        CheckKind.OEM_BACKGROUND, CheckKind.CHARGER -> {
            null
        }
    }

@Composable
private fun outcomeText(outcome: StartOutcome): String =
    stringResource(
        when (outcome) {
            StartOutcome.Started -> {
                R.string.space_setup_error_ok
            }

            StartOutcome.NoWifi -> {
                R.string.space_setup_error_no_wifi
            }

            StartOutcome.ServiceRefused -> {
                R.string.space_setup_error_service
            }

            is StartOutcome.Refused -> {
                when (outcome.problem) {
                    StartProblem.ANOTHER_ACTIVE -> R.string.space_setup_error_another_active
                    StartProblem.STORAGE_ABOVE_FREE -> R.string.space_setup_error_storage
                    StartProblem.END_IN_PAST -> R.string.space_setup_error_end_past
                    StartProblem.NOT_A_DRAFT -> R.string.space_setup_error_not_draft
                    StartProblem.NOT_FOUND -> R.string.space_setup_error_not_found
                }
            }
        },
    )

private fun outcomeNeedsEdit(outcome: StartOutcome): Boolean =
    outcome is StartOutcome.Refused && outcome.problem == StartProblem.STORAGE_ABOVE_FREE

// Die Akku-Anfrage ist bewusst gewählt (Sideloading, kein Play-Store, ADR 0017). Die Begründung steht in der Einrichtung.
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
) {
    val request =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
    try {
        launcher.launch(request)
    } catch (failure: ActivityNotFoundException) {
        launchOrIgnore(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (failure: SecurityException) {
        launchOrIgnore(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppSettings(context: Context) {
    launchOrIgnore(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

/** Öffnet eine Systemseite. Fehlt sie, bleibt der Nutzer hier, und der Hinweis am Punkt bleibt stehen. */
private fun launchOrIgnore(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent)
    } catch (failure: ActivityNotFoundException) {
        // Die Seite gibt es auf diesem Gerät nicht. Der Punkt bleibt offen.
    } catch (failure: SecurityException) {
        // Das System verbietet den Aufruf. Der Punkt bleibt offen.
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
