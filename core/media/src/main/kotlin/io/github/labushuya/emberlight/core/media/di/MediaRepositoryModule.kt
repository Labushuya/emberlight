package io.github.labushuya.emberlight.core.media.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.core.media.MediaRepository
import javax.inject.Singleton

/**
 * Öffentliche Bindung von [MediaRepository]. Ersetzbar in Tests über `@TestInstallIn(replaces = [MediaRepositoryModule::class])`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaRepositoryModule {
    @Provides
    @Singleton
    fun provideMediaRepository(
        @MediaStoreSource repository: MediaRepository,
    ): MediaRepository = repository
}
