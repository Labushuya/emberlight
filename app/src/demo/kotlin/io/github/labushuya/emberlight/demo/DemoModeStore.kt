package io.github.labushuya.emberlight.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoModeDataStore: DataStore<Preferences> by preferencesDataStore(name = "demo_mode")

/**
 * Der Demo-Schalter. Er bleibt bis zum Ausschalten gespeichert. Gesetzt wird er über den Start-Intent
 * `--ez emberlight.demo true|false` (ADR 0011).
 */
@Singleton
internal class DemoModeStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** `true`, solange der Demo-Modus aktiv ist. Ein unlesbarer Speicher gilt als aus. */
        val enabled: Flow<Boolean> =
            context.demoModeDataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .map { prefs -> prefs[KEY] == true }

        fun setEnabled(value: Boolean) {
            writes.launch { context.demoModeDataStore.edit { it[KEY] = value } }
        }

        private companion object {
            val KEY = booleanPreferencesKey("demo_mode_enabled")
        }
    }
