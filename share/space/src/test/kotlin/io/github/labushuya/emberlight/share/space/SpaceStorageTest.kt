package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SpaceStorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sha = "a".repeat(64)

    private fun storage(): SpaceStorage = SpaceStorage(folder.newFolder("spaces"), SpaceId.generate())

    @Test
    fun simpleNamesResolveInsideTheSpaceDirectory() {
        val storage = storage()

        val path = storage.contentPath(sha)

        assertEquals(storage.resolveName(sha), path)
        assertEquals(sha, path.fileName.toString())
    }

    @Test
    fun parentReferencesAndSeparatorsAreRejected() {
        val storage = storage()

        listOf("..", "../evil", "a/b", "a\\b", ".hidden", "", " x", "x".repeat(200)).forEach { name ->
            assertThrows(name, UnsafeStoragePathException::class.java) { storage.resolveName(name) }
        }
    }

    @Test
    fun contentPathRequiresLowercaseSha256Hex() {
        val storage = storage()

        assertThrows(IllegalArgumentException::class.java) { storage.contentPath("A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { storage.contentPath("a".repeat(63)) }
    }

    @Test
    fun symlinkInsideSpaceIsRejected() {
        val storage = storage()
        val outside = folder.newFolder("outside").toPath()
        val link = storage.directoryPath().resolve("link")
        createSymlinkOrSkip(link, outside)

        assertThrows(UnsafeStoragePathException::class.java) { storage.resolveName("link") }
    }

    @Test
    fun symlinkAsStoredObjectIsRejectedAndNothingOutsideIsTouched() {
        val storage = storage()
        val outside = folder.newFile("secret.txt").toPath()
        Files.writeString(outside, "geheim")
        createSymlinkOrSkip(storage.directoryPath().resolve(sha), outside)

        assertThrows(UnsafeStoragePathException::class.java) { storage.contentPath(sha) }
        assertEquals("geheim", Files.readString(outside))
    }

    @Test
    fun symlinkedSpaceDirectoryIsRejectedAtConstruction() {
        val spaces = folder.newFolder("spaces-link").toPath()
        val target = folder.newFolder("elsewhere").toPath()
        val id = SpaceId.generate()
        createSymlinkOrSkip(spaces.resolve(id.value), target)

        assertThrows(UnsafeStoragePathException::class.java) { SpaceStorage(spaces.toFile(), id) }
    }

    @Test
    fun tempPathsAreRandomAndInsideTheDirectory() {
        val storage = storage()

        val first = storage.newTempPath()
        val second = storage.newTempPath()

        assertTrue(first.fileName.toString().startsWith(SpaceStorage.TEMP_PREFIX))
        assertFalse(first == second)
        assertEquals(storage.directoryPath(), first.parent)
    }

    @Test
    fun deleteUnlistedRemovesOnlyUnreferencedEntries() {
        val storage = storage()
        val kept = storage.contentPath(sha)
        Files.write(kept, byteArrayOf(1))
        Files.write(storage.newTempPath(), byteArrayOf(2))

        val removed = storage.deleteUnlisted(setOf(sha))

        assertEquals(1, removed)
        assertTrue(Files.exists(kept))
    }

    @Test
    fun moveReplacesOrphanAtomically() {
        val storage = storage()
        val temp = storage.newTempPath()
        Files.write(temp, byteArrayOf(1, 2, 3))
        Files.write(storage.contentPath(sha), byteArrayOf(9))

        storage.moveAtomically(temp, storage.contentPath(sha))

        assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(storage.contentPath(sha)).toList())
        assertFalse(Files.exists(temp))
    }

    private fun SpaceStorage.directoryPath(): Path = contentPath(sha).parent

    private fun createSymlinkOrSkip(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: Exception) {
            // Nur auf Windows braucht die Symlink-Anlage Rechte (Entwicklermodus oder Administrator). Dort wird der Test
            // übersprungen, wenn sie fehlen. Auf allen anderen Systemen (CI: Linux) ist die Anlage Pflicht. Ein Fehler
            // schlägt dort den Lauf fehl, statt den Test still zu überspringen.
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) throw failure
            assumeNoException("Symlink-Anlage auf Windows nicht erlaubt: ${failure.javaClass.simpleName}", failure)
        }
    }
}
