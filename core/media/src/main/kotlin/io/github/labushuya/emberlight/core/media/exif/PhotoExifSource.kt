package io.github.labushuya.emberlight.core.media.exif

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.core.media.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liest die EXIF-Angaben eines Fotos. Der Aufruf geschieht erst, wenn der Infobereich geöffnet wird (lazy, ADR 0010). Das
 * Ergebnis ist `null`, wenn nichts zu lesen ist oder die Datei nicht lesbar ist. Fehler werden still behandelt.
 */
interface PhotoExifSource {
    /** EXIF-Angaben des Fotos mit der Content-URI [uri], `null` wenn keine lesbar sind. */
    suspend fun read(uri: String): PhotoExif?
}

/**
 * Liest EXIF über `ContentResolver.openInputStream` auf dem IO-Dispatcher. Es entsteht keine Kopie der Datei. Die Ergebnisse
 * bleiben im Speicher, begrenzt auf [MAX_CACHED] Einträge (am längsten ungenutzte fallen zuerst heraus). Auch leere Ergebnisse
 * werden gemerkt, damit eine unlesbare Datei nicht bei jedem Öffnen erneut gelesen wird.
 */
@Singleton
internal class ContentResolverPhotoExifSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : PhotoExifSource {
        private val cache =
            object : LinkedHashMap<String, CachedExif>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedExif>?): Boolean = size > MAX_CACHED
            }

        override suspend fun read(uri: String): PhotoExif? {
            synchronized(cache) { cache[uri] }?.let { return it.value }
            val exif = withContext(io) { readOrNull(uri) }
            synchronized(cache) { cache[uri] = CachedExif(exif) }
            return exif
        }

        private fun readOrNull(uri: String): PhotoExif? =
            try {
                context.contentResolver
                    .openInputStream(Uri.parse(uri))
                    ?.use { ExifInterface(it).toPhotoExif() }
                    ?.takeUnless { it.isEmpty }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private class CachedExif(
            val value: PhotoExif?,
        )

        internal companion object {
            /** Höchstens so viele Einträge im Speicher (Obergrenze, ADR 0010). */
            const val MAX_CACHED = 64
            private const val INITIAL_CAPACITY = 16
            private const val LOAD_FACTOR = 0.75f
        }
    }
