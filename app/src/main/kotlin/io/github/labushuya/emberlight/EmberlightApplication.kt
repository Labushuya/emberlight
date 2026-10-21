package io.github.labushuya.emberlight

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.labushuya.emberlight.feature.space.retention.RetentionWork
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

    /**
     * Der periodische Lauf der Aufbewahrung (Paket 3.6c, ADR 0021) wird einmal je Prozessstart eingeplant. Ist er schon
     * eingeplant, bleibt er unverändert. In Tests mit einem anderen Application-Typ läuft er nicht.
     */
    override fun onCreate() {
        super.onCreate()
        RetentionWork.schedule(this)
    }

