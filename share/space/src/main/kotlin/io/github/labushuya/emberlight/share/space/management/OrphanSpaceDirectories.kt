package io.github.labushuya.emberlight.share.space.management

import io.github.labushuya.emberlight.share.space.SpaceId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Entfernt Space-Verzeichnisse ohne Eintrag in der Datenbank (Paket 3.2, verwaiste Entwürfe). Ein Verzeichnis gilt nur
 * dann als verwaist, wenn sein Name eine gültige Space-Kennung ist und die Datenbank sie nicht kennt. Alles andere bleibt
 * unberührt: fremde Namen, Dateien, Symlinks und jeder Pfad außerhalb der Wurzel. Ein Baum mit Symlink wird ganz
 * stehen gelassen, damit nichts über den Link hinaus gelöscht wird.
 */
internal object OrphanSpaceDirectories {
    /** Liefert die Anzahl der entfernten Verzeichnisse. Fehler bei einzelnen Einträgen überspringen den Eintrag nur. */
    fun sweep(
        spacesDir: File,
        known: Set<String>,
    ): Int {
        val root = spacesDir.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
        val realRoot =
            try {
                root.toRealPath()
            } catch (failure: IOException) {
                return 0
            }
        val entries =
            try {
                Files.newDirectoryStream(root).use { stream -> stream.toList() }
            } catch (failure: IOException) {
                return 0
            }
        return entries.count { entry -> removeIfOrphan(entry, realRoot, known) }
    }

    private fun removeIfOrphan(
        entry: Path,
        realRoot: Path,
        known: Set<String>,
    ): Boolean {
        val name = entry.fileName.toString()
        if (name in known || SpaceId.parse(name) == null) return false
        // Dateien und Symlinks gehören nicht zu den Verzeichnissen eines Space: unangetastet lassen.
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return false
        val tree =
            try {
                if (entry.toRealPath().parent != realRoot) return false
                Files.walk(entry).use { stream -> stream.collect(Collectors.toList()) }
            } catch (failure: IOException) {
                return false
            }
        if (tree.any { Files.isSymbolicLink(it) }) return false
        return try {
            // Die Liste ist in Pfadreihenfolge (Eltern vor Kindern). Umgekehrt löschen: Kinder zuerst.
            tree.asReversed().forEach { Files.deleteIfExists(it) }
            true
        } catch (failure: IOException) {
            // Ein Rest bleibt liegen. Der nächste Start versucht es erneut.
            false
        }
    }
}
