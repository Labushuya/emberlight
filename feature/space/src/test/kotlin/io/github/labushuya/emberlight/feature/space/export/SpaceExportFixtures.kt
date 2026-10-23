package io.github.labushuya.emberlight.feature.space.export

private const val MIB = 1024L * 1024L

/** Zustände der Auswahl für Goldens und Touch-Tests (Paket 3.7). */
internal object SpaceExportFixtures {
    /** Zwei gewählte Medien, eines mit Standort, ein bereits übernommenes und ein Video. */
    fun selected(): SpaceExportUiState {
        val items =
            listOf(
                ExportItemUi(
                    "m1",
                    isVideo = false,
                    sizeBytes = 3 * MIB,
                    locationKept = false,
                    exported = false,
                    selected = true,
                ),
                ExportItemUi(
                    "m2",
                    isVideo = false,
                    sizeBytes = 4 * MIB,
                    locationKept = true,
                    exported = false,
                    selected = true,
                ),
                ExportItemUi(
                    "m3",
                    isVideo = true,
                    sizeBytes = 48 * MIB,
                    locationKept = false,
                    exported = false,
                    selected = false,
                ),
                ExportItemUi(
                    "m4",
                    isVideo = false,
                    sizeBytes = 2 * MIB,
                    locationKept = false,
                    exported = true,
                    selected = false,
                ),
            )
        return SpaceExportUiState(
            loading = false,
            spaceName = "Sommerfest",
            items = items,
            selectedCount = 2,
            selectedBytes = 7 * MIB,
            withLocationCount = 1,
        )
    }

    /** Viele Medien, damit das Raster den Platz unter dem Raster füllt. */
    fun many(count: Int): SpaceExportUiState {
        val items =
            (1..count).map { index ->
                ExportItemUi(
                    "m$index",
                    isVideo = index % 4 == 0,
                    sizeBytes = index * MIB,
                    locationKept = false,
                    exported = false,
                    selected = index == 1,
                )
            }
        return SpaceExportUiState(
            loading = false,
            spaceName = "Sommerfest",
            items = items,
            selectedCount = 1,
            selectedBytes = MIB,
        )
    }
}
