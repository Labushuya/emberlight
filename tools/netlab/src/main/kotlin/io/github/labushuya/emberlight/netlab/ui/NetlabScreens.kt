package io.github.labushuya.emberlight.netlab.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.netlab.NetlabUiState
import io.github.labushuya.emberlight.netlab.R
import io.github.labushuya.emberlight.netlab.android.DeviceProbe
import io.github.labushuya.emberlight.netlab.capability.CapabilitySummary
import io.github.labushuya.emberlight.netlab.capability.Flag
import io.github.labushuya.emberlight.netlab.capability.NetworkFact
import io.github.labushuya.emberlight.netlab.capability.Transport
import io.github.labushuya.emberlight.netlab.payload.JoinPayload
import io.github.labushuya.emberlight.netlab.payload.PayloadError
import io.github.labushuya.emberlight.netlab.report.ErrorKind
import io.github.labushuya.emberlight.netlab.report.HotspotFailure
import io.github.labushuya.emberlight.netlab.report.ProbeResult
import io.github.labushuya.emberlight.netlab.report.Role
import io.github.labushuya.emberlight.netlab.report.TimelineEvent
import io.github.labushuya.emberlight.netlab.report.TransferResult

private const val PERMISSION_PREFIX = "android.permission."
private const val CAMERA_PERMISSION = "android.permission.CAMERA"

/** Text aus der Zwischenablage, falls vorhanden. */
private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** Wurzel der Oberfläche: Rollenwahl, dann Gastgeber- oder Gast-Bildschirm, darüber der Bericht. */
@Composable
fun NetlabScreens(
    state: NetlabUiState,
    onRefresh: () -> Unit,
    onRole: (Role) -> Unit,
    onStartHost: () -> Unit,
    onStopHost: () -> Unit,
    onAcceptJoin: (String) -> Unit,
    onConnectGuest: () -> Unit,
    onRunGuestTests: () -> Unit,
    onDisconnectGuest: () -> Unit,
    onBuildReport: () -> Unit,
    onCloseReport: () -> Unit,
) {
    val context = LocalContext.current
    val report = state.report
    when {
        report != null -> {
            ReportScreen(report = report, onClose = onCloseReport)
        }

        state.role == Role.HOST -> {
            HostScreen(state, onStartHost, onStopHost, onBuildReport, onBack = { onRole(Role.NONE) })
        }

        state.role == Role.GUEST -> {
            GuestScreen(
                state = state,
                onAcceptJoin = onAcceptJoin,
                onConnect = onConnectGuest,
                onRunTests = onRunGuestTests,
                onDisconnect = onDisconnectGuest,
                onBuildReport = onBuildReport,
                onBack = { onRole(Role.NONE) },
            )
        }

        else -> {
            HomeScreen(state.capabilities, onRefresh, onRole, context)
        }
    }
}

@Composable
private fun HomeScreen(
    capabilities: CapabilitySummary?,
    onRefresh: () -> Unit,
    onRole: (Role) -> Unit,
    context: Context,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading(R.string.app_name, MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_intro))
        Button(onClick = { onRole(Role.HOST) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_role_host))
        }
        Button(onClick = { onRole(Role.GUEST) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_role_guest))
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_refresh))
        }
        if (capabilities == null) {
            Text(stringResource(R.string.home_loading))
        } else {
            CapabilityBlock(capabilities, context)
        }
    }
}

@Composable
private fun CapabilityBlock(
    caps: CapabilitySummary,
    context: Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading(R.string.caps_title, MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.caps_device, caps.manufacturer, caps.model, caps.sdkInt))
        Text(stringResource(R.string.caps_sta_ap, flagText(caps.staAp)))
        Text(stringResource(R.string.caps_sta_local, flagText(caps.staLocalOnly)))
        Text(stringResource(R.string.caps_band5, flagText(caps.band5GHz)))
        Text(stringResource(R.string.caps_location, flagText(caps.locationServices)))
        Text(stringResource(R.string.caps_networks_title), style = MaterialTheme.typography.titleSmall)
        if (caps.networks.isEmpty()) Text(stringResource(R.string.caps_networks_none))
        caps.networks.forEach { Text(networkText(it)) }
        Text(stringResource(R.string.caps_permissions_title), style = MaterialTheme.typography.titleSmall)
        DeviceProbe.permissionNames(caps.sdkInt).forEach { name ->
            val granted = DeviceProbe.hasPermission(context, name)
            Text(
                stringResource(
                    if (granted) R.string.caps_permission_granted else R.string.caps_permission_missing,
                    name.removePrefix(PERMISSION_PREFIX),
                ),
            )
        }
    }
}

