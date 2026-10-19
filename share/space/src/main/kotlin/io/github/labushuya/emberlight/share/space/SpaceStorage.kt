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

    /** Löscht alle Einträge des Space-Verzeichnisses, deren Name nicht in [keep] steht. Liefert die Anzahl. */
    fun deleteUnlisted(keep: Set<String>): Int {
        val names = Files.newDirectoryStream(directory).use { stream -> stream.map { it.fileName.toString() } }
        return names.filter { it !in keep }.count { name ->
            Files.deleteIfExists(directory.resolve(name))
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
        private val SIMPLE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
