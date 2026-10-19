package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Titelbilder im Space-Speicher (Paket 3.2). Die Bereinigung beim Serverstart darf sie nie entfernen, und das Löschen
 * des Space entfernt alles.
 */
class SpaceStorageCoversTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val hash = "c".repeat(64)
    private val otherHash = "d".repeat(64)

    private fun storage(): SpaceStorage = SpaceStorage(folder.newFolder("spaces"), SpaceId.generate())

    @Test
    fun coverPathUsesThePrefixAndRejectsBadHashes() {
        val storage = storage()
        assertEquals("cover-$hash", storage.coverPath(hash).fileName.toString())
        val rejected = runCatching { storage.coverPath("ABC") }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun deleteUnlistedKeepsCoversAndRemovesOnlyUnlistedMedia() {
        val storage = storage()
        val cover = storage.coverPath(hash).toFile().apply { writeBytes(byteArrayOf(1)) }
        val listedMedia = storage.contentPath(otherHash).toFile().apply { writeBytes(byteArrayOf(2)) }
        val orphan = storage.contentPath("e".repeat(64)).toFile().apply { writeBytes(byteArrayOf(3)) }

        val removed = storage.deleteUnlisted(setOf(otherHash))

        assertEquals(1, removed)
        assertTrue(cover.isFile)
        assertTrue(listedMedia.isFile)
        assertFalse(orphan.exists())
    }

    @Test
    fun deleteCoversExceptRemovesOnlyOtherCovers() {
        val storage = storage()
        val keep = storage.coverPath(hash).toFile().apply { writeBytes(byteArrayOf(1)) }
        val drop = storage.coverPath(otherHash).toFile().apply { writeBytes(byteArrayOf(2)) }
        val media = storage.contentPath("f".repeat(64)).toFile().apply { writeBytes(byteArrayOf(3)) }

        val removed = storage.deleteCoversExcept(setOf(hash))

        assertEquals(1, removed)
        assertTrue(keep.isFile)
        assertFalse(drop.exists())
        assertTrue(media.isFile)
    }

    @Test
    fun deleteEverythingRemovesTheSpaceDirectory() {
        val spacesDir = folder.newFolder("spaces")
        val id = SpaceId.generate()
        val storage = SpaceStorage(spacesDir, id)
        storage.coverPath(hash).toFile().writeBytes(byteArrayOf(1))
        storage.contentPath(otherHash).toFile().writeBytes(byteArrayOf(2))
        val directory = File(spacesDir, id.value)
        assertTrue(directory.isDirectory)

        storage.deleteEverything()

        assertFalse(directory.exists())
        // Ein zweiter Aufruf auf dem fehlenden Verzeichnis ist unkritisch.
        storage.deleteEverything()
    }
}
