package io.github.labushuya.emberlight.core.media.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Berechtigungslogik ohne Gerät. Die SDK-Level 32, 33, 34 und 37 decken alle Regelbereiche ab: vor Android 13,
 * Android 13, Android 14 (partieller Zugriff) und ein aktuelles Level.
 */
class MediaPermissionsTest {
    private val readExternal = "android.permission.READ_EXTERNAL_STORAGE"
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val video = "android.permission.READ_MEDIA_VIDEO"
    private val visual = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    private val relevant = listOf(readExternal, images, video, visual)

    @Test
    fun requiredPermissionsBeforeAndroid13() {
        listOf(26, 29, 32).forEach { sdk ->
            assertEquals("sdk $sdk", listOf(readExternal), requiredPermissions(sdk))
        }
    }

    @Test
    fun requiredPermissionsOnAndroid13() {
        assertEquals(listOf(images, video), requiredPermissions(33))
    }

    @Test
    fun requiredPermissionsFromAndroid14IncludePartialAccess() {
        assertEquals(listOf(images, video, visual), requiredPermissions(34))
        assertEquals(listOf(images, video, visual), requiredPermissions(37))
    }

    @Test
    fun mediaAccessForEveryCombinationOfRelevantPermissions() {
        for (sdk in listOf(32, 33, 34, 37)) {
            for (granted in subsets(relevant)) {
                val expected = expectedAccess(sdk, granted)
                assertEquals("sdk $sdk, granted $granted", expected, mediaAccess(sdk, granted.toSet()))
            }
        }
    }

    @Test
    fun partialAccessOnlyFromAndroid14() {
        assertEquals(MediaAccess.PARTIAL, mediaAccess(34, setOf(visual)))
        assertEquals(MediaAccess.PARTIAL, mediaAccess(37, setOf(visual)))
        // Auf API 33 gibt es die Berechtigung nicht. Gewährt ist sie dort wirkungslos.
        assertEquals(MediaAccess.NONE, mediaAccess(33, setOf(visual)))
    }

    @Test
    fun fullAccessWhenImagesOrVideoGrantedOnAndroid14Plus() {
        assertEquals(MediaAccess.FULL, mediaAccess(34, setOf(images, video)))
        assertEquals(MediaAccess.FULL, mediaAccess(37, setOf(images, visual)))
    }

    @Test
    fun oneOfImagesOrVideoCountsAsFullOnAndroid13Plus() {
        // Die App fordert beide Typen an. Gewährt der Nutzer nur einen, gilt das trotzdem als FULL (siehe KDoc).
        assertEquals(MediaAccess.FULL, mediaAccess(33, setOf(images)))
        assertEquals(MediaAccess.FULL, mediaAccess(33, setOf(video)))
    }

    @Test
    fun legacyStorageOnlyCountsUpToAndroid12() {
        assertEquals(MediaAccess.FULL, mediaAccess(32, setOf(readExternal)))
        assertEquals(MediaAccess.FULL, mediaAccess(26, setOf(readExternal)))
        // Ab Android 13 hat READ_EXTERNAL_STORAGE keine Wirkung mehr.
        assertEquals(MediaAccess.NONE, mediaAccess(33, setOf(readExternal)))
        assertEquals(MediaAccess.NONE, mediaAccess(37, setOf(readExternal)))
    }

    @Test
    fun noPermissionMeansNone() {
        for (sdk in listOf(26, 32, 33, 34, 37)) {
            assertEquals("sdk $sdk", MediaAccess.NONE, mediaAccess(sdk, emptySet()))
        }
    }

    /** Erwartung, unabhängig von der Implementierung formuliert, Regel für Regel. */
    private fun expectedAccess(
        sdk: Int,
        granted: List<String>,
    ): MediaAccess =
        when {
            sdk <= 32 -> if (readExternal in granted) MediaAccess.FULL else MediaAccess.NONE

            sdk == 33 -> if (images in granted || video in granted) MediaAccess.FULL else MediaAccess.NONE

            // API 34 und höher
            images in granted || video in granted -> MediaAccess.FULL

            visual in granted -> MediaAccess.PARTIAL

            else -> MediaAccess.NONE
        }

    private fun subsets(values: List<String>): List<List<String>> =
        (0 until (1 shl values.size)).map { mask ->
            values.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
        }
}
