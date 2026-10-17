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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zoomstufe des Rasters. Galerie und Album teilen sie: Ein Wechsel in einer Ansicht gilt sofort auch in der anderen
 * (ADR 0009). Der Wert wird gespeichert und beim nächsten Start wieder hergestellt.
 */
internal interface ZoomLevelStore {
    /** Aktuelle Stufe, begrenzt auf den gültigen Bereich. Ohne Eintrag gilt die Standardstufe. */
    val level: Flow<Int>

    suspend fun setLevel(level: Int)
}

/**
 * Gewählte Stufe hat Vorrang vor der gespeicherten. So überschreibt eine späte Antwort des Speichers keine Nutzerwahl,
 * die schon getroffen wurde.
 */
internal fun resolveZoomLevel(
    chosen: Int?,
    saved: Int?,
): Int = ZoomLevels.clamp(chosen ?: saved ?: ZoomLevels.DEFAULT_LEVEL)

private val Context.zoomLevelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "timeline_zoom",
)

/**
 * Singleton: Alle Ansichten sehen dieselbe Nutzerwahl. Die Wahl liegt im Speicher des Singletons und wird zusätzlich in
 * DataStore geschrieben.
 */
@Singleton
internal class DataStoreZoomLevelStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ZoomLevelStore {
        private val chosen = MutableStateFlow<Int?>(null)

        private val saved: Flow<Int?> =
            context.zoomLevelDataStore.data
                .catch { error ->
                    // Ein unlesbarer Speicher gilt als „nichts gespeichert“. Dann startet die Zeitleiste mit der Standardstufe.
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }.map { prefs -> prefs[ZOOM_LEVEL] }

        override val level: Flow<Int> = combine(chosen, saved) { choice, stored -> resolveZoomLevel(choice, stored) }

        override suspend fun setLevel(level: Int) {
            val clamped = ZoomLevels.clamp(level)
            chosen.value = clamped
            context.zoomLevelDataStore.edit { it[ZOOM_LEVEL] = clamped }
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
