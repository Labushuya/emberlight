package io.github.labushuya.emberlight.core.media.store

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.di.IoDispatcher
import io.github.labushuya.emberlight.core.media.index.MediaIndex
import io.github.labushuya.emberlight.core.media.log.MediaLogger
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.core.media.permission.MediaPermissionChecker
import io.github.labushuya.emberlight.core.media.permission.mediaAccess
import io.github.labushuya.emberlight.core.media.permission.missingMediaTypes
import io.github.labushuya.emberlight.core.media.permission.requiredPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore-Implementierung von [MediaRepository] (ADR 0007).
 *
 * Eine einzige Sammel-Coroutine verarbeitet alle Auslöser nacheinander: den Start, Änderungen aus dem
 * ContentObserver (entprellt) und Aufrufe von [refresh]. Abfragen laufen so nie parallel und überholen sich nicht.
 *
 * `debounce` trägt die Markierung `@FlowPreview`. Die Nutzung ist bewusst, weil die Entprellung genau hier gebraucht wird.
 */
@OptIn(FlowPreview::class)
@Singleton
internal class MediaStoreMediaRepository
    @Inject
    constructor(
        private val gateway: MediaStoreGateway,
        private val permissions: MediaPermissionChecker,
        private val logger: MediaLogger,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : MediaRepository {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
        private val state = MutableStateFlow<MediaLibraryState>(MediaLibraryState.Loading)

        /** Generation der letzten erfolgreichen Abfrage. Nur von der Sammel-Coroutine gelesen und geschrieben. */
        private var lastGeneration: Long? = null

        override val library: StateFlow<MediaLibraryState> = state.asStateFlow()

        init {
            scope.launch {
                merge(
                    // Start: immer eine vollständige Abfrage.
                    flowOf(true),
                    // Änderungen: nur bei neuer Generation, nach einer Ruhephase von 400 ms.
                    gateway
                        .changes()
                        .catch { e -> logger.warn("MediaStore observer failed, changes are no longer reported", e) }
                        .debounce(CHANGE_DEBOUNCE_MS)
                        .map { false },
                    // refresh(): immer neu abfragen.
                    refreshRequests.receiveAsFlow().map { true },
                ).collect { force -> reload(force) }
            }
        }

        override fun refresh() {
            // Die Kanal-Kapazität ist CONFLATED, daher schlägt trySend nicht fehl.
            refreshRequests.trySend(Unit)
        }

        private suspend fun reload(force: Boolean) {
            withContext(ioDispatcher) {
                try {
                    val sdk = gateway.sdkInt
                    val granted = requiredPermissions(sdk).filterTo(HashSet()) { permissions.isGranted(it) }
                    val access = mediaAccess(sdk, granted)
                    val missing = missingMediaTypes(sdk, granted)
                    // Generation vor der Abfrage lesen. Ändert sich während der Abfrage etwas, folgt eine weitere Runde.
                    val generation = gateway.generation()
                    if (!force && generation != null && generation == lastGeneration) return@withContext
                    state.value = load(access, missing, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unerwarteter Fehler: den letzten guten Zustand behalten und protokollieren. Die Sammel-Coroutine
                    // läuft weiter, damit spätere Änderungen trotzdem ankommen.
                    logger.warn("MediaStore refresh failed, keeping the last library state", e)
                }
            }
        }

        private fun load(
            access: MediaAccess,
            missing: Set<MediaType>,
            generation: Long?,
        ): MediaLibraryState {
            if (access == MediaAccess.NONE) {
                lastGeneration = null
                return MediaLibraryState.Ready(emptyLibrary(MediaAccess.NONE))
            }
            return try {
                // Fehlt die Berechtigung für einen Typ, wird er nicht abgefragt. Sonst würde MediaStore die ganze Abfrage
                // mit einer SecurityException abbrechen, und die Bibliothek fiele auf NONE zurück.
                val library =
                    MediaIndex.build(
                        images = if (MediaType.IMAGE in missing) emptyList() else gateway.query(MediaType.IMAGE),
                        videos = if (MediaType.VIDEO in missing) emptyList() else gateway.query(MediaType.VIDEO),
                        access = access,
                        missingTypes = missing,
                    )
                lastGeneration = generation
                MediaLibraryState.Ready(library)
            } catch (e: SecurityException) {
                // Berechtigung fehlt, obwohl die Prüfung sie gemeldet hat (z. B. Entzug mitten im Lauf): leere Liste ohne Zugriff.
                lastGeneration = null
                MediaLibraryState.Ready(emptyLibrary(MediaAccess.NONE))
            }
        }

        private fun emptyLibrary(access: MediaAccess): MediaLibrary =
            MediaLibrary(items = emptyList(), albums = emptyList(), mediaAccess = access)

        private companion object {
            const val CHANGE_DEBOUNCE_MS = 400L
        }
    }
