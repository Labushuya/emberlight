package io.github.labushuya.emberlight.feature.gallery.timeline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Speichert die gewählte Zoomstufe der Zeitleiste, damit sie beim nächsten Start wieder gilt. */
internal interface ZoomLevelStore {
    /** Gespeicherte Stufe, bereits auf den gültigen Bereich begrenzt. Ohne Eintrag gilt die Standardstufe. */
    val level: Flow<Int>

    suspend fun setLevel(level: Int)
}

private val Context.zoomLevelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "timeline_zoom",
)

@Singleton
internal class DataStoreZoomLevelStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ZoomLevelStore {
        override val level: Flow<Int> =
            context.zoomLevelDataStore.data
                .catch { error ->
                    // Ein unlesbarer Speicher gilt als „nichts gespeichert“. Dann startet die Zeitleiste mit der Standardstufe.
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }.map { prefs -> ZoomLevels.clamp(prefs[ZOOM_LEVEL] ?: ZoomLevels.DEFAULT_LEVEL) }

        override suspend fun setLevel(level: Int) {
            context.zoomLevelDataStore.edit { it[ZOOM_LEVEL] = ZoomLevels.clamp(level) }
        }

        private companion object {
            val ZOOM_LEVEL = intPreferencesKey("zoom_level")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ZoomLevelModule {
    @Binds
    abstract fun bindZoomLevelStore(impl: DataStoreZoomLevelStore): ZoomLevelStore
}
