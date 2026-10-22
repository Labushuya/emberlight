package io.github.labushuya.emberlight.share.space.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class CaptureTimeTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun aValidExifTimeIsReadInTheGivenZone() {
        val expected = LocalDateTime.of(2026, 10, 21, 18, 30, 5).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertEquals(expected, CaptureTime.parseExif("2026:10:21 18:30:05", utc))
        assertEquals(expected, CaptureTime.parseExif("  2026:10:21 18:30:05 ", utc))
    }

    @Test
    fun invalidOrIncompleteExifTimesGiveNull() {
        assertNull(CaptureTime.parseExif("2026:13:01 00:00:00", utc))
        assertNull(CaptureTime.parseExif("2026-10-21 18:30:05", utc))
        assertNull(CaptureTime.parseExif("2026:10:21", utc))
        assertNull(CaptureTime.parseExif("2026:10:21 18:30:05 extra", utc))
        assertNull(CaptureTime.parseExif("", utc))
    }

    @Test
    fun videosAndUnreadableFilesGiveNoCaptureTime() {
        assertNull(CaptureTime.ofFile(File("gibt-es-nicht.mp4"), "video/mp4"))
        assertNull(CaptureTime.ofFile(File("gibt-es-nicht.jpg"), "image/jpeg"))
    }
}
