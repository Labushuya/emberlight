package io.github.labushuya.emberlight.core.media.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Leere Mehrfachbindung für alternative Medienquellen. Ohne Beitrag (Release) ist die Menge leer.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlternativeSourcesModule {
    @Multibinds
    abstract fun alternativeSources(): Set<AlternativeMediaSource>
}
