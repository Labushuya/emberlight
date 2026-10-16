package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fit-Rechteck des Bildes im Bereich der Seite: dieselbe Fläche, die das Vollbild zeigt (Grundlage des gemeinsamen Elements). */
class ViewerPreviewFitTest {
    @Test
    fun aLandscapeImageFillsTheWidthOfAPortraitPage() {
        // Seite 400 × 800, Bild 4:3 (Breite durch Höhe 1,333): volle Breite, Höhe 300.
        val (width, height) = requireNotNull(fittedSize(400f, 800f, 4f / 3f))
        assertEquals(400f, width, 1e-3f)
        assertEquals(300f, height, 1e-3f)
    }

    @Test
    fun aPortraitImageFillsTheHeightOfALandscapePage() {
        // Seite 800 × 400, Bild 3:4: volle Höhe 400, Breite 300.
        val (width, height) = requireNotNull(fittedSize(800f, 400f, 3f / 4f))
        assertEquals(300f, width, 1e-3f)
        assertEquals(400f, height, 1e-3f)
    }

    @Test
    fun anImageWithTheSameAspectFillsThePage() {
        val (width, height) = requireNotNull(fittedSize(360f, 720f, 0.5f))
        assertEquals(360f, width, 1e-3f)
        assertEquals(720f, height, 1e-3f)
    }

    @Test
    fun missingOrInvalidSizesGiveNoFit() {
        assertNull(fittedSize(0f, 100f, 1f))
        assertNull(fittedSize(100f, 0f, 1f))
        assertNull(fittedSize(100f, 100f, 0f))
        assertNull(fittedSize(100f, 100f, Float.NaN))
        assertNull(fittedSize(100f, 100f, Float.POSITIVE_INFINITY))
    }
}
