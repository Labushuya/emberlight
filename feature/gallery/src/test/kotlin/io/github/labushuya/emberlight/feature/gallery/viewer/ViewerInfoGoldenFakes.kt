package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import java.time.ZoneId

/** Zustand, den die Infobereich-Goldens festhalten. Alles ohne Animation, damit die Aufnahme deterministisch bleibt. */
internal enum class InfoGolden {
    /** Infobereich offen, Bild oben sichtbar. */
    INFO_OPEN,

    /** Kurzer Blick: Blick-Stufe mit hervorgehobener Kachel. */
    PEEK,
}

/** Die Kachel, die der Blick-Golden hervorhebt (Koordinaten im Overlay). */
internal val GOLDEN_TILE = Rect(132f, 560f, 264f, 692f)

/**
 * Overlay für die Infobereich-Goldens, geöffnet auf das erste Element. [exif] liefert die EXIF-Angaben (Fake, ohne Datei). Der
 * Zustand wird ohne Animation gesetzt.
 */
@Composable
internal fun ViewerInfoGoldenContent(
    items: List<ViewerItem>,
    exif: ExifLookup,
    golden: InfoGolden,
) {
    EmberlightTheme {
        val scope = rememberCoroutineScope()
        val overlay =
            remember(scope) {
                ViewerOverlayState(scope, mutableStateOf(items.first().id), { id ->
                    if (id ==
                        items.first().id
                    ) {
                        GOLDEN_TILE
                    } else {
                        null
                    }
                })
            }
        LaunchedEffect(overlay) {
            when (golden) {
                InfoGolden.INFO_OPEN -> overlay.snapInfo(open = true)
                InfoGolden.PEEK -> overlay.snapPeek(GOLDEN_TILE)
            }
        }
        ViewerOverlay(
            overlay = overlay,
            items = items,
            playback = remember { ViewerPlaybackViewModel(NoPlayers) },
            imageSource = solidImageSources,
            initialChromeVisible = true,
            zone = ZoneId.of("Europe/Berlin"),
            exif = exif,
        )
    }
}

/** Fotografie mit Anzeigedaten, wie sie der MediaStore liefert (ohne Ortsdaten). */
internal fun photoWithDetails(
    id: Long,
    localDateTime: String,
): ViewerItem =
    photo(id, localDateTime).copy(
        details =
            MediaDetails(
                displayName = "IMG_20$id.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 3_482_000L,
                albumName = "Camera",
                folder = "DCIM/Camera",
            ),
    )

/** Video mit Anzeigedaten. */
internal fun videoWithDetails(
    id: Long,
    localDateTime: String,
    durationMs: Long,
): ViewerItem =
    video(id, localDateTime, durationMs).copy(
        details =
            MediaDetails(
                displayName = "VID_0042.mp4",
                mimeType = "video/mp4",
                sizeBytes = 42_000_000L,
                albumName = "Camera",
                folder = "DCIM/Camera",
            ),
    )
