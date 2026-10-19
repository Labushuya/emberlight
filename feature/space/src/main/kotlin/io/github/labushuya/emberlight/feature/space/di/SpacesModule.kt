package io.github.labushuya.emberlight.feature.space.di

import android.content.Context
import android.os.StatFs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import java.io.File
import javax.inject.Singleton
import kotlin.random.Random

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

    /**
     * Freier Speicher des Geräts, für die harte Obergrenze des Speicherlimits. Ist er nicht lesbar, bleibt es bei `0`:
     * dann lässt sich kein Space anlegen, und das Formular sagt es. Das ist der sichere Fehlerfall.
     */
    @Provides
    @Singleton
    fun provideStorageProbe(
        @ApplicationContext context: Context,
    ): StorageProbe =
        StorageProbe {
            runCatching { StatFs(context.noBackupFilesDir.path).availableBytes }.getOrDefault(0L)
        }

    @Provides
    @Singleton
    fun provideSpaceRepository(
        database: SpacesDatabase,
        @ApplicationContext context: Context,
        storageProbe: StorageProbe,
    ): SpaceRepository =
        SpaceRepository(
            database = database,
            covers = SpaceCoverStore(spacesDirectory(context)),
            spacesDir = spacesDirectory(context),
            freeSpace = storageProbe,
        )

    /** Zufallsquelle für Namensvorschläge. Tests bekommen eine feste Quelle über den Konstruktor des ViewModels. */
    @Provides
    fun provideRandom(): Random = Random.Default

    private fun spacesDirectory(context: Context): File = File(context.noBackupFilesDir, SPACES_DIRECTORY)

    private const val SPACES_DIRECTORY = "spaces"
}