@Composable
private fun HostScreen(
    state: NetlabUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBuildReport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    val missing = hostPermissionsMissing(context)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) onStart()
        }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading(R.string.host_title, MaterialTheme.typography.headlineSmall)
        if (missing.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.host_permissions_missing,
                    missing.joinToString { it.removePrefix(PERMISSION_PREFIX) },
                ),
            )
        }
        if (state.capabilities?.locationServices == Flag.NO && sdk <= Build.VERSION_CODES.S_V2) {
            Text(stringResource(R.string.host_location_off))
        }
        if (!state.hostActive) {
            Button(
                onClick = { if (missing.isEmpty()) onStart() else launcher.launch(missing.toTypedArray()) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.busy) R.string.host_starting else R.string.host_start))
            }
        }
        state.hotspot?.let { outcome ->
            if (!outcome.started) {
                Text(stringResource(R.string.host_failed, failureText(outcome.failure)))
            }
        }
        if (state.hostActive) {
            HostDetails(state)
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.host_stop)) }
        }
        OutlinedButton(onClick = onBuildReport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.report_build))
        }
        if (!state.hostActive) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun HostDetails(state: NetlabUiState) {
    val outcome = state.hotspot ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.host_running), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.host_ssid, outcome.ssid.orEmpty()))
        Text(stringResource(R.string.host_password, outcome.passphrase.orEmpty()))
        Text(stringResource(R.string.host_security, outcome.securityType ?: stringResource(R.string.unknown)))
        Text(stringResource(R.string.host_band, outcome.bands?.joinToString() ?: stringResource(R.string.unknown)))
        Text(stringResource(R.string.host_address, outcome.hostAddress ?: stringResource(R.string.unknown)))
        state.hostPayload?.let { payload ->
            Text(stringResource(R.string.host_qr_app))
            QrCodeImage(payload.encode(), stringResource(R.string.host_qr_app_description))
        }
        state.hostWifiQr?.let { wifi ->
            Text(stringResource(R.string.host_qr_wifi))
            QrCodeImage(wifi, stringResource(R.string.host_qr_wifi_description))
        }
        state.hostBrowserUrl?.let { url ->
            Text(stringResource(R.string.host_qr_browser))
            QrCodeImage(url, stringResource(R.string.host_qr_browser_description))
            Text(url)
        }
        Text(stringResource(R.string.host_guests, state.guests?.total ?: 0, state.guests?.active ?: 0))
        Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleSmall)
        TimelineList(state.hostTimeline)
    }
}

@Composable
private fun GuestScreen(
    state: NetlabUiState,
    onAcceptJoin: (String) -> Unit,
    onConnect: () -> Unit,
    onRunTests: () -> Unit,
    onDisconnect: () -> Unit,
    onBuildReport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            scanning = granted
        }
    if (scanning) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.guest_scan_hint))
            QrScanner(
                onResult = { text ->
                    scanning = false
                    onAcceptJoin(text)
                },
                onUnavailable = { scanning = false },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.guest_scan_cancel))
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading(R.string.guest_title, MaterialTheme.typography.headlineSmall)
        Button(
            onClick = {
                if (DeviceProbe.hasPermission(context, CAMERA_PERMISSION)) {
                    scanning = true
                } else {
                    cameraLauncher.launch(CAMERA_PERMISSION)
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.guest_scan)) }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.guest_paste_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onAcceptJoin(typed) }) { Text(stringResource(R.string.guest_use_text)) }
            OutlinedButton(onClick = {
                clipboardText(context)?.let {
                    typed = it
                    onAcceptJoin(it)
                }
            }) {
                Text(stringResource(R.string.guest_paste))
            }
        }
        state.guestPayloadError?.let { Text(stringResource(payloadErrorText(it))) }
        state.guestPayload?.let { GuestPayloadBlock(it, state, onConnect, onRunTests, onDisconnect) }
        OutlinedButton(onClick = onBuildReport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.report_build))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun GuestPayloadBlock(
    payload: JoinPayload,
    state: NetlabUiState,
    onConnect: () -> Unit,
    onRunTests: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val manual = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.guest_payload_ok, payload.ssid, payload.host, payload.port))
        if (manual) {
            Text(stringResource(R.string.guest_manual_hint, payload.ssid, payload.passphrase))
        }
        if (!state.guestConnected) {
            Button(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        when {
                            state.busy -> R.string.guest_connecting
                            manual -> R.string.guest_wait_manual
                            else -> R.string.guest_connect
                        },
                    ),
                )
            }
            state.guestConnect?.takeIf { !it.ok }?.let {
                Text(stringResource(R.string.guest_connect_failed, errorText(it.error)))
            }
        } else {
            Text(stringResource(R.string.guest_connected))
            Button(onClick = onRunTests, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.busy) R.string.guest_tests_running else R.string.guest_tests))
            }
            state.guestRun?.let { run ->
                run.bound.forEach { Text(probeText(it, R.string.guest_result_bound)) }
                run.upload?.let { Text(transferText(it)) }
                run.unbound?.let { Text(probeText(it, R.string.guest_result_unbound)) }
                run.vpnActive?.let {
                    val answer = stringResource(if (it) R.string.yes else R.string.no)
                    Text(stringResource(R.string.guest_result_vpn, answer))
                }
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.guest_disconnect))
            }
        }
        Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleSmall)
        TimelineList(state.guestTimeline)
    }
}

