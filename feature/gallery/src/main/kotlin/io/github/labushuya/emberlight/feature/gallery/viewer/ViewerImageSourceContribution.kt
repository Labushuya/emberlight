@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.compositionLocalOf

/**
 * Erweiterungsstelle der Bildquelle des Vollbild-Viewers (ADR 0011). Ein Beitrag liefert für ein Element eine eigene Quelle.
 * Gibt er `null` zurück, gilt die Standardquelle (Content-URI). Die App sammelt die Beiträge und reicht sie über
 * [LocalViewerImageSources] herein. Im Release-Build gibt es keine Beiträge.
 */
interface ViewerImageSourceContribution {
    /** Quelle für [item] oder `null` für die Standardquelle. */
    fun sourceFor(item: ViewerItem): ViewerImageSource?
}

/** Beiträge zur Bildquelle des Viewers. Ohne Beitrag gilt die Standardquelle. */
val LocalViewerImageSources = compositionLocalOf<List<ViewerImageSourceContribution>> { emptyList() }

/** Die Quelle für [item]: der erste Beitrag, der eine liefert, sonst die Standardquelle. */
internal fun List<ViewerImageSourceContribution>.imageSourceFor(item: ViewerItem): ViewerImageSource =
    firstNotNullOfOrNull { it.sourceFor(item) } ?: ContentUriImageSources
