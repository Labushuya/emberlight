package io.github.labushuya.emberlight.feature.space.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import java.io.File
import javax.inject.Singleton

/**
 * Verdrahtung der Spaces (Paket 3.2). Die Datenbank und der Speicher liegen unter `noBackupFilesDir/spaces`, wie beim
 * Server (ADR 0014). Eine Datenbank für die ganze App.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpacesModule {
    @Provides
    @Singleton
    fun provideSpacesDatabase(
        @ApplicationContext context: Context,
    ): SpacesDatabase = SpacesDatabase.open(context, spacesDirectory(context))

    @Provides
    @Singleton
    fun provideSpaceRepository(
        database: SpacesDatabase,
        @ApplicationContext context: Context,
    ): SpaceRepository = SpaceRepository(database, SpaceCoverStore(spacesDirectory(context)))

    private fun spacesDirectory(context: Context): File = File(context.noBackupFilesDir, SPACES_DIRECTORY)

    private const val SPACES_DIRECTORY = "spaces"
}
