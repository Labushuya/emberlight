package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class TimelineIndexTest {
    private val items = TimelineFixtures.sampleItems()
    private val days = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE)

    @Test
    fun countsHeadersMediaAndBanner() {
        // Heute, Gestern, 12. Oktober, Juli, Juni 2025: 5 Überschriften und 8 Medien.
        assertEquals(13, TimelineIndex(days, leadingItems = 0, today = TODAY).totalItems)
        assertEquals(14, TimelineIndex(days, leadingItems = 1, today = TODAY).totalItems)
    }

    @Test
    fun bannerHasNoSection() {
        val index = TimelineIndex(days, leadingItems = 1, today = TODAY)

        assertNull(index.sectionAt(0))
        assertEquals(TimelineHeader.Today, index.sectionAt(1)?.header)
    }

    @Test
    fun everyPositionBelongsToTheRightSection() {
        val index = TimelineIndex(days, leadingItems = 0, today = TODAY)

        assertEquals(TimelineHeader.Today, index.sectionAt(0)?.header)
        assertEquals(TimelineHeader.Today, index.sectionAt(2)?.header)
        assertEquals(TimelineHeader.Yesterday, index.sectionAt(3)?.header)
        assertEquals(TimelineHeader.Yesterday, index.sectionAt(5)?.header)
        assertEquals("day:2025-06-03", index.sectionAt(12)?.key)
    }

    @Test
    fun outsideTheGridThereIsNoSection() {
        val index = TimelineIndex(days, leadingItems = 0, today = TODAY)

        assertNull(index.sectionAt(-1))
        assertNull(index.sectionAt(index.totalItems))
        assertNull(TimelineIndex(emptyList(), leadingItems = 0, today = TODAY).sectionAt(0))
    }

    @Test
    fun mediaPositionsFollowTheirHeaders() {
        val index = TimelineIndex(days, leadingItems = 1, today = TODAY)
        val uri = items[4].uri // 12. Oktober, erster Eintrag der dritten Gruppe

        val position = index.indexOfMedia(uri)!!

        assertEquals(
            TimelineHeader.Day(LocalDate.of(2026, 10, 12), withYear = false),
            index.sectionAt(position)!!.header,
        )
        assertNull(index.indexOfMedia("content://media/external/image/media/999"))
    }

    @Test
    fun dateAtGivesTheDateOfTheSectionAtAPosition() {
        val index = TimelineIndex(days, leadingItems = 0, today = TODAY)

        assertEquals(TODAY, index.dateAt(0))
        assertEquals(LocalDate.of(2026, 10, 15), index.dateAt(3))
        assertEquals(LocalDate.of(2025, 6, 3), index.dateAt(11))
        assertNull(index.dateAt(-1))
    }

    @Test
    fun representativeDatesForEveryHeaderKind() {
        assertEquals(TODAY, TimelineHeader.Today.representativeDate(TODAY))
        assertEquals(LocalDate.of(2026, 10, 15), TimelineHeader.Yesterday.representativeDate(TODAY))
        assertEquals(
            LocalDate.of(2026, 7, 2),
            TimelineHeader.Day(LocalDate.of(2026, 7, 2), false).representativeDate(TODAY),
        )
        assertEquals(LocalDate.of(2025, 6, 1), TimelineHeader.Month(YearMonth.of(2025, 6)).representativeDate(TODAY))
    }

    @Test
    fun yesterdayStaysCorrectAcrossNewYear() {
        val newYear = LocalDate.of(2027, 1, 1)

        assertEquals(LocalDate.of(2026, 12, 31), TimelineHeader.Yesterday.representativeDate(newYear))
    }
}
