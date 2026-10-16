package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import java.time.LocalDate
import java.time.ZoneId

/** Geltungsbereich eines Rasters: alle Einträge der Galerie oder nur die Einträge eines Albums (ADR 0009). */
internal sealed interface MediaScope {
    fun contains(item: MediaItem): Boolean

    /** Alle Einträge der Galerie. */
    data object All : MediaScope {
        override fun contains(item: MediaItem): Boolean = true
    }

    /**
     * Einträge eines Ordners. [bucketId] `null` meint die Einträge ohne Ordner, also das Album „Sonstige“.
     */
    @Immutable
    data class Bucket(
        val bucketId: Long?,
    ) : MediaScope {
        override fun contains(item: MediaItem): Boolean = item.bucketId == bucketId
    }
}

/**
 * Gefilterte Einträge für einen Geltungsbereich. Merkt sich das letzte Ergebnis je Quellliste. Dadurch bleibt die Liste
 * für denselben Index dieselbe Instanz, und der Gruppierungs-Cache greift auch beim Wechsel der Zoomstufe.
 */
internal class ScopedItems(
    private val scope: MediaScope,
) {
    private var source: List<MediaItem>? = null
    private var selected: List<MediaItem> = emptyList()

    @Synchronized
    fun of(items: List<MediaItem>): List<MediaItem> {
        if (scope == MediaScope.All) return items
        if (items !== source) {
            source = items
            selected = items.filter(scope::contains)
        }
        return selected
    }
}

/**
 * Bildet den Index auf den Zustand eines Rasters ab. Reine Funktion ohne Android und ohne Uhr: Heute und Zone kommen als
 * Parameter. Galerie und Album nutzen dieselbe Abbildung, sie unterscheiden sich nur im [scope].
 *
 * - Kein Zugriff ([MediaAccess.NONE]): Berechtigungsanfrage. Der Dialog erscheint nur, wenn er noch kann: noch nie
 *   gefragt, oder die Systemerklärung ist anzeigbar ([showRationale]). Sonst helfen nur noch die Einstellungen.
 * - Keine Einträge im Geltungsbereich: Leerzustand der Galerie, für ein Album [MediaGridState.Gone].
 * - Sonst: das Raster der Stufe [zoomLevel], gruppiert über [grouper].
 */
internal fun mediaGridState(
    library: MediaLibraryState,
    scope: MediaScope,
    askedBefore: Boolean,
    showRationale: Boolean,
    zoomLevel: Int,
    today: LocalDate,
    zone: ZoneId,
    grouper: TimelineGrouper = TimelineGrouper.Direct,
    scopedItems: ScopedItems = ScopedItems(scope),
): MediaGridState =
    when (library) {
        MediaLibraryState.Loading -> {
            MediaGridState.Loading
        }

        is MediaLibraryState.Ready -> {
            val index = library.library
            val level = ZoomLevels.clamp(zoomLevel)
            val items = scopedItems.of(index.items)
            when {
                index.mediaAccess == MediaAccess.NONE -> {
                    MediaGridState.PermissionRequired(canRequest = !askedBefore || showRationale)
                }

                items.isEmpty() && scope == MediaScope.All -> {
                    MediaGridState.Empty(access = index.mediaAccess, missingTypes = index.missingTypes)
                }

                items.isEmpty() -> {
                    MediaGridState.Gone(access = index.mediaAccess, missingTypes = index.missingTypes)
                }

                else -> {
                    MediaGridState.Content(
                        sections = grouper.group(items, ZoomLevels.groupingFor(level), today, zone),
                        access = index.mediaAccess,
                        missingTypes = index.missingTypes,
                        zoomLevel = level,
                        today = today,
                    )
                }
            }
        }
    }
