package moe.hexzii.earpick.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class Settings(
    val ssidPrefix: String = "zdeer earpick",
    val devTargetEnabled: Boolean = false,
    val devHost: String = "10.0.2.2",
    val devPort: Int = 40000,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::toSettings)

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { preferences ->
            val next = transform(toSettings(preferences))
            preferences[SSID_PREFIX] = next.ssidPrefix
            preferences[DEV_ENABLED] = next.devTargetEnabled
            preferences[DEV_HOST] = next.devHost
            preferences[DEV_PORT] = next.devPort
        }
    }

    private fun toSettings(preferences: Preferences) = Settings(
        ssidPrefix = preferences[SSID_PREFIX] ?: Settings().ssidPrefix,
        devTargetEnabled = preferences[DEV_ENABLED] ?: false,
        devHost = preferences[DEV_HOST] ?: Settings().devHost,
        devPort = preferences[DEV_PORT] ?: Settings().devPort,
    )

    private companion object {
        val SSID_PREFIX = stringPreferencesKey("ssid_prefix")
        val DEV_ENABLED = booleanPreferencesKey("dev_target_enabled")
        val DEV_HOST = stringPreferencesKey("dev_host")
        val DEV_PORT = intPreferencesKey("dev_port")
    }
}
