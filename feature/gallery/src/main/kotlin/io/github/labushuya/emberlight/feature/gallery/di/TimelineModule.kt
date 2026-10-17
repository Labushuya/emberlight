package io.github.labushuya.emberlight.feature.gallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.ZoneId
import javax.inject.Qualifier

/** Kennzeichnet den Dispatcher für CPU-lastige Arbeit wie die Gruppierung der Zeitleiste. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher

/**
 * Uhr, Zeitzone und Rechen-Dispatcher der Galerie. Tests ersetzen dieses Modul durch feste Werte, damit „Heute“
 * und „Gestern“ deterministisch sind.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimelineModule {
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun provideZoneId(): ZoneId = ZoneId.systemDefault()

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
