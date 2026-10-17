package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.core.media.exif.PhotoExif
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerDetailsContribution
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerItem
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerMediaType
import javax.inject.Inject

/**
 * Liefert für die synthetischen Fotos plausible EXIF-Angaben für den Infobereich (ADR 0010, ADR 0011). Die Werte folgen dem
 * Index des Eintrags, sind also bei jedem Start gleich. Andere Einträge und Videos bleiben bei der Standardquelle. Im
 * Release-Build gibt es diesen Beitrag nicht, dort stehen keine Demo-Daten im Infobereich.
 */
internal class DemoViewerDetailsContribution
    @Inject
    constructor() : ViewerDetailsContribution {
        override fun exifFor(item: ViewerItem): PhotoExif? {
            if (item.type != ViewerMediaType.IMAGE) return null
            val index = SyntheticLibrary.indexOf(item.uri) ?: return null
            return demoExif(index)
        }
    }

/** Synthetische Aufnahmedaten für den Eintrag mit dem Index [index]. Ohne Ortsdaten, wie jede EXIF-Angabe in der App. */
internal fun demoExif(index: Int): PhotoExif =
    PhotoExif(
        make = DEMO_MAKE,
        model = DEMO_MODELS[index % DEMO_MODELS.size],
        lensModel = if (index % 2 == 0) DEMO_LENS else null,
        fNumber = DEMO_APERTURES[index % DEMO_APERTURES.size],
        exposureSeconds = DEMO_EXPOSURES[(index / 4) % DEMO_EXPOSURES.size],
        iso = DEMO_ISO[(index / 16) % DEMO_ISO.size],
        focalLengthMm = DEMO_FOCAL[index % DEMO_FOCAL.size],
        flashFired = index % 3 == 0,
    )

private const val DEMO_MAKE = "Demo-Kamera"
private val DEMO_MODELS = listOf("Demo M1", "Demo M2")
private const val DEMO_LENS = "Demo 35 mm"
private val DEMO_APERTURES = listOf(1.8, 2.8, 4.0, 5.6)
private val DEMO_EXPOSURES = listOf(1.0 / 60.0, 1.0 / 125.0, 1.0 / 250.0, 1.0 / 1000.0)
private val DEMO_ISO = listOf(100, 200, 400, 800)
private val DEMO_FOCAL = listOf(24.0, 35.0, 50.0, 85.0)
