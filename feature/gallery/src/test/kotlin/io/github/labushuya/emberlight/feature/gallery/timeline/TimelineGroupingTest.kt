package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.item
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.random.Random

class TimelineGroupingTest {
    @Test
    fun emptyInputGivesNoSections() {
        assertEquals(emptyList<TimelineSection>(), TimelineGrouping.group(emptyList(), GroupingMode.DAY, TODAY, ZONE))
    }

    @Test
    fun dayModeMakesOneSectionPerDayInOrder() {
        val items =
            listOf(
                item(3, millis("2026-10-16T09:00")),
                item(2, millis("2026-10-16T08:00")),
                item(1, millis("2026-10-14T20:00")),
            )

        val sections = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE)

        assertEquals(listOf("day:2026-10-16", "day:2026-10-14"), sections.map { it.key })
        assertEquals(listOf(3L, 2L), sections[0].items.map { it.id })
        assertEquals(listOf(1L), sections[1].items.map { it.id })
    }

    @Test
    fun todayYesterdayAndOlderDaysUseTheirHeaders() {
        val items =
            listOf(
                item(3, millis("2026-10-16T09:00")),
                item(2, millis("2026-10-15T09:00")),
                item(1, millis("2026-10-14T09:00")),
            )

        val headers = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE).map { it.header }

        assertEquals(TimelineHeader.Today, headers[0])
        assertEquals(TimelineHeader.Yesterday, headers[1])
        assertEquals(TimelineHeader.Day(LocalDate.of(2026, 10, 14), withYear = false), headers[2])
    }

    @Test
    fun dayInOtherYearShowsTheYear() {
        val header = TimelineGrouping.dayHeader(LocalDate.of(2025, 6, 3), TODAY)

        assertEquals(TimelineHeader.Day(LocalDate.of(2025, 6, 3), withYear = true), header)
    }

    @Test
    fun dayInCurrentYearHidesTheYear() {
        val header = TimelineGrouping.dayHeader(LocalDate.of(2026, 1, 2), TODAY)

        assertEquals(TimelineHeader.Day(LocalDate.of(2026, 1, 2), withYear = false), header)
    }

    @Test
    fun yearBoundaryWithFixedClock() {
        // Heute ist der 2. Januar 2027. Der 1. Januar ist „Gestern“, der 31. Dezember 2026 zeigt das Jahr.
        val today = LocalDate.of(2027, 1, 2)
        val items =
            listOf(
                item(3, millis("2027-01-01T12:00")),
                item(2, millis("2026-12-31T12:00")),
                item(1, millis("2026-12-30T12:00")),
            )

        val headers = TimelineGrouping.group(items, GroupingMode.DAY, today, ZONE).map { it.header }

        assertEquals(TimelineHeader.Yesterday, headers[0])
        assertEquals(TimelineHeader.Day(LocalDate.of(2026, 12, 31), withYear = true), headers[1])
        assertEquals(TimelineHeader.Day(LocalDate.of(2026, 12, 30), withYear = true), headers[2])
    }

    @Test
    fun timeZoneDecidesTheDay() {
        // 23:30 UTC am 15. Oktober ist in Berlin schon der 16. Oktober (Heute), in UTC noch der 15. (Gestern).
        val utcMillis = LocalDateTime.parse("2026-10-15T23:30").toInstant(ZoneOffset.UTC).toEpochMilli()
        val entry = item(1, utcMillis)

        val inBerlin = TimelineGrouping.group(listOf(entry), GroupingMode.DAY, TODAY, ZONE)
        val inUtc = TimelineGrouping.group(listOf(entry), GroupingMode.DAY, TODAY, ZoneOffset.UTC)

        assertEquals(TimelineHeader.Today, inBerlin.single().header)
        assertEquals(TimelineHeader.Yesterday, inUtc.single().header)
    }

    @Test
    fun monthModeGroupsByCalendarMonth() {
        val items =
            listOf(
                item(4, millis("2026-10-16T09:00")),
                item(3, millis("2026-10-01T00:30")),
                item(2, millis("2026-09-30T23:00")),
                item(1, millis("2026-09-01T08:00")),
            )

        val sections = TimelineGrouping.group(items, GroupingMode.MONTH, TODAY, ZONE)

        assertEquals(listOf("month:2026-10", "month:2026-09"), sections.map { it.key })
        assertEquals(TimelineHeader.Month(YearMonth.of(2026, 10)), sections[0].header)
        assertEquals(listOf(4L, 3L), sections[0].items.map { it.id })
        assertEquals(listOf(2L, 1L), sections[1].items.map { it.id })
    }

    @Test
    fun monthModeAcrossYearBoundary() {
        val items = listOf(item(2, millis("2027-01-01T10:00")), item(1, millis("2026-12-31T10:00")))

        val sections = TimelineGrouping.group(items, GroupingMode.MONTH, LocalDate.of(2027, 1, 2), ZONE)

        assertEquals(listOf("month:2027-01", "month:2026-12"), sections.map { it.key })
    }

    @Test
    fun videosStayInTheirDayGroup() {
        val items =
            listOf(
                item(2, millis("2026-10-16T09:00"), MediaType.VIDEO, durationMs = 5_000L),
                item(1, millis("2026-10-16T08:00")),
            )

        val section = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE).single()

        assertEquals(listOf(MediaType.VIDEO, MediaType.IMAGE), section.items.map { it.type })
    }

    @Test
    fun groupingModeFollowsColumnStage() {
        assertEquals(GroupingMode.DAY, GroupingMode.forColumns(1))
        assertEquals(GroupingMode.DAY, GroupingMode.forColumns(DEFAULT_COLUMNS))
        assertEquals(GroupingMode.DAY, GroupingMode.forColumns(GroupingMode.MAX_DAY_COLUMNS))
        assertEquals(GroupingMode.MONTH, GroupingMode.forColumns(GroupingMode.MAX_DAY_COLUMNS + 1))
    }

    @Test
    fun everyItemAppearsExactlyOnceInOrder() {
        val items = sorted(count = 5_000, seed = SEED)

        val flattened = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE).flatMap { it.items }

        assertEquals(items.map { it.id }, flattened.map { it.id })
    }

    /**
     * Gruppierung ist linear: Das Verhältnis 50.000 zu 10.000 Einträge liegt nahe 5, quadratisch wäre es 25.
     * Gemessen wird das beste von fünf Läufen nach einem Aufwärmlauf, wie in MediaIndexPerformanceTest.
     */
    @Test
    fun groupingGrowsLinearlyBetween10kAnd50k() {
        val small = sorted(count = 10_000, seed = SEED)
        val large = sorted(count = 50_000, seed = SEED)
        TimelineGrouping.group(small, GroupingMode.DAY, TODAY, ZONE)

        val smallNanos = bestOf { TimelineGrouping.group(small, GroupingMode.DAY, TODAY, ZONE) }
        val largeNanos = bestOf { TimelineGrouping.group(large, GroupingMode.DAY, TODAY, ZONE) }
        val ratio = largeNanos.toDouble() / smallNanos

        val formatted = "%.2f".format(ratio)
        println(
            "TimelineGrouping: 10k=${smallNanos / 1_000_000} ms, 50k=${largeNanos / 1_000_000} ms, ratio=$formatted",
        )
        assertTrue("Verhältnis 50k/10k = $ratio, erwartet unter $MAX_RATIO", ratio < MAX_RATIO)
    }

    private fun sorted(
        count: Int,
        seed: Int,
    ): List<MediaItem> {
        val random = Random(seed)
        return (0 until count)
            .map { index -> item(index.toLong(), millis("2026-10-16T12:00") - random.nextLong(0L, SPAN_DAYS * DAY_MS)) }
            .sortedByDescending { it.timestamp }
    }

    private inline fun bestOf(block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - start)
        }
        return best
    }

    private companion object {
        const val SEED = 7
        const val DAY_MS = 86_400_000L
        const val SPAN_DAYS = 3_000L
        const val MAX_RATIO = 12.0
    }
}
