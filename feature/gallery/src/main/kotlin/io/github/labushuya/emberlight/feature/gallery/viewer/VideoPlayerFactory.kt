package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Erzeugt den Player des Viewers. Der Viewer nutzt genau einen Player (ADR 0010). Die Fabrik ist eine Schnittstelle, damit
 * Tests einen Player mit Aufzeichnung oder den `TestExoPlayerBuilder` einsetzen können.
 */
internal fun interface VideoPlayerFactory {
    fun create(): Player
}

/**
 * ExoPlayer mit Audio-Fokus und Pause beim Abziehen des Kopfhörers („becoming noisy“), beides über die Einstellungen des
 * Players. Der Player startet nicht von selbst: Die Wiedergabe beginnt erst, wenn der Nutzer den Abspiel-Knopf drückt.
 */
internal class ExoVideoPlayerFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : VideoPlayerFactory {
        override fun create(): Player =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // Die Fokusanfrage übernimmt der Player: Ein Anruf oder eine andere App pausiert das Video.
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ViewerPlayerModule {
    @Binds
    abstract fun bindVideoPlayerFactory(factory: ExoVideoPlayerFactory): VideoPlayerFactory
}
