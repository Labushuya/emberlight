package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bildet den Index auf den UI-Zustand ab. Reine Funktion ohne Android und ohne Uhr: Heute und Zone kommen als Parameter.
 *
 * - Kein Zugriff ([MediaAccess.NONE]): Berechtigungsanfrage. Der Dialog erscheint nur, wenn er noch kann: noch nie
 *   gefragt, oder die Systemerklärung ist anzeigbar ([showRationale]). Sonst helfen nur noch die Einstellungen.
 * - Zugriff, aber keine Einträge: Leerzustand.
 * - Sonst: die Zeitleiste der Stufe [zoomLevel], gruppiert über [grouper].
 */
internal fun timelineUiState(
    library: MediaLibraryState,
    askedBefore: Boolean,
    showRationale: Boolean,
    zoomLevel: Int,
    today: LocalDate,
    zone: ZoneId,
    grouper: TimelineGrouper = TimelineGrouper.Direct,
): TimelineUiState =
    when (library) {
        MediaLibraryState.Loading -> {
            TimelineUiState.Loading
        }

        is MediaLibraryState.Ready -> {
            val index = library.library
            val level = ZoomLevels.clamp(zoomLevel)
            when {
                index.mediaAccess == MediaAccess.NONE -> {
                    TimelineUiState.PermissionRequired(canRequest = !askedBefore || showRationale)
                }

                index.items.isEmpty() -> {
                    TimelineUiState.Empty(access = index.mediaAccess, missingTypes = index.missingTypes)
                }

                else -> {
                    TimelineUiState.Content(
                        sections = grouper.group(index.items, ZoomLevels.groupingFor(level), today, zone),
                        access = index.mediaAccess,
                        missingTypes = index.missingTypes,
                        zoomLevel = level,
                        today = today,
                    )
                }
            }
        }
    }
