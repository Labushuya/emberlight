package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.lifecycle.ViewModel
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.labushuya.emberlight.core.media.exif.PhotoExif
import io.github.labushuya.emberlight.core.media.exif.PhotoExifSource
import javax.inject.Inject

/**
 * Erweiterungsstelle der EXIF-Angaben des Infobereichs (ADR 0010). Ein Beitrag liefert für ein Element eigene Angaben, etwa
 * synthetische Werte im Demo-Modus. `null` heißt: dieser Beitrag hat nichts für das Element. Dann fragt die Galerie den
 * MediaStore-Leser. Im Release-Build gibt es keine Beiträge.
 */
interface ViewerDetailsContribution {
    /** EXIF-Angaben für [item] oder `null`, wenn dieser Beitrag für das Element nichts liefert. */
    fun exifFor(item: ViewerItem): PhotoExif?
}

/** Liefert die EXIF-Angaben eines Elements für den Infobereich. Lazy: nur wenn der Infobereich offen ist. */
interface ExifLookup {
    /** EXIF-Angaben von [item], `null` bei Videos, bei fehlenden Angaben oder bei Lesefehlern. */
    suspend fun exifOf(item: ViewerItem): PhotoExif?

    /** Ohne Quelle: nie EXIF. Vorgabe für Aufrufer ohne Hilt, etwa Tests. */
    companion object {
        val None: ExifLookup =
            object : ExifLookup {
                override suspend fun exifOf(item: ViewerItem): PhotoExif? = null
            }
    }
}

/**
 * Holt die EXIF-Angaben für den Infobereich. Die Beiträge gehen zuerst, dann der Leser für Fotos. Videos haben keine EXIF-Angaben.
 * Die Galerie-Ziele halten den Halter, damit er Konfigurationswechsel übersteht (wie der Wiedergabe-Halter).
 */
@HiltViewModel
class ViewerDetailsViewModel
    @Inject
    constructor(
        private val exifSource: PhotoExifSource,
        private val contributions: Set<@JvmSuppressWildcards ViewerDetailsContribution>,
    ) : ViewModel(),
        ExifLookup {
        override suspend fun exifOf(item: ViewerItem): PhotoExif? {
            if (item.type != ViewerMediaType.IMAGE) return null
            for (contribution in contributions) {
                contribution.exifFor(item)?.let { return it }
            }
            return exifSource.read(item.uri)
        }
    }

/** Leere Mehrfachbindung für EXIF-Beiträge. Ohne Beitrag (Release) liest die Galerie nur den MediaStore. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ViewerDetailsModule {
    @Multibinds
    abstract fun viewerDetailsContributions(): Set<ViewerDetailsContribution>
}
