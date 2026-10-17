package io.github.labushuya.emberlight

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class EmberlightApplication :
    Application(),
    SingletonImageLoader.Factory {
    /** Provider statt direkter Instanz: Coil fragt den Loader erst beim ersten Bild, nach Hilts Injektion. */
    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()
}
