package io.github.labushuya.emberlight.share.server

/**
 * IPv4-Adresse mit Präfixlänge, etwa `192.168.1.20/24` (ADR 0013, Netz). Die Adresse ist die des Servers, das Netz
 * bestimmt, welche Absender zugelassen sind.
 */
class Ipv4Subnet(
    val address: String,
    val prefixLength: Int,
) {
    private val addressBits: Int = parse(address)

    init {
        require(prefixLength in 0..32) { "Präfixlänge muss zwischen 0 und 32 liegen." }
    }

    /** `true`, wenn [ip] eine IPv4-Adresse in diesem Netz ist. Andere Adressen und Formate sind nicht enthalten. */
    fun contains(ip: String?): Boolean {
        if (ip == null || !IPV4.matches(ip)) return false
        val candidate = runCatching { parse(ip) }.getOrNull() ?: return false
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (candidate and mask) == (addressBits and mask)
    }

    private companion object {
        val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

        fun parse(text: String): Int {
            val match = requireNotNull(IPV4.matchEntire(text)) { "Keine IPv4-Adresse." }
            return match.groupValues
                .drop(1)
                .map { part ->
                    val value = part.toInt()
                    require(value in 0..255) { "IPv4-Oktett außerhalb 0 bis 255." }
                    value
                }.fold(0) { acc, octet -> (acc shl 8) or octet }
        }
    }
}
