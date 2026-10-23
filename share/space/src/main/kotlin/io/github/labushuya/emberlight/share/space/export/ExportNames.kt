package io.github.labushuya.emberlight.share.space.export

/**
 * Namen für die Übernahme in die Galerie (Paket 3.7, ADR 0022). Reine Funktionen ohne Android.
 *
 * Ordner und Dateinamen kommen aus dem Gerät bzw. vom Gast, deshalb werden sie bereinigt, bevor sie in die Galerie gehen.
 */
object ExportNames {
    /** Oberste Ebene im Medienordner, wie in der Aufgabe festgelegt: `Pictures/Emberlight/<Space-Name>`. */
    const val ROOT_FOLDER = "Emberlight"

    private const val MAX_FOLDER_CHARS = 80
    private const val MAX_FILE_CHARS = 120
    private const val FALLBACK_FOLDER = "Space"
    private const val FALLBACK_FILE = "Medium"

    /** Zeichen, die auf Android-Dateisystemen (FAT, exFAT, Scoped Storage) nicht vorkommen dürfen. */
    private val FORBIDDEN = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")

    /** Alle Leerzeichen und Zeilenumbrüche (auch Tab) werden zu einem Leerzeichen, bevor die Zeichenregeln greifen. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Ordnername für einen Space. Ersetzt verbotene Zeichen, entfernt führende und nachgestellte Punkte und Leerzeichen,
     * kürzt auf [MAX_FOLDER_CHARS] Zeichen. Leer wird zu „Space“. Die Namen `.` und `..` gibt es danach nicht mehr.
     */
    fun folderName(spaceName: String): String {
        val cleaned =
            spaceName
                .replace(WHITESPACE, " ")
                .replace(FORBIDDEN, "_")
                .trim(' ', '.')
                .take(MAX_FOLDER_CHARS)
                .trim(' ', '.')
        return cleaned.ifEmpty { FALLBACK_FOLDER }
    }

    /**
     * Dateiname aus dem Originalnamen. Ohne Pfadanteile, ohne verbotene Zeichen, mit der Endung unverändert, wenn sie
     * vorhanden ist. Ein leerer Name wird zu „Medium“ mit [extension] (ohne Punkt), falls angegeben.
     */
    fun fileName(
        originalName: String,
        extension: String?,
    ): String {
        val base = originalName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            base
                .replace(WHITESPACE, " ")
                .replace(FORBIDDEN, "_")
                .trim(' ', '.')
                .take(MAX_FILE_CHARS)
                .trim(' ', '.')
        if (cleaned.isEmpty()) {
            return if (extension.isNullOrEmpty()) FALLBACK_FILE else "$FALLBACK_FILE.$extension"
        }
        return cleaned
    }

    /**
     * Macht [base] eindeutig gegen [taken]. Ist der Name frei, bleibt er. Sonst wird „ (2)“, „ (3)“ usw. vor der Endung
     * angehängt. Die Vergleiche sind ohne Beachtung der Groß- und Kleinschreibung, weil Android-Dateisysteme sie nicht
     * unterscheiden.
     */
    fun unique(
        base: String,
        taken: Set<String>,
    ): String {
        val lowerTaken = taken.map { it.lowercase() }.toHashSet()
        if (base.lowercase() !in lowerTaken) return base
        val dot = base.lastIndexOf('.').takeIf { it > 0 }
        val stem = if (dot == null) base else base.substring(0, dot)
        val suffix = if (dot == null) "" else base.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$suffix"
            if (candidate.lowercase() !in lowerTaken) return candidate
            n++
        }
    }

    /** Relativer Pfad für den Medienspeicher der Galerie. */
    fun relativePath(folder: String): String = "Pictures/$ROOT_FOLDER/$folder/"
}
