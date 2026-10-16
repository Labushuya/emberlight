package io.github.labushuya.emberlight.di

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.video.VideoFrameDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.labushuya.emberlight.core.media.thumbnail.addMediaThumbnails
import io.github.labushuya.emberlight.extensions.CoilComponents
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerImageSourceContribution
import javax.inject.Singleton

/**
 * Der eine ImageLoader der App. Speicher-Cache an, Disk-Cache aus: Kopien privater Vorschaubilder landen nicht im
 * App-Cache, das System cached MediaStore-Thumbnails ohnehin (ADR 0008). Kein Netzwerk-Modul.
 *
 * Zusätzliche Komponenten stehen in [CoilComponents]. Sie kommen vor den Medien-Thumbnails, damit sie zuerst gefragt werden.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        contributions: Set<@JvmSuppressWildcards CoilComponents>,
    ): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCachePolicy(CachePolicy.DISABLED)
            .components {
                contributions.forEach { it.contribute(this) }
                addMediaThumbnails()
                // Videos unter API 29 kommen über den Frame-Decoder, ab API 29 liefert der ContentResolver die Vorschau.
                add(VideoFrameDecoder.Factory())
            }.build()
}

/** Leere Mehrfachbindung für zusätzliche Coil-Komponenten. Ohne Beitrag (Release) bleibt die Menge leer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoilComponentsModule {
    @Multibinds
    abstract fun coilComponents(): Set<CoilComponents>
}

/** Leere Mehrfachbindung für Start-Intent-Beiträge. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LaunchIntentModule {
    @Multibinds
    abstract fun launchIntentHandlers(): Set<io.github.labushuya.emberlight.extensions.LaunchIntentHandler>
}

/** Leere Mehrfachbindung für Overlays über der Oberfläche. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppOverlayModule {
    @Multibinds
    abstract fun appOverlays(): Set<io.github.labushuya.emberlight.extensions.AppOverlay>
}

/** Leere Mehrfachbindung für Bildquellen des Vollbild-Viewers. Ohne Beitrag (Release) gilt die Standardquelle. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ViewerImageSourceModule {
    @Multibinds
    abstract fun viewerImageSources(): Set<ViewerImageSourceContribution>
}
