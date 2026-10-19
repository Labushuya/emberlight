package io.github.labushuya.emberlight.share.space.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bereinigung verwaister Space-Verzeichnisse (Paket 3.2). Geprüft wird, was entfernt wird und vor allem, was unberührt
 * bleibt: bekannte Kennungen, fremde Namen, Dateien, Symlinks und alles außerhalb der Wurzel.
 */
class OrphanSpaceDirectoriesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        // Geschwister der Wurzel: Ein Verzeichnis mit einer gültigen Kennung darf dort niemals angefasst werden.
        outside = folder.newFolder("ausserhalb")
        root = folder.newFolder("spaces")
    }

    private fun dirWithFile(
        parent: File,
        name: String,
    ): File =
        File(parent, name).apply {
            mkdirs()
            File(this, "cover-${"a".repeat(64)}").writeBytes(byteArrayOf(1, 2, 3))
        }

    @Test
    fun anOrphanedSpaceDirectoryIsRemovedAndAKnownOneStays() {
        val known = "AAAAAAAAAAAAAAAAAAAAAA"
        val orphan = "BBBBBBBBBBBBBBBBBBBBBB"
        dirWithFile(root, known)
        dirWithFile(root, orphan)

        val removed = OrphanSpaceDirectories.sweep(root, setOf(known))

        assertEquals(1, removed)
        assertFalse(File(root, orphan).exists())
        assertTrue(File(root, known).isDirectory)
        assertTrue(File(File(root, known), "cover-${"a".repeat(64)}").isFile)
    }

    @Test
    fun namesThatAreNoSpaceIdAndFilesInTheRootStayUntouched() {
        val other = dirWithFile(root, "notizen")
        val dbFile = File(root, "spaces.db").apply { writeBytes(byteArrayOf(9)) }
        val shortName = dirWithFile(root, "CCCCCCCCCCCCCC")

        val removed = OrphanSpaceDirectories.sweep(root, emptySet())

        assertEquals(0, removed)
        assertTrue(other.isDirectory)
        assertTrue(dbFile.isFile)
        assertTrue(shortName.isDirectory)
    }

    @Test
    fun aSymlinkInsideTheRootIsNeverFollowedNorDeleted() {
        val target = dirWithFile(outside, "DDDDDDDDDDDDDDDDDDDDDD")
        val link = File(root, "EEEEEEEEEEEEEEEEEEEEEE").toPath()
        linkOrSkip(link, target.toPath())

        val removed = OrphanSpaceDirectories.sweep(root, emptySet())

        assertEquals(0, removed)
        assertTrue(Files.isSymbolicLink(link))
        assertTrue(target.isDirectory)
        assertTrue(File(target, "cover-${"a".repeat(64)}").isFile)
    }

    @Test
    fun anOrphanWithALinkInsideIsKeptWhole() {
        val target = dirWithFile(outside, "FFFFFFFFFFFFFFFFFFFFFF")
        val orphan = dirWithFile(root, "GGGGGGGGGGGGGGGGGGGGGG")
        linkOrSkip(File(orphan, "link").toPath(), target.toPath())

        val removed = OrphanSpaceDirectories.sweep(root, emptySet())

        assertEquals(0, removed)
        assertTrue(orphan.isDirectory)
        assertTrue(target.isDirectory)
        assertTrue(File(target, "cover-${"a".repeat(64)}").isFile)
    }

    @Test
    fun aValidNameOutsideTheRootIsNeverTouched() {
        val sibling = dirWithFile(outside, "HHHHHHHHHHHHHHHHHHHHHH")

        val removed = OrphanSpaceDirectories.sweep(root, emptySet())

        assertEquals(0, removed)
        assertTrue(sibling.isDirectory)
    }

    @Test
    fun aMissingRootIsNoError() {
        val missing = File(folder.root, "gibt-es-nicht")

        assertEquals(0, OrphanSpaceDirectories.sweep(missing, emptySet()))
    }

    /** Symlinks brauchen unter Windows ein Recht, das nicht jeder Account hat. Fehlt es, endet der Test ohne Fehler. */
    private fun linkOrSkip(
        link: Path,
        target: Path,
    ) {
        val created = runCatching { Files.createSymbolicLink(link, target) }
        Assume.assumeTrue("Symlinks sind auf diesem System nicht erlaubt.", created.isSuccess)
    }
}
