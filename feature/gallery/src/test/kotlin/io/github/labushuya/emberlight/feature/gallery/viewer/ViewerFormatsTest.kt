package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale

/** Titel des Viewers in Deutsch und Englisch, in der Zone der Galerie. */
@RunWith(AndroidJUnit4::class)
class ViewerFormatsTest {
    // 16. Oktober 2026, 09:30 in Berlin.
    private val morning = TimelineFixtures.millis("2026-10-16T09:30")

    // Die Verbindung zwischen Datum und Uhrzeit kommt von Android je Sprache („um“, „at“), nicht von uns.
    @Test
    fun germanTitleHasDayMonthYearAndTime() {
        assertEquals("16. Oktober 2026 um 09:30", ViewerFormats(Locale.GERMAN, TimelineFixtures.ZONE).title(morning))
    }

    @Test
    fun englishTitleHasMonthDayYearAndTime() {
        assertEquals("October 16, 2026 at 09:30", ViewerFormats(Locale.US, TimelineFixtures.ZONE).title(morning))
    }

    @Test
    fun titleUsesTheZoneItIsGiven() {
        // 09:30 in Berlin ist 07:30 in UTC. Der Titel folgt der Zone, die die Galerie vorgibt.
        assertEquals("16. Oktober 2026 um 07:30", ViewerFormats(Locale.GERMAN, ZoneId.of("UTC")).title(morning))
    }
}
