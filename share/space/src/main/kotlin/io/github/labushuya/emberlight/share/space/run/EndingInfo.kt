package io.github.labushuya.emberlight.share.space.run

/**
 * Angaben zum Ende eines Space für die Benachrichtigung des Gastgebers (ADR 0017, Paket 3.5a-3): der Name und die Tage der
 * Aufbewahrung nach dem Ende.
 */
data class EndingInfo(
    val name: String,
    val retentionDays: Int,
)
