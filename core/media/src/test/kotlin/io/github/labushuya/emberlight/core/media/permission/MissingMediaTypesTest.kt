package io.github.labushuya.emberlight.core.media.permission

import io.github.labushuya.emberlight.core.media.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fehlende Medientypen ab Android 13 (ADR 0007). Ohne Gerät, nur über die Berechtigungsnamen. */
class MissingMediaTypesTest {
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val video = "android.permission.READ_MEDIA_VIDEO"
    private val visual = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val readExternal = "android.permission.READ_EXTERNAL_STORAGE"

    @Test
    fun nothingMissingWhenBothTypesGranted() {
        assertEquals(emptySet<MediaType>(), missingMediaTypes(33, setOf(images, video)))
        assertEquals(emptySet<MediaType>(), missingMediaTypes(37, setOf(images, video, visual)))
    }

    @Test
    fun onlyImagesGrantedMissesVideo() {
        assertEquals(setOf(MediaType.VIDEO), missingMediaTypes(33, setOf(images)))
        assertEquals(setOf(MediaType.VIDEO), missingMediaTypes(37, setOf(images, visual)))
    }

    @Test
    fun onlyVideoGrantedMissesImages() {
        assertEquals(setOf(MediaType.IMAGE), missingMediaTypes(34, setOf(video)))
    }

    @Test
    fun partialAccessMissesNothing() {
        // Die Auswahl deckt beide Typen ab. Es fehlt also nichts, auch wenn keiner der Typen einzeln gewährt ist.
        assertEquals(emptySet<MediaType>(), missingMediaTypes(34, setOf(visual)))
    }

    @Test
    fun noAccessMissesNothing() {
        // Ohne jeden Zugriff ist die Liste leer. Die UI zeigt dann die Berechtigungsanfrage, nicht den Hinweis.
        assertEquals(emptySet<MediaType>(), missingMediaTypes(37, emptySet()))
        assertEquals(emptySet<MediaType>(), missingMediaTypes(33, setOf(readExternal)))
    }

    @Test
    fun beforeAndroid13NothingMissesEvenWithLegacyPermission() {
        assertEquals(emptySet<MediaType>(), missingMediaTypes(32, setOf(readExternal)))
        assertEquals(emptySet<MediaType>(), missingMediaTypes(26, setOf(readExternal)))
    }
}