@Composable
private fun ReportScreen(
    report: String,
    onClose: () -> Unit,
) {
    val shareTitle = stringResource(R.string.report_share)
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading(R.string.report_title, MaterialTheme.typography.headlineSmall)
        SelectionContainer { Text(report) }
        Button(
            onClick = {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                context.startActivity(Intent.createChooser(send, shareTitle))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.report_share)) }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun TimelineList(events: List<TimelineEvent>) {
    if (events.isEmpty()) {
        Text(stringResource(R.string.timeline_empty))
        return
    }
    events.takeLast(TIMELINE_SHOWN).forEach { event ->
        Text(stringResource(R.string.timeline_entry, formatMillis(event.atMs), event.text))
    }
}

private const val TIMELINE_SHOWN = 30

private fun formatMillis(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
private fun flagText(flag: Flag): String =
    stringResource(
        when (flag) {
            Flag.YES -> R.string.yes
            Flag.NO -> R.string.no
            Flag.UNAVAILABLE -> R.string.caps_unavailable
        },
    )

@Composable
private fun networkText(network: NetworkFact): String {
    val transports =
        network.transports
            .sortedBy { it.ordinal }
            .map { transport ->
                stringResource(
                    when (transport) {
                        Transport.WIFI -> R.string.transport_wifi
                        Transport.CELLULAR -> R.string.transport_cellular
                        Transport.VPN -> R.string.transport_vpn
                        Transport.ETHERNET -> R.string.transport_ethernet
                        Transport.OTHER -> R.string.transport_other
                    },
                )
            }.joinToString("+")
    val validated = stringResource(if (network.validated) R.string.validated_yes else R.string.validated_no)
    return if (network.isDefault) {
        stringResource(R.string.network_line_default, transports, validated)
    } else {
        stringResource(R.string.network_line, transports, validated)
    }
}

@Composable
private fun failureText(failure: HotspotFailure?): String =
    stringResource(
        when (failure) {
            HotspotFailure.GENERIC -> R.string.failure_generic
            HotspotFailure.NO_CHANNEL -> R.string.failure_no_channel
            HotspotFailure.INCOMPATIBLE_MODE -> R.string.failure_incompatible_mode
            HotspotFailure.TETHERING_DISALLOWED -> R.string.failure_tethering
            HotspotFailure.UNKNOWN, null -> R.string.failure_unknown
        },
    )

private fun payloadErrorText(error: PayloadError): Int =
    when (error) {
        PayloadError.WRONG_FORMAT -> R.string.payload_error_format
        PayloadError.WRONG_VERSION -> R.string.payload_error_version
        PayloadError.MISSING_FIELD -> R.string.payload_error_missing
        PayloadError.INVALID_SSID -> R.string.payload_error_ssid
        PayloadError.INVALID_PASSPHRASE -> R.string.payload_error_passphrase
        PayloadError.INVALID_HOST -> R.string.payload_error_host
        PayloadError.INVALID_PORT -> R.string.payload_error_port
        PayloadError.INVALID_TOKEN -> R.string.payload_error_token
    }

@Composable
private fun errorText(error: ErrorKind): String =
    stringResource(
        when (error) {
            ErrorKind.TIMEOUT -> R.string.err_timeout
            ErrorKind.REFUSED -> R.string.err_refused
            ErrorKind.UNKNOWN_HOST -> R.string.err_unknown_host
            ErrorKind.NO_ROUTE -> R.string.err_no_route
            ErrorKind.UNAVAILABLE_NETWORK -> R.string.err_unavailable
            ErrorKind.HTTP_STATUS -> R.string.err_http
            ErrorKind.IO -> R.string.err_io
            ErrorKind.OTHER -> R.string.err_other
            ErrorKind.NONE -> R.string.err_none
        },
    )

@Composable
private fun probeText(
    result: ProbeResult,
    labelRes: Int,
): String {
    val label = stringResource(labelRes)
    return if (result.ok) {
        stringResource(R.string.result_ok_ms, label, result.millis ?: 0L)
    } else {
        stringResource(R.string.result_failed, label, errorText(result.error))
    }
}

@Composable
private fun transferText(result: TransferResult): String {
    val mbit = result.mbitPerSecond
    return if (result.ok && mbit != null) {
        stringResource(R.string.result_upload_ok, result.bytes / 1_000_000, result.millis, "%.1f".format(mbit))
    } else {
        stringResource(R.string.result_upload_failed, errorText(result.error))
    }
}

/** Berechtigungen, die der Gastgeber vor dem Hotspot braucht und die noch fehlen. Kamera zählt nicht dazu. */
private fun hostPermissionsMissing(context: Context): List<String> =
    DeviceProbe
        .permissionNames(Build.VERSION.SDK_INT)
        .filter { it != "${PERMISSION_PREFIX}CAMERA" && !DeviceProbe.hasPermission(context, it) }

@Composable
private fun Heading(
    text: Int,
    style: TextStyle,
) {
    Text(stringResource(text), style = style, modifier = Modifier.semantics { heading() })
}
