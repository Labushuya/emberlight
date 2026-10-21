package io.github.labushuya.emberlight.feature.space.run

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.feature.space.formatDate
import io.github.labushuya.emberlight.feature.space.formatGib
import io.github.labushuya.emberlight.feature.space.media.HostMediaItem
import io.github.labushuya.emberlight.feature.space.media.HostMediaUiState
import io.github.labushuya.emberlight.feature.space.media.HostMediaViewModel
import io.github.labushuya.emberlight.feature.space.media.MediaFilter
import io.github.labushuya.emberlight.feature.space.upload.HostUploadUiState
import io.github.labushuya.emberlight.feature.space.upload.HostUploadViewModel
import io.github.labushuya.emberlight.share.server.PauseCause
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.participants.ParticipantInfo
import io.github.labushuya.emberlight.share.space.run.PauseEvent

/**
 * Gastgeber-Ansicht des laufenden Space. Die Hülle lädt den Zustand, der Inhalt zeigt ihn ([SpaceRunningContent]). Das
 * Ende geht nur nach Bestätigung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceRunningScreen(
    id: String?,
    confirmEnd: Boolean,
    onBack: () -> Unit,
    onSetup: (String) -> Unit,
    viewModel: SpaceRunningViewModel = hiltViewModel(),
    uploadViewModel: HostUploadViewModel = hiltViewModel(),
    mediaViewModel: HostMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val upload by uploadViewModel.state.collectAsStateWithLifecycle()
    val media by mediaViewModel.state.collectAsStateWithLifecycle()
    var showEndDialog by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ParticipantInfo?>(null) }
    var rotating by rememberSaveable { mutableStateOf(false) }
    var openMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    // Ein Tippen auf „Beenden“ in der Benachrichtigung öffnet nur die Bestätigung, nie das Ende selbst.
    LaunchedEffect(confirmEnd) {
        if (confirmEnd) showEndDialog = true
    }
    // Die Medienansicht folgt dem Space, der gerade angezeigt wird (auch ohne Kennung aus der Benachrichtigung).
    val spaceId = state.record?.id
    LaunchedEffect(spaceId) {
        spaceId?.let(mediaViewModel::show)
    }
    val pickMedia =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            val space = spaceId ?: return@rememberLauncherForActivityResult
            uploadViewModel.enqueue(space, uris.map { it.toString() })
        }
    val canUpload = state.record?.status == SpaceStatus.ACTIVE && state.runState is RunState.Running
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
        SpaceRunningContent(
            state = state,
            onEnd = { showEndDialog = true },
            onConfirmNetwork = viewModel::confirmNetwork,
            onAcknowledgePause = viewModel::acknowledgePauses,
            onResume = viewModel::resume,
            onSetup = {
                state.record
                    ?.id
                    ?.value
                    ?.let(onSetup)
            },
            onRemoveRequest = { removing = it },
            onRotateRequest = { rotating = true },
            onAcknowledgeInvite = viewModel::acknowledgeInvite,
            upload = upload,
            media = media,
            onPickMedia =
                if (canUpload) {
                    { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                } else {
                    null
                },
            onUploadCancel = uploadViewModel::cancel,
            onMediaFilter = mediaViewModel::setFilter,
            onMediaOpen = { openMediaId = it.id },
            modifier = Modifier.padding(padding),
        )
    }
    upload.locationQuestion?.let { name ->
        LocationQuestionDialog(
            fileName = name,
            onKeep = { uploadViewModel.answerLocation(true) },
            onSkip = { uploadViewModel.answerLocation(false) },
        )
    }
    openMediaId?.let { mediaId ->
        media.items.firstOrNull { it.id == mediaId }?.let { item ->
            HostMediaDetail(
                item = item,
                loadDetail = { mediaViewModel.detailOf(mediaId) },
                // Entfernen gilt bis zum Ende, auch in einer Pause. Nach dem Ende bleibt die Ansicht nur lesbar (ADR 0019).
                editable = state.record?.status == SpaceStatus.ACTIVE,
                onDelete = {
                    mediaViewModel.delete(mediaId)
                    openMediaId = null
                },
                onDismiss = { openMediaId = null },
            )
        }
    }
    removing?.let { target ->
        RemoveParticipantDialog(
            participant = target,
            onConfirm = { deleteUploads ->
                viewModel.removeGuest(target, deleteUploads)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
    if (rotating) {
        RotateInviteDialog(
            onConfirm = {
                viewModel.rotateInvite()
                rotating = false
            },
            onDismiss = { rotating = false },
        )
    }
    if (showEndDialog) {
        AlertDialog(
            onDismissRequest = { showEndDialog = false },
            title = { Text(stringResource(R.string.space_running_end_title)) },
            text = { EndDialogText() },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndDialog = false
                        viewModel.end()
                    },
                ) { Text(stringResource(R.string.space_running_end_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDialog = false }) { Text(stringResource(R.string.space_cancel)) }
            },
        )
    }
}

/**
 * Inhalt des laufenden Space ohne Zustand. Die Goldens rufen diesen Teil direkt auf. [upload] und [media] sind die Zustände
 * des Gastgeber-Uploads und der Medienansicht (Paket 3.5c). [onPickMedia] ist `null`, wenn gerade nicht hochgeladen werden
 * darf. Dann fehlt der Knopf.
 */
