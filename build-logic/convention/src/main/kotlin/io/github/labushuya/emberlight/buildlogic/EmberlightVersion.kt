package io.github.labushuya.emberlight.buildlogic

/**
 * Version der App, abgeleitet aus dem Git-Tag `vX.Y.Z` bzw. `vX.Y.Z-(alpha|beta|rc).N` (übergeben als
 * Gradle-Property [PROPERTY], dann ohne `v`).
 *
 * `versionCode = (X * 10000 + Y * 100 + Z) * 100 + Stufe`. Die Stufe bildet den Reifegrad ab, damit eine
 * Vorabversion kleiner ist als ihre finale Version und jede Version größer als alle vorherigen:
 * - alpha.N: N (1 bis 19)
 * - beta.N: 20 + N (21 bis 39)
 * - rc.N: 40 + N (41 bis 59)
 * - final: 99
 *
 * Y und Z sind auf 0 bis 99 begrenzt, N auf 1 bis 19, X auf 2099. Der größte Code ist damit 2_099_999_999
 * und bleibt unter der Google-Play-Grenze 2_100_000_000 (ADR 0004, Nachtrag).
 */
data class EmberlightVersion(
    val name: String,
    val code: Int,
) {
    companion object {
        const val PROPERTY = "emberlight.version"
        const val DEV_NAME = "0.0.0-dev"
        const val DEV_CODE = 1

        // Gruppen: 1 Major, 2 Minor, 3 Patch, 4 Vorab-Art (leer bei final), 5 Vorab-Nummer (leer bei final).
        private val VERSION =
            Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.([1-9]\d*))?$""")
        private const val MAX_MINOR_OR_PATCH = 99
        private const val MAX_PRERELEASE = 19
        private const val FINAL_STAGE = 99

        // Größte Major-Version: 2099 * 10000 + 99 * 100 + 99 = 20_999_999, mal 100 plus Stufe ergibt 2_099_999_999.
        private const val MAX_MAJOR = 2099

        // Erster Stufenwert je Vorab-Art. Die Stufe ist Offset + Nummer.
        private val PRERELEASE_OFFSET = mapOf("alpha" to 0, "beta" to 20, "rc" to 40)

        /** Liefert die Version aus der Property, ohne Property die Entwicklungsversion. */
        fun resolve(raw: String?): EmberlightVersion =
            if (raw == null) {
                EmberlightVersion(DEV_NAME, DEV_CODE)
            } else {
                parse(raw)
            }

        /** Wirft [IllegalArgumentException] mit einer klaren Meldung bei ungültigem Format oder Wertebereich. */
        fun parse(raw: String): EmberlightVersion {
            val match =
                VERSION.matchEntire(raw) ?: throw IllegalArgumentException(
                    "Ungültige Version '$raw'. Erwartet wird X.Y.Z oder X.Y.Z-alpha.N, -beta.N bzw. -rc.N " +
                        "(N von 1 bis $MAX_PRERELEASE), ohne 'v'-Präfix und ohne Leerzeichen, " +
                        "z. B. 0.1.0 oder 0.3.0-alpha.1.",
                )
            val groups = match.groupValues
            val major = groups[1].toNumber(raw)
            val minor = groups[2].toNumber(raw)
            val patch = groups[3].toNumber(raw)
            require(major <= MAX_MAJOR) {
                "Major-Version $major ist zu groß, der versionCode würde den Android-Maximalwert überschreiten."
            }
            require(minor <= MAX_MINOR_OR_PATCH) {
                "Minor-Version $minor ist ungültig, das versionCode-Schema erlaubt nur Werte bis $MAX_MINOR_OR_PATCH."
            }
            require(patch <= MAX_MINOR_OR_PATCH) {
                "Patch-Version $patch ist ungültig, das versionCode-Schema erlaubt nur Werte bis $MAX_MINOR_OR_PATCH."
            }
            val kind = groups[4]
            val stage =
                if (kind.isEmpty()) {
                    FINAL_STAGE
                } else {
                    val number = groups[5].toNumber(raw)
                    require(number in 1..MAX_PRERELEASE) {
                        "Vorab-Nummer $number ist ungültig, erlaubt sind 1 bis $MAX_PRERELEASE."
                    }
                    PRERELEASE_OFFSET.getValue(kind) + number
                }
            // Der Match ist vollständig und ohne führende Nullen, daher entspricht der Name der Eingabe.
            return EmberlightVersion(
                name = raw,
                code = (major * 10_000 + minor * 100 + patch) * 100 + stage,
            )
        }

        private fun String.toNumber(raw: String): Int =
            toIntOrNull() ?: throw IllegalArgumentException("Version '$raw' enthält einen zu großen Zahlenwert.")
    }
}
