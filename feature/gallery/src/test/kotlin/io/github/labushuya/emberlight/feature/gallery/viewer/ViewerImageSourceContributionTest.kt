package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertSame
import org.junit.Test

/** Auswahl der Bildquelle: der erste Beitrag mit einer Quelle gewinnt, sonst gilt die Standardquelle (ADR 0011). */
class ViewerImageSourceContributionTest {
    private val item =
        ViewerItem(
            id = 1L,
            uri = "content://media/external/images/media/1",
            type = ViewerMediaType.IMAGE,
            timestamp = 0L,
            width = 10,
            height = 10,
            durationMs = null,
            previewKey = null,
        )

    @Test
    fun withoutContributionsTheContentUriSourceApplies() {
        assertSame(ContentUriImageSources, emptyList<ViewerImageSourceContribution>().imageSourceFor(item))
    }

    @Test
    fun aContributionThatDeclinesLeavesTheDefault() {
        val declining = contribution { null }
        assertSame(ContentUriImageSources, listOf(declining).imageSourceFor(item))
    }

    @Test
    fun theFirstContributionWithASourceWins() {
        val first = contribution { solidImageSources }
        val second = contribution { ContentUriImageSources }
        assertSame(solidImageSources, listOf(first, second).imageSourceFor(item))
    }

    private fun contribution(pick: () -> ViewerImageSource?): ViewerImageSourceContribution =
        object : ViewerImageSourceContribution {
            override fun sourceFor(item: ViewerItem): ViewerImageSource? = pick()
        }
}
