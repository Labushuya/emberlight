package io.github.labushuya.emberlight.buildlogic

/**
 * Version der App, abgeleitet aus dem Git-Tag `vX.Y.Z` (übergeben als Gradle-Property [PROPERTY]).
 *
 * `versionCode = X * 10000 + Y * 100 + Z`, daher sind Minor und Patch auf 0 bis 99 begrenzt.
 */
data class EmberlightVersion(
    val name: String,
    val code: Int,
) {
    companion object {
        const val PROPERTY = "emberlight.version"
        const val DEV_NAME = "0.0.0-dev"
        const val DEV_CODE = 1

        private val SEMVER = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
        private const val MAX_MINOR_OR_PATCH = 99

        // Größte Major-Version, deren versionCode noch in einen 32-Bit-Int passt.
        private const val MAX_MAJOR = 214_747

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
                SEMVER.matchEntire(raw) ?: throw IllegalArgumentException(
                    "Ungültige Version '$raw'. Erwartet wird genau X.Y.Z, z. B. 0.1.0, " +
                        "ohne 'v'-Präfix, Suffix oder Leerzeichen.",
                )
            val (major, minor, patch) =
                match.destructured.toList().map { part ->
                    part.toIntOrNull()
                        ?: throw IllegalArgumentException("Version '$raw' enthält einen zu großen Zahlenwert.")
                }
            require(major <= MAX_MAJOR) {
                "Major-Version $major ist zu groß, der versionCode würde den Android-Maximalwert überschreiten."
            }
            require(minor <= MAX_MINOR_OR_PATCH) {
                "Minor-Version $minor ist ungültig, das versionCode-Schema erlaubt nur Werte bis $MAX_MINOR_OR_PATCH."
            }
            require(patch <= MAX_MINOR_OR_PATCH) {
                "Patch-Version $patch ist ungültig, das versionCode-Schema erlaubt nur Werte bis $MAX_MINOR_OR_PATCH."
            }
            return EmberlightVersion(
                name = "$major.$minor.$patch",
                code = major * 10_000 + minor * 100 + patch,
            )
        }
    }
}
