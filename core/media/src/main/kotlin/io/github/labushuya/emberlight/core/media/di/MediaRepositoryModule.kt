package io.github.labushuya.emberlight.core.media.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.source.selectMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Öffentliche Bindung von [MediaRepository]. Ersetzbar in Tests über `@TestInstallIn(replaces = [MediaRepositoryModule::class])`.
 *
 * Ohne alternative Quelle (Release) ist das der MediaStore. Liefert ein Entwicklungs- oder Mess-Build eine alternative Quelle
 * über [AlternativeMediaSource], wechselt der Index zur Laufzeit zwischen beiden (ADR 0011).
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaRepositoryModule {
    @Provides
    @Singleton
    fun provideMediaRepository(
        @MediaStoreSource repository: MediaRepository,
        alternatives: Set<@JvmSuppressWildcards AlternativeMediaSource>,
    ): MediaRepository =
        selectMediaRepository(
            repository,
            alternatives,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
