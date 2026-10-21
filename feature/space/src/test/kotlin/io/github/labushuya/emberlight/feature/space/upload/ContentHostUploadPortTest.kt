package io.github.labushuya.emberlight.feature.space.upload

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.run.FakeInviteSecretCipher
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.participants.Secrets
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Upload aus der App über den Inhaltsresolver (Paket 3.5c): Der Stream geht an die Vault, Uploader ist der Gastgeber,
 * Dedupe gilt wie beim HTTP-Upload, unlesbare Adressen werden gemeldet. Die Zustandsprüfung des laufenden Space liegt
 * in [HostUploadRules] und ist dort getestet.
 */
@RunWith(RobolectricTestRunner::class)
class ContentHostUploadPortTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val space = SpaceId.generate()
    private val cipher = FakeInviteSecretCipher()
    private val hostId = ParticipantId.generate()
    private lateinit var database: SpacesDatabase
    private lateinit var vaults: SpaceVaults
    private lateinit var port: ContentHostUploadPort

    @Before
    fun setUp() {
        val spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        vaults = SpaceVaults.create(database, spacesDir)
        val clock =
            object : RunClock {
                override fun wallMillis(): Long = NOW

                override fun elapsedMillis(): Long = NOW

                override fun uptimeMillis(): Long = NOW
            }
        port = ContentHostUploadPort(context, vaults, cipher, SpaceRunRepository(database, clock), clock)
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = space.value,
                    name = "Test",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = null,
                    lifetimeUntil = null,
                    retentionDays = 7,
                    storageLimitBytes = 1_000_000_000L,
                    status = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            vaults.vaultOf(space, cipher)!!.registerParticipant(hostId, "Gastgeber", Role.HOST, Secrets.hashHex("h"))
        }
    }

    @After
    fun tearDown() {
        vaults.close()
        database.close()
    }

    /**
     * Registriert einen Stream für [uri]. Mit [name] meldet der Anbieter Name und Größe wie das System. Ohne [name] fehlt die
     * Abfrage, der Upload nimmt den Ersatznamen und kennt die Größe nicht.
     */
    private fun register(
        uri: String,
        bytes: ByteArray,
        name: String? = null,
    ): Uri {
        val parsed = Uri.parse(uri)
        Shadows.shadowOf(context.contentResolver).registerInputStream(parsed, ByteArrayInputStream(bytes))
        if (name != null) {
            ShadowContentResolver.registerProviderInternal(parsed.authority!!, NamedProvider(name, bytes.size.toLong()))
        }
        return parsed
    }

    /** Anbieter, der Name und Größe nennt, wie der Dateiauswahl-Anbieter des Systems. */
    private class NamedProvider(
        private val name: String,
        private val size: Long,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf<String>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>(name, size))
            }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private fun jpeg(size: Int = 200): ByteArray {
        val comLength = size - 6
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte()) +
            byteArrayOf((comLength shr 8).toByte(), comLength.toByte()) +
            ByteArray(size - 8) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun theStreamIsStoredOnBehalfOfTheHostWithItsName() {
        val bytes = jpeg()
        register("content://test/bild-1", bytes, name = "Urlaub.jpg")

        val outcome = runBlocking { port.upload(space, "content://test/bild-1", keepLocation = false) {} }

        val created = (outcome as PickedUploadOutcome.Ingested).result as IngestResult.Created
        assertEquals(hostId, created.record.uploaderId)
        assertEquals(bytes.size.toLong(), created.record.sizeBytes)
        assertEquals("Urlaub.jpg", created.record.originalName)
    }

    @Test
    fun anUploadOfTheSameBytesAgainIsADuplicate() {
        val bytes = jpeg()
        register("content://test/bild-a", bytes)
        register("content://test/bild-b", bytes)
        runBlocking { port.upload(space, "content://test/bild-a", keepLocation = false) {} }

        val second = runBlocking { port.upload(space, "content://test/bild-b", keepLocation = false) {} }

        assertTrue((second as PickedUploadOutcome.Ingested).result is IngestResult.Duplicate)
    }

    @Test
    fun progressCountsTheBytesReadAndIsUndeterminedWithoutASize() {
        val bytes = jpeg(300)
        register("content://test/ohne-groesse", bytes)
        val reported = mutableListOf<UploadProgress>()

        runBlocking { port.upload(space, "content://test/ohne-groesse", keepLocation = false) { reported += it } }

        assertTrue(reported.isNotEmpty())
        assertEquals(bytes.size.toLong(), reported.last().readBytes)
        assertNull(reported.last().totalBytes)
    }

    @Test
    fun progressKnowsTheTotalWhenTheSystemNamesTheSize() {
        val bytes = jpeg(300)
        register("content://test/mit-groesse", bytes, name = "gross.jpg")
        val reported = mutableListOf<UploadProgress>()

        runBlocking { port.upload(space, "content://test/mit-groesse", keepLocation = false) { reported += it } }

        assertEquals(bytes.size.toLong(), reported.last().totalBytes)
    }

    @Test
    fun aDescribedFileKeepsItsSizeAndName() {
        register("content://test/beschrieben", jpeg(120), name = "Wanderung.jpg")

        val described = runBlocking { port.describe("content://test/beschrieben") }

        assertEquals("Wanderung.jpg", described.name)
        assertEquals(120L, described.sizeBytes)
    }

    @Test
    fun anAddressWithoutAnOpenStreamIsUnreadable() {
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse("content://test/fehlt")) {
            throw FileNotFoundException("Entfernt.")
        }

        val outcome = runBlocking { port.upload(space, "content://test/fehlt", keepLocation = false) {} }

        assertEquals(PickedUploadOutcome.Unreadable, outcome)
    }

    @Test
    fun aReadThatFailsMidwayIsUnreadableNotACrash() {
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            Uri.parse("content://test/abbruch"),
            object : InputStream() {
                override fun read(): Int = throw IOException("Zugriff entzogen.")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw IOException("Zugriff entzogen.")
            },
        )

        val outcome = runBlocking { port.upload(space, "content://test/abbruch", keepLocation = false) {} }

        assertEquals(PickedUploadOutcome.Unreadable, outcome)
    }

    @Test
    fun anUnknownSpaceIsUnreadableInsteadOfCrashing() {
        register("content://test/fremd", jpeg())

        val outcome = runBlocking { port.upload(SpaceId.generate(), "content://test/fremd", keepLocation = false) {} }

        assertEquals(PickedUploadOutcome.Unreadable, outcome)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
