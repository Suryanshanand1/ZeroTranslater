package com.zerotranslater.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zerotranslater.engine.LanguagePair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class Settings(
    val sourceLanguage: String = LanguagePair.AUTO,
    val targetLanguage: String = DEFAULT_TARGET,
    val wifiOnlyDownloads: Boolean = false,
    /**
     * Whether the user wants the floating Quick-Translate pill. Defaults to false:
     * it draws over other apps, so it must be an explicit choice rather than
     * something the app turns on for you.
     */
    val quickTranslateEnabled: Boolean = false,
    /**
     * Whether to fall back to an online dictionary API when the local WordNet
     * dataset has no entry for a word. Default true: most devices have at least
     * intermittent connectivity, and the API fills gaps in the offline dataset.
     */
    val onlineMeaningFallback: Boolean = true,
) {
    companion object {
        /**
         * English is the ML Kit pivot language, so it is the one target guaranteed
         * to need a single pack on first run. A sensible default that also keeps
         * the first translation cheap.
         */
        const val DEFAULT_TARGET: String = "en"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val SOURCE = stringPreferencesKey("source_language")
        val TARGET = stringPreferencesKey("target_language")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val QUICK_TRANSLATE = booleanPreferencesKey("quick_translate_enabled")
        val ONLINE_MEANING = booleanPreferencesKey("online_meaning_fallback")
    }

    val settings: Flow<Settings> = context.dataStore.data
        .catch { throwable ->
            // A corrupt preferences file must not crash the app on launch.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            Settings(
                sourceLanguage = prefs[Keys.SOURCE] ?: LanguagePair.AUTO,
                targetLanguage = prefs[Keys.TARGET] ?: Settings.DEFAULT_TARGET,
                wifiOnlyDownloads = prefs[Keys.WIFI_ONLY] ?: false,
                quickTranslateEnabled = prefs[Keys.QUICK_TRANSLATE] ?: false,
                onlineMeaningFallback = prefs[Keys.ONLINE_MEANING] ?: true,
            )
        }

    suspend fun setSourceLanguage(code: String) {
        context.dataStore.edit { it[Keys.SOURCE] = code }
    }

    suspend fun setTargetLanguage(code: String) {
        context.dataStore.edit { it[Keys.TARGET] = code }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setQuickTranslateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.QUICK_TRANSLATE] = enabled }
    }

    suspend fun setOnlineMeaningFallback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ONLINE_MEANING] = enabled }
    }
}
