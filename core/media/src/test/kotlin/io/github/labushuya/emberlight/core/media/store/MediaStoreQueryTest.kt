package io.github.labushuya.emberlight.core.media.store

import io.github.labushuya.emberlight.core.media.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreQueryTest {
    @Test
    fun beforeAndroid10HasNoScopedColumnsAndNoPendingSelection() {
        listOf(26, 28).forEach { sdk ->
            assertNull("sdk $sdk", MediaStoreQuery.selection(sdk))
            val columns = MediaStoreQuery.projection(MediaType.IMAGE, sdk).toList()
            assertFalse("sdk $sdk", columns.contains(MediaColumnNames.RELATIVE_PATH))
            assertFalse("sdk $sdk", columns.contains(MediaColumnNames.VOLUME_NAME))
        }
    }

    @Test
    fun imageProjectionContainsAllFieldsFromAndroid8() {
        val columns = MediaStoreQuery.projection(MediaType.IMAGE, 26).toList()
        listOf(
            MediaColumnNames.ID,
            MediaColumnNames.DISPLAY_NAME,
            MediaColumnNames.MIME_TYPE,
            MediaColumnNames.SIZE,
            MediaColumnNames.WIDTH,
            MediaColumnNames.HEIGHT,
            MediaColumnNames.DATE_TAKEN,
            MediaColumnNames.DATE_MODIFIED,
            MediaColumnNames.DATE_ADDED,
            MediaColumnNames.BUCKET_ID,
            MediaColumnNames.BUCKET_DISPLAY_NAME,
            MediaColumnNames.ORIENTATION,
        ).forEach { assertTrue(it, columns.contains(it)) }
        assertFalse(columns.contains(MediaColumnNames.DURATION))
    }

    @Test
    fun videoProjectionHasDurationAndOrientationOnlyFromAndroid10() {
        val before = MediaStoreQuery.projection(MediaType.VIDEO, 28).toList()
        assertTrue(before.contains(MediaColumnNames.DURATION))
        assertFalse(before.contains(MediaColumnNames.ORIENTATION))

        val after = MediaStoreQuery.projection(MediaType.VIDEO, 37).toList()
        assertTrue(after.contains(MediaColumnNames.DURATION))
        assertTrue(after.contains(MediaColumnNames.ORIENTATION))
        assertTrue(after.contains(MediaColumnNames.RELATIVE_PATH))
        assertTrue(after.contains(MediaColumnNames.VOLUME_NAME))
    }

    @Test
    fun pendingItemsAreExcludedFromAndroid10() {
        assertEquals("is_pending = 0", MediaStoreQuery.selection(29))
        assertEquals("is_pending = 0", MediaStoreQuery.selection(34))
        assertEquals("is_pending = 0", MediaStoreQuery.selection(37))
    }

    @Test
    fun projectionHasNoDuplicateColumns() {
        for (sdk in listOf(26, 29, 37)) {
            for (type in MediaType.entries) {
                val columns = MediaStoreQuery.projection(type, sdk).toList()
                assertEquals("sdk $sdk $type", columns.distinct(), columns)
            }
        }
    }
}
