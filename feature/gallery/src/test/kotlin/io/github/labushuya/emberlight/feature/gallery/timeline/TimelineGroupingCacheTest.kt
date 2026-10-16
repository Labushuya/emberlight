package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineGroupingCacheTest {
    @Test
    fun switchingBetweenModesGroupsEachModeOnce() {
        val counting = CountingGrouper()
        val cache = TimelineGroupingCache(counting)
        val items = TimelineFixtures.sampleItems()

        repeat(3) {
            cache.group(items, GroupingMode.DAY, TODAY, ZONE)
            cache.group(items, GroupingMode.MONTH, TODAY, ZONE)
        }

        assertEquals(listOf(GroupingMode.DAY, GroupingMode.MONTH), counting.modes)
    }

    @Test
    fun cachedResultIsTheSameInstance() {
        val cache = TimelineGroupingCache()
        val items = TimelineFixtures.sampleItems()

        val first = cache.group(items, GroupingMode.DAY, TODAY, ZONE)
        val second = cache.group(items, GroupingMode.DAY, TODAY, ZONE)

        assertSame(first, second)
    }

    @Test
    fun newLibraryListInvalidatesBothModes() {
        val counting = CountingGrouper()
        val cache = TimelineGroupingCache(counting)
        val items = TimelineFixtures.sampleItems()
        cache.group(items, GroupingMode.DAY, TODAY, ZONE)
        cache.group(items, GroupingMode.MONTH, TODAY, ZONE)

        // Gleicher Inhalt, andere Liste: der Index hat neu gelesen.
        cache.group(items.toList(), GroupingMode.DAY, TODAY, ZONE)

        assertEquals(3, counting.modes.size)
    }

    @Test
    fun changedDayOrZoneInvalidatesTheCache() {
        val counting = CountingGrouper()
        val cache = TimelineGroupingCache(counting)
        val items = TimelineFixtures.sampleItems()
        cache.group(items, GroupingMode.DAY, TODAY, ZONE)

        cache.group(items, GroupingMode.DAY, TODAY.plusDays(1), ZONE)
        cache.group(items, GroupingMode.DAY, TODAY.plusDays(1), ZoneId.of("UTC"))

        assertEquals(3, counting.modes.size)
    }

    @Test
    fun directGrouperMatchesTheDirectFunction() {
        val items: List<MediaItem> = TimelineFixtures.sampleItems()
        val today = LocalDate.of(2026, 10, 16)

        assertEquals(
            TimelineGrouping.group(items, GroupingMode.MONTH, today, ZONE),
            TimelineGrouper.Direct.group(items, GroupingMode.MONTH, today, ZONE),
        )
    }

    private class CountingGrouper : TimelineGrouper {
        val modes = mutableListOf<GroupingMode>()

        override fun group(
            items: List<MediaItem>,
            mode: GroupingMode,
            today: LocalDate,
            zone: ZoneId,
        ): List<TimelineSection> {
            modes += mode
            return TimelineGrouping.group(items, mode, today, zone)
        }
    }
}
