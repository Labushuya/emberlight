package io.github.labushuya.emberlight.share.space.management

/**
 * Feste Akzentfarben eines Space als Hex-Werte (Paket 3.4, ADR 0018). Der Server schickt an die Gäste-Seite nur einen
 * dieser Werte, nie einen freien CSS-Wert. Die Reihenfolge entspricht `SpaceAccents` in `core:design`. Ein Test in
 * `feature:space` prüft die Gleichheit.
 */
object SpaceAccentPalette {
    val HEX: List<String> =
        listOf(
            "#FF8A3D",
            "#FFC27A",
            "#FF8F75",
            "#E8A33D",
            "#E38AA0",
            "#D9774A",
            "#A8B86B",
            "#8FA8C8",
        )

    /** Hex-Wert für einen gespeicherten Index. Ungültige Indizes fallen auf die erste Farbe zurück, wie in der Oberfläche. */
    fun hexAt(index: Int): String = HEX.getOrElse(index) { HEX.first() }
}