@Composable
internal fun SpaceRunningContent(
    state: SpaceRunningUiState,
    onEnd: () -> Unit,
    onConfirmNetwork: () -> Unit,
    onAcknowledgePause: () -> Unit,
    onSetup: () -> Unit,
    onResume: () -> Unit,
    onRemoveRequest: (ParticipantInfo) -> Unit,
    onRotateRequest: () -> Unit,
    onAcknowledgeInvite: () -> Unit,
    modifier: Modifier = Modifier,
    upload: HostUploadUiState = HostUploadUiState(),
    media: HostMediaUiState = HostMediaUiState(),
    onPickMedia: (() -> Unit)? = null,
    onUploadCancel: () -> Unit = {},
    onMediaFilter: (MediaFilter) -> Unit = {},
    onMediaOpen: (HostMediaItem) -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val record = state.record
        if (!state.loaded) return@Surface
        if (record == null) {
            Text(
                text = stringResource(R.string.space_running_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Surface
        }
        val running = record.status == SpaceStatus.ACTIVE
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                text = record.settings.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = statusLine(record, state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (state.startQueued && state.runState !is RunState.Running) {
                QueuedStartCard(seconds = SpaceServerService.closeGraceSeconds)
                Spacer(Modifier.height(16.dp))
            }

            if (state.interrupted) {
                InterruptedCard(failure = state.resumeFailure, onResume = onResume)
                Spacer(Modifier.height(16.dp))
            }

            state.pause?.let { pause ->
                PauseBanner(pause = pause, onSetup = onSetup, onAcknowledge = onAcknowledgePause)
                Spacer(Modifier.height(16.dp))
            }

            val paused = state.runState as? RunState.Paused
            if (paused != null && paused.cause == PauseCause.NETWORK_CHANGED && paused.pendingAddress != null) {
                NetworkChangedCard(onConfirm = onConfirmNetwork)
                Spacer(Modifier.height(16.dp))
            } else if (paused != null && paused.cause == PauseCause.NETWORK_LOST) {
                Text(
                    text = stringResource(R.string.space_running_network_lost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            val address = (state.runState as? RunState.Running)
            if (address != null) {
                val link = state.invite
                if (link != null) {
                    InviteSection(
                        link = link,
                        addressChanged = state.addressChanged,
                        onRotate = onRotateRequest,
                        inviteRenewed = state.inviteRenewed,
                        onAcknowledgeInvite = onAcknowledgeInvite,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.space_running_address_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "http://${address.address.ipv4}:${address.port}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(24.dp))
                ParticipantsSection(participants = state.participants, onRemove = onRemoveRequest)
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.space_running_storage_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    stringResource(
                        R.string.space_running_storage_value,
                        stringResource(R.string.space_storage_gib, formatGib(state.usedBytes)),
                        stringResource(R.string.space_storage_gib, formatGib(record.settings.storageLimitBytes)),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.usedPercent / PERCENT_SCALE },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.storageWarning) {
                Text(
                    text = stringResource(R.string.space_running_storage_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.space_running_media_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            if (onPickMedia != null) {
                HostUploadAddRow(onAdd = onPickMedia)
                Spacer(Modifier.height(8.dp))
            }
            if (upload.items.isNotEmpty()) {
                HostUploadSection(state = upload, onCancel = onUploadCancel)
                Spacer(Modifier.height(12.dp))
            }
            HostMediaSection(state = media, onFilter = onMediaFilter, onOpen = onMediaOpen)
            Spacer(Modifier.height(24.dp))

            if (running) {
                // Während der Start im Nachlauf wartet, gibt es nichts zu beenden: der Dienst hat den Lauf noch nicht.
                OutlinedButton(
                    onClick = onEnd,
                    enabled = !state.startQueued,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.space_running_end))
                }
            } else if (record.status == SpaceStatus.ENDED) {
                Text(
                    text = stringResource(R.string.space_running_ended_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PauseBanner(
    pause: PauseEvent,
    onSetup: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.space_running_pause_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.space_running_pause_text, minutesOf(pause.durationMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.space_running_pause_setup)) }
                Spacer(Modifier.height(0.dp))
                TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.space_running_pause_ack)) }
            }
        }
    }
}

/**
 * Hinweis, solange der Start im Nachlauf des vorigen Space wartet (ADR 0017, Nachtrag 21.10.2026). Verschwindet, sobald der
 * Server läuft. Die Ankündigung ist höflich (Polite), damit Vorlesegeräte sie einmal ansagen.
 */
@Composable
private fun QueuedStartCard(seconds: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = pluralStringResource(R.plurals.space_running_start_queued, seconds, seconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Hinweis, wenn der Lauf unterbrochen ist (ADR 0017). „Fortsetzen“ startet den Dienst aus der sichtbaren Oberfläche neu.
 * Scheitert das, bleibt der Space aktiv, und der Grund steht hier.
 */
@Composable
private fun InterruptedCard(
    failure: StartOutcome?,
    onResume: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.space_interrupted_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.space_interrupted_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            failureTextRes(failure)?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onResume) { Text(stringResource(R.string.space_interrupted_resume)) }
        }
    }
}

/** Text zum Scheitern des Fortsetzens. Die Texte stehen in `strings.xml`, dieselben wie beim Start. */
private fun failureTextRes(failure: StartOutcome?): Int? =
    when (failure) {
        StartOutcome.NoWifi -> R.string.space_setup_error_no_wifi
        StartOutcome.ServiceRefused -> R.string.space_setup_error_service
        else -> null
    }

@Composable
private fun NetworkChangedCard(onConfirm: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.space_running_network_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.space_running_network_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConfirm) { Text(stringResource(R.string.space_running_network_continue)) }
        }
    }
}

@Composable
private fun statusLine(
    record: SpaceRecord,
    state: SpaceRunningUiState,
): String =
    when {
        record.status == SpaceStatus.ENDED -> {
            stringResource(R.string.space_running_status_ended, formatDate(record.endedAt ?: record.updatedAt))
        }

        state.interrupted -> {
            stringResource(R.string.space_running_status_interrupted)
        }

        state.startQueued && state.runState !is RunState.Running -> {
            stringResource(R.string.space_running_status_queued)
        }

        state.runState is RunState.Paused -> {
            stringResource(R.string.space_running_status_paused)
        }

        else -> {
            val expires = record.expiresAt
            if (expires != null) {
                stringResource(R.string.space_running_status_running, remainingText(expires - state.nowMillis))
            } else {
                stringResource(R.string.space_detail_summary)
            }
        }
    }

/**
 * Text des Beenden-Dialogs (Paket 3.5a-3): Folgen für Gäste, dazu was Gäste ohne offene Seite sehen. Eigene Funktion,
 * damit die Goldens dieselben Zeilen zeigen wie die App.
 */
@Composable
internal fun EndDialogText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.space_running_end_text))
        Text(stringResource(R.string.space_running_end_notice))
    }
}

@Composable
private fun remainingText(millis: Long): String {
    val minutes = millis / MILLIS_PER_MINUTE
    return when {
        millis < MILLIS_PER_MINUTE -> {
            stringResource(R.string.space_running_remaining_under_minute)
        }

        minutes < MINUTES_PER_HOUR -> {
            stringResource(R.string.space_running_remaining_m, minutes)
        }

        else -> {
            stringResource(
                R.string.space_running_remaining_hm,
                minutes / MINUTES_PER_HOUR,
                minutes % MINUTES_PER_HOUR,
            )
        }
    }
}

private fun minutesOf(millis: Long): Long = (millis + MILLIS_PER_MINUTE / 2) / MILLIS_PER_MINUTE

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val PERCENT_SCALE = 100f
