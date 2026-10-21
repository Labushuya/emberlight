package io.github.labushuya.emberlight.share.space

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Verstoß gegen die Pfadregeln des Space-Speichers. Wird wie ein ungültiger Argumentwert behandelt. */
class UnsafeStoragePathException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Dateiablage eines Space unter `<spacesDir>/<spaceId>/` (ADR 0013, Speicher). Alle Zugriffe laufen über diese Klasse.
 *
 * Namen kommen nie vom Client. Gespeichert wird unter dem SHA-256 des Inhalts (inhaltsadressiert) oder unter einem
 * zufälligen Temp-Namen. Jeder Pfad wird geprüft: nur einfache Namen ohne Trennzeichen, keine Symlinks auf jeder
 * Ebene, das Ergebnis liegt unterhalb der Space-Wurzel.
 */
class SpaceStorage(
    spacesDir: File,
    val spaceId: SpaceId,
) {
    private val base: Path = spacesDir.toPath().toAbsolutePath().normalize()
    private val directory: Path = base.resolve(spaceId.value)
    private val realRoot: Path

    init {
        Files.createDirectories(directory)
        rejectSymlinks(directory)
        realRoot = directory.toRealPath()
    }

    /** Neuer Temp-Name im Space-Verzeichnis. Die Datei liegt noch nicht vor. */
    fun newTempPath(): Path = resolveName("$TEMP_PREFIX${OpaqueId.generate()}$TEMP_SUFFIX")

    /** Pfad eines inhaltsadressierten Objekts. [sha256Hex] muss ein Hash in Kleinbuchstaben sein. */
    fun contentPath(sha256Hex: String): Path {
        require(SHA256_HEX.matches(sha256Hex)) { "Ungültiger Hash." }
        return resolveName(sha256Hex)
    }

    /**
     * Prüft einen einfachen Namen und liefert seinen Pfad. Namen mit Trennzeichen, `..` oder führendem Punkt sind
     * ungültig. Symlinks auf dem Weg oder als Ziel werden abgewiesen.
     */
    fun resolveName(name: String): Path {
        if (!SIMPLE_NAME.matches(name)) throw UnsafeStoragePathException("Ungültiger Dateiname im Space-Speicher.")
        val candidate = directory.resolve(name).normalize()
        rejectSymlinks(candidate)
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            val real = candidate.toRealPath()
            if (real.parent != realRoot) throw UnsafeStoragePathException("Ziel liegt außerhalb des Space-Speichers.")
        }
        return candidate
    }

    /** Verschiebt atomar (ersetzt ein vorhandenes Ziel, das ohne Index-Eintrag liegt). */
    fun moveAtomically(
        from: Path,
        to: Path,
    ) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Löscht eine Datei, falls vorhanden. Liefert `true`, wenn sie existierte. */
    fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    /**
     * Löscht Einträge des Space-Verzeichnisses, deren Name nicht in [keep] steht. Titelbilder ([COVER_PREFIX]) bleiben
     * unberührt, sie verwaltet [deleteCoversExcept]. Liefert die Anzahl der gelöschten Einträge.
     */
    fun deleteUnlisted(keep: Set<String>): Int {
        val names = Files.newDirectoryStream(directory).use { stream -> stream.map { it.fileName.toString() } }
        return names
            .filter { it !in keep && !it.startsWith(COVER_PREFIX) && it != PREVIEW_DIR }
            .count { name -> Files.deleteIfExists(directory.resolve(name)) }
    }

    /**
     * Pfad einer Vorschau (`previews/<sha256>-<longEdge>.jpg`, ADR 0019). Legt den Unterordner bei Bedarf an. [sha256Hex]
     * ist der Hash des Originals, er muss in Kleinbuchstaben vorliegen.
     */
    fun previewPath(
        sha256Hex: String,
        longEdge: Int,
    ): Path {
        require(SHA256_HEX.matches(sha256Hex)) { "Ungültiger Hash." }
        return resolveIn(PREVIEW_DIR, previewFileName(sha256Hex, longEdge))
    }

    /** Pfad der Vorschau, sofern die Datei als reguläre Datei vorliegt. Sonst `null`. */
    fun existingPreview(
        sha256Hex: String,
        longEdge: Int,
    ): Path? {
        require(SHA256_HEX.matches(sha256Hex)) { "Ungültiger Hash." }
        if (!Files.isDirectory(directory.resolve(PREVIEW_DIR), LinkOption.NOFOLLOW_LINKS)) return null
        val path = resolveIn(PREVIEW_DIR, previewFileName(sha256Hex, longEdge), create = false)
        return path.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Löscht die Vorschauen (320 px und 1600 px) eines Originals, falls vorhanden. Ist der Vorschau-Ordner danach leer,
     * verschwindet er auch. Lesen und Löschen legen keinen Ordner an.
     */
    fun deletePreviews(sha256Hex: String) {
        require(SHA256_HEX.matches(sha256Hex)) { "Ungültiger Hash." }
        val dir = directory.resolve(PREVIEW_DIR)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        PREVIEW_EDGES.forEach { edge ->
            Files.deleteIfExists(resolveIn(PREVIEW_DIR, previewFileName(sha256Hex, edge), create = false))
        }
        if (Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }) Files.deleteIfExists(dir)
    }

    /** Löscht Dateien im Vorschau-Ordner, die nicht in [keep] (Dateinamen) stehen. Liefert die Anzahl der gelöschten. */
    fun deletePreviewsExcept(keep: Set<String>): Int {
        val dir = directory.resolve(PREVIEW_DIR)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return 0
        rejectSymlinks(dir)
        val names = Files.newDirectoryStream(dir).use { stream -> stream.map { it.fileName.toString() } }
        return names
            .filter { it !in keep }
            .count { name -> Files.deleteIfExists(dir.resolve(name)) }
    }

    /**
     * Einfacher Name in einem Unterordner der Space-Wurzel. Dieselben Prüfungen wie in [resolveName]: keine
     * Trennzeichen, keine Symlinks auf dem Weg, das Ziel liegt im Unterordner.
     */
    private fun resolveIn(
        subdirectory: String,
        name: String,
        create: Boolean = true,
    ): Path {
        if (!SIMPLE_NAME.matches(name)) throw UnsafeStoragePathException("Ungültiger Dateiname im Space-Speicher.")
        val dir = directory.resolve(subdirectory)
        rejectSymlinks(dir)
        if (create) Files.createDirectories(dir)
        val realDir = dir.toRealPath()
        val candidate = dir.resolve(name).normalize()
        rejectSymlinks(candidate)
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (candidate.toRealPath().parent != realDir) throw UnsafeStoragePathException("Ziel liegt außerhalb des Space-Speichers.")
        }
        return candidate
    }

    /** Pfad eines Titelbilds. [sha256Hex] muss ein Hash in Kleinbuchstaben sein. Titelbilder liegen getrennt von Medien. */
    fun coverPath(sha256Hex: String): Path {
        require(SHA256_HEX.matches(sha256Hex)) { "Ungültiger Hash." }
        return resolveName(COVER_PREFIX + sha256Hex)
    }

    /** Löscht alle Titelbilder außer den in [keepSha256] genannten. Liefert die Anzahl. */
    fun deleteCoversExcept(keepSha256: Set<String>): Int {
        val keepNames = keepSha256.map { COVER_PREFIX + it }.toSet()
        val names = Files.newDirectoryStream(directory).use { stream -> stream.map { it.fileName.toString() } }
        return names
            .filter { it.startsWith(COVER_PREFIX) && it !in keepNames }
            .count { name -> Files.deleteIfExists(directory.resolve(name)) }
    }

    /** Entfernt den gesamten Speicher dieses Space, also alle Medien und Titelbilder samt Verzeichnis. */
    fun deleteEverything() {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        rejectSymlinks(directory)
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun rejectSymlinks(path: Path) {
        if (!path.startsWith(base)) throw UnsafeStoragePathException("Pfad liegt außerhalb des Speicher-Ordners.")
        var current: Path? = path
        while (current != null && current != base) {
            if (Files.isSymbolicLink(current)) throw UnsafeStoragePathException("Symlink im Space-Speicher abgewiesen.")
            current = current.parent
        }
    }

    companion object {
        const val TEMP_PREFIX = "tmp-"
        const val TEMP_SUFFIX = ".part"

        /** Präfix der Titelbilder (Paket 3.2). Medien tragen nur den Hash, Titelbilder dieses Präfix. */
        const val COVER_PREFIX = "cover-"

        /** Unterordner der Vorschauen (Paket 3.5a-2, ADR 0019). Nie im MediaStore, nie im Coil-Cache. */
        const val PREVIEW_DIR = "previews"

        /** Kantenlängen der Vorschau-Dateien: 320 px für Bilder und Videos, 1600 px für die HEIC-Ansicht. */
        val PREVIEW_EDGES = listOf(320, 1600)

        /** Dateiname einer Vorschau. Der Hash ist der des Originals, daher gehört jede Datei zu genau einem Medium. */
        fun previewFileName(
            sha256Hex: String,
            longEdge: Int,
        ): String = "$sha256Hex-$longEdge.jpg"

        private val SIMPLE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
