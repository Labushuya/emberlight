package io.github.labushuya.emberlight.core.media.thumbnail

import coil3.size.Dimension
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaThumbnailFetcherTest {
    @Test
    fun edgeIsTheLargerTargetDimension() {
        assertEquals(200, thumbnailEdgePx(Size(Dimension(100), Dimension(200))))
        assertEquals(300, thumbnailEdgePx(Size(Dimension(300), Dimension(120))))
    }

    @Test
    fun unknownTargetFallsBackToFixedEdge() {
        assertEquals(FALLBACK_THUMBNAIL_EDGE_PX, thumbnailEdgePx(Size.ORIGINAL))
    }

    @Test
    fun oneUndefinedDimensionUsesTheOther() {
        assertEquals(144, thumbnailEdgePx(Size(Dimension.Undefined, Dimension(144))))
    }
}
