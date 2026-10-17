package io.github.labushuya.emberlight.feature.gallery.permission

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

/**
 * Merkt sich nur, dass der Nutzer die Medienberechtigung schon einmal angefragt hat. Der Berechtigungsstatus selbst
 * wird nicht gespeichert, den liest die App bei jeder Abfrage frisch. Die Unterscheidung „Dialog“ oder „nur noch
 * Einstellungen“ hängt an diesem Merkmal (ADR 0008).
 */
internal interface PermissionPromptStore {
    /** `true`, sobald eine Anfrage für Medien abgeschlossen wurde. */
    val askedBefore: Flow<Boolean>

    suspend fun markAsked()
}

private val Context.permissionPromptDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "permission_prompt",
)

@Singleton
internal class DataStorePermissionPromptStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PermissionPromptStore {
        override val askedBefore: Flow<Boolean> =
            context.permissionPromptDataStore.data
                .catch { error ->
                    // Ein unlesbarer Speicher gilt als „noch nie gefragt“. Dann fragt die App eher erneut, statt zu blockieren.
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }.map { it[MEDIA_ASKED] == true }

        override suspend fun markAsked() {
            context.permissionPromptDataStore.edit { it[MEDIA_ASKED] = true }
        }

        private companion object {
            val MEDIA_ASKED = booleanPreferencesKey("media_permission_asked")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PermissionPromptModule {
    @Binds
    abstract fun bindPermissionPromptStore(impl: DataStorePermissionPromptStore): PermissionPromptStore
}
