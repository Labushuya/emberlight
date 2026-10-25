package io.github.labushuya.emberlight.netlab.net

/**
 * Prüft, ob eine Adresse im selben /24-Netz liegt wie der Gastgeber (Paket 4.0, Funktion C2 ab API 26).
 *
 * Reine Funktion. Nur IPv4 im Format a.b.c.d zählt. Alles andere ergibt `false`.
 */
object SubnetCheck {
    fun sameSlash24(
        hostAddress: String,
        candidates: Collection<String>,
    ): Boolean {
        val prefix = slash24(hostAddress) ?: return false
        return candidates.any { slash24(it) == prefix }
    }

    private fun slash24(address: String): String? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        if (parts.any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }) return null
        return parts.take(3).joinToString(".")
    }
}
