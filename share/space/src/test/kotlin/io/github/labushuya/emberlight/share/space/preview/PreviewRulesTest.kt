package io.github.labushuya.emberlight.share.space.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Regeln der Vorschauen: Größen, Subsampling, Pixelgrenze, Videobild, Codec-Namen (ADR 0019). */
class PreviewRulesTest {
    @Test
    fun theLongEdgeBecomesTheTargetAndTheAspectRatioStays() {
        assertEquals(320 to 240, PreviewRules.targetSize(4000, 3000, 320))
        assertEquals(80 to 320, PreviewRules.targetSize(100, 400, 320))
        assertEquals(320 to 320, PreviewRules.targetSize(640, 640, 320))
    }

    @Test
    fun imagesSmallerThanTheTargetAreNotEnlarged() {
        assertEquals(200 to 100, PreviewRules.targetSize(200, 100, 320))
        assertEquals(320 to 320, PreviewRules.targetSize(320, 320, 320))
    }

    @Test
    fun aVerySkinnyImageKeepsAtLeastOnePixelOnTheShortEdge() {
        assertEquals(320 to 1, PreviewRules.targetSize(10_000, 2, 320))
    }

    @Test
    fun zeroOrNegativeSizesAreRefused() {
        val zero = runCatching { PreviewRules.targetSize(0, 100, 320) }
        assertTrue(zero.isFailure)
    }

    @Test
    fun theSampleSizeIsTheLargestPowerOfTwoThatKeepsTheLongEdgeAboveTheTarget() {
        assertEquals(8, PreviewRules.sampleSize(4000, 3000, 320))
        assertEquals(2, PreviewRules.sampleSize(640, 480, 320))
        assertEquals(1, PreviewRules.sampleSize(320, 200, 320))
        assertEquals(1, PreviewRules.sampleSize(100, 50, 1600))
    }

    @Test
    fun aHundredMegapixelImageIsStillAllowedAndOneMoreIsRefused() {
        assertFalse(PreviewRules.exceedsPixelLimit(10_000, 10_000))
        assertTrue(PreviewRules.exceedsPixelLimit(10_001, 10_000))
        assertTrue(PreviewRules.exceedsPixelLimit(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun theVideoFrameIsOneSecondForLongVideosAndTenPercentForShortOnes() {
        assertEquals(0L, PreviewRules.seekMicros(null))
        assertEquals(0L, PreviewRules.seekMicros(0))
        assertEquals(500_000L, PreviewRules.seekMicros(5_000))
        assertEquals(1_000_000L, PreviewRules.seekMicros(10_000))
        assertEquals(1_000_000L, PreviewRules.seekMicros(120_000))
    }

    @Test
    fun knownVideoMimesMapToTheFourCharacterCodecs() {
        assertEquals("avc1", PreviewRules.codecOf("video/avc"))
        assertEquals("hvc1", PreviewRules.codecOf("video/hevc"))
        assertEquals("vp09", PreviewRules.codecOf("video/x-vnd.on2.vp9"))
        assertEquals("av01", PreviewRules.codecOf("video/av01"))
        assertNull(PreviewRules.codecOf("video/unknown"))
        assertNull(PreviewRules.codecOf("audio/mp4a-latm"))
    }

    @Test
    fun onlyHeicAndHeifGetTheViewAndOnlyVideosUseTheFrameRoute() {
        assertTrue(PreviewRules.hasHeicView("image/heic"))
        assertTrue(PreviewRules.hasHeicView("image/heif"))
        assertFalse(PreviewRules.hasHeicView("image/avif"))
        assertFalse(PreviewRules.hasHeicView("image/jpeg"))
        assertTrue(PreviewRules.isVideo("video/mp4"))
        assertFalse(PreviewRules.isVideo("image/png"))
    }

    @Test
    fun theWireValueIsLowerCaseAndNullStaysNull() {
        assertEquals("ready", PreviewState.wire(PreviewState.READY))
        assertEquals("pending", PreviewState.wire(PreviewState.PENDING))
        assertEquals("failed", PreviewState.wire(PreviewState.FAILED))
        assertNull(PreviewState.wire(null))
    }
}
