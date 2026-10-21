package io.github.labushuya.emberlight.feature.space.di

import android.content.Context
import android.os.StatFs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.feature.space.setup.SetupStore
import io.github.labushuya.emberlight.share.server.ConnectivityWifiNetworkProvider
import io.github.labushuya.emberlight.share.server.WifiNetworkProvider
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.participants.AndroidKeyStoreInviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.SystemRunClock
import java.io.File
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Verdrahtung der Spaces (Paket 3.2, Paket 3.3). Die Datenbank und der Speicher liegen unter `noBackupFilesDir/spaces`,
 * wie beim Server (ADR 0014). Die Datenbank ist prozessweit eine Instanz (ADR 0016): Die Oberfläche und der Dienst
 * greifen über [SpacesDatabase.shared] auf dieselbe Datei zu.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpacesModule {
    @Provides
    @Singleton
    fun provideSpacesDatabase(
        @ApplicationContext context: Context,
    ): SpacesDatabase = SpacesDatabase.shared(context)

    /**
     * Die eine Vault je Space im Prozess (Paket 3.5c). Der Server und die Oberfläche bekommen dieselbe Instanz, damit
     * Commit-Lock und Vorschau-Worker nur einmal existieren.
     */
    @Provides
    @Singleton
    fun provideSpaceVaults(
        @ApplicationContext context: Context,
    ): SpaceVaults = SpaceVaults.shared(context)

    // Verschlüsselung des Einladungsgeheimnisses mit dem AndroidKeyStore (ADR 0018).
    @Provides
    @Singleton
    fun provideInviteSecretCipher(): InviteSecretCipher = AndroidKeyStoreInviteSecretCipher()

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

    @Provides
    @Singleton
    fun provideSpaceRunRepository(
        database: SpacesDatabase,
        clock: RunClock,
    ): SpaceRunRepository = SpaceRunRepository(database, clock)

    @Provides
    fun provideRunClock(): RunClock = SystemRunClock

    @Provides
    @Singleton
    fun provideWifiNetworkProvider(
        @ApplicationContext context: Context,
    ): WifiNetworkProvider = ConnectivityWifiNetworkProvider(context)

    @Provides
    @Singleton
    fun provideSetupStore(
        @ApplicationContext context: Context,
    ): SetupStore = SetupStore(context)

    /** Zufallsquelle für Namensvorschläge. Tests bekommen eine feste Quelle über den Konstruktor des ViewModels. */
    @Provides
    fun provideRandom(): Random = Random.Default

    private fun spacesDirectory(context: Context): File = SpacesDatabase.spacesDirectory(context)
}
