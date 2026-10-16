package io.github.labushuya.emberlight.core.media.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.log.LogcatMediaLogger
import io.github.labushuya.emberlight.core.media.log.MediaLogger
import io.github.labushuya.emberlight.core.media.permission.ContextMediaPermissionChecker
import io.github.labushuya.emberlight.core.media.permission.MediaPermissionChecker
import io.github.labushuya.emberlight.core.media.store.ContentResolverMediaStoreGateway
import io.github.labushuya.emberlight.core.media.store.MediaStoreGateway
import io.github.labushuya.emberlight.core.media.store.MediaStoreMediaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Bindungen für den Medienzugriff. Alle Implementierungen sind Singletons über ihre Klassen-Annotation. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaModule {
    @Binds
    abstract fun bindMediaRepository(impl: MediaStoreMediaRepository): MediaRepository

    @Binds
    abstract fun bindMediaStoreGateway(impl: ContentResolverMediaStoreGateway): MediaStoreGateway

    @Binds
    abstract fun bindMediaPermissionChecker(impl: ContextMediaPermissionChecker): MediaPermissionChecker

    @Binds
    abstract fun bindMediaLogger(impl: LogcatMediaLogger): MediaLogger
}

/** Stellt den IO-Dispatcher bereit. Tests ersetzen ihn über den Qualifier, siehe [IoDispatcher]. */
@Module
@InstallIn(SingletonComponent::class)
internal object DispatcherModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
