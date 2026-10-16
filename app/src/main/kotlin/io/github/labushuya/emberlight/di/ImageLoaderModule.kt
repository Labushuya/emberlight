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
import io.github.labushuya.emberlight.core.media.thumbnail.addMediaThumbnails
import javax.inject.Singleton

/**
 * Der eine ImageLoader der App. Speicher-Cache an, Disk-Cache aus: Kopien privater Vorschaubilder landen nicht im
 * App-Cache, das System cached MediaStore-Thumbnails ohnehin (ADR 0008). Kein Netzwerk-Modul.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
    ): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCachePolicy(CachePolicy.DISABLED)
            .components {
                addMediaThumbnails()
                // Videos unter API 29 kommen über den Frame-Decoder, ab API 29 liefert der ContentResolver die Vorschau.
                add(VideoFrameDecoder.Factory())
            }.build()
}
