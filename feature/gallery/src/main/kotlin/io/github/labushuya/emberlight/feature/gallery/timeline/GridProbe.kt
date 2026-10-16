package io.github.labushuya.emberlight.feature.gallery.timeline

/**
 * Test-Seam: Zählt Kompositionen des Rasters und seiner Zellen. Die Tests setzen einen Zähler über [GridProbeHolder], im
 * Betrieb ist keiner gesetzt. Dann kostet die Prüfung nur einen Null-Vergleich pro Komposition.
 */
internal interface GridProbe {
    /** Das Raster wurde komponiert (einmal je Komposition, nicht je Frame). */
    fun gridComposed()

    /** Die Zelle mit der Medien-URI wurde komponiert. */
    fun cellComposed(uri: String)

    /** Die Zelle mit der Medien-URI ist in der Komposition (Eintritt) bzw. wurde daraus entfernt (Austritt). */
    fun cellEntered(uri: String)

    fun cellLeft(uri: String)
}

/**
 * Hält den Zähler für Tests. Ein Objekt statt einer CompositionLocal: Eine zusätzliche CompositionLocal würde die Regeln der
 * Compose-Prüfung verletzen, und ein Parameter würde durch jede Ebene reichen. Tests setzen den Zähler und setzen ihn danach
 * auf `null` zurück.
 */
internal object GridProbeHolder {
    @Volatile
    var current: GridProbe? = null
}
