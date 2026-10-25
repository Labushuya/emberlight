package io.github.labushuya.emberlight.netlab.capability

/**
 * Beschreibt Zustandswechsel der Netze für die Zeitlinie (Messpunkte M1, M2, M5, M9). Reine Funktion.
 *
 * Verglichen werden nur Merkmale, die für die Messung zählen: WLAN aktiv, Mobilfunk validiert, VPN aktiv und das
 * Standardnetz. Ohne vorherigen Zustand (`previous == null`) entsteht ein Startzustand.
 */
object NetworkChanges {
    fun describe(
        previous: List<NetworkFact>?,
        current: List<NetworkFact>,
    ): List<String> {
        val now = Snapshot.of(current)
        if (previous == null) return now.lines(prefix = "Startzustand")
        val before = Snapshot.of(previous)
        return buildList {
            if (before.wifi != now.wifi) add(if (now.wifi) "WLAN verbunden" else "WLAN getrennt")
            if (before.cellularValidated != now.cellularValidated) {
                add(if (now.cellularValidated) "Mobilfunk validiert" else "Mobilfunk nicht mehr validiert")
            }
            if (before.vpn != now.vpn) add(if (now.vpn) "VPN aktiv" else "VPN beendet")
            if (before.defaultName != now.defaultName) add("Standardnetz: ${now.defaultName}")
        }
    }

    private data class Snapshot(
        val wifi: Boolean,
        val cellularValidated: Boolean,
        val vpn: Boolean,
        val defaultName: String,
    ) {
        fun lines(prefix: String): List<String> =
            buildList {
                add("$prefix: WLAN ${onOff(wifi)}")
                add("$prefix: Mobilfunk ${if (cellularValidated) "validiert" else "nicht validiert"}")
                add("$prefix: VPN ${onOff(vpn)}")
                add("$prefix: Standardnetz $defaultName")
            }

        companion object {
            fun of(networks: List<NetworkFact>): Snapshot {
                val default = networks.firstOrNull { it.isDefault }
                return Snapshot(
                    wifi = networks.any { Transport.WIFI in it.transports },
                    cellularValidated = networks.any { Transport.CELLULAR in it.transports && it.validated },
                    vpn = networks.any { Transport.VPN in it.transports },
                    defaultName = default?.let { describeTransports(it.transports) } ?: "keines",
                )
            }

            private fun describeTransports(transports: Set<Transport>): String =
                when {
                    Transport.VPN in transports -> "VPN"
                    Transport.WIFI in transports -> "WLAN"
                    Transport.CELLULAR in transports -> "Mobilfunk"
                    Transport.ETHERNET in transports -> "Ethernet"
                    else -> "sonstiges"
                }

            private fun onOff(value: Boolean): String = if (value) "aktiv" else "inaktiv"
        }
    }
}
