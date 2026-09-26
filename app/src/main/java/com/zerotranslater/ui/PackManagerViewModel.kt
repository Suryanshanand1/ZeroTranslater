package com.zerotranslater.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.common.model.DownloadConditions
import com.zerotranslater.data.SettingsStore
import com.zerotranslater.engine.LanguagePair
import com.zerotranslater.engine.PackInfo
import com.zerotranslater.engine.TranslationError
import com.zerotranslater.engine.TranslationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackManagerUiState(
    val packs: List<PackInfo> = emptyList(),
    val isLoading: Boolean = true,
    /** Language whose download/delete is currently in flight, for a row spinner. */
    val busyLanguage: String? = null,
    val error: TranslationError? = null,
    val wifiOnlyDownloads: Boolean = false,
)

class PackManagerViewModel(application: Application) : ViewModel() {

    private val settingsStore = SettingsStore(application)

    @Volatile
    private var wifiOnly: Boolean = false

    private val manager = TranslationManager(
        downloadConditions = {
            if (wifiOnly) {
                DownloadConditions.Builder().requireWifi().build()
            } else {
                DownloadConditions.Builder().build()
            }
        },
    )

    private val _state = MutableStateFlow(PackManagerUiState())
    val state: StateFlow<PackManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { saved ->
                wifiOnly = saved.wifiOnlyDownloads
                _state.update { it.copy(wifiOnlyDownloads = saved.wifiOnlyDownloads) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val installed = manager.installedPacks()
                LanguagePair.supportedLanguages
                    .sortedBy { LanguagePair.displayName(it) }
                    .map { code ->
                        PackInfo(
                            language = code,
                            isDownloaded = code in installed,
                        )
                    }
            }.onSuccess { packs ->
                _state.update { it.copy(packs = packs, isLoading = false) }
            }.onFailure { cause ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = TranslationError.Unknown(cause.message ?: "unknown"),
                    )
                }
            }
        }
    }

    fun download(language: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(busyLanguage = language, error = null)
            }
            runCatching { manager.downloadPacks(listOf(language)) }
                .onSuccess { markDownloaded(language, downloaded = true) }
                .onFailure { cause ->
                    _state.update {
                        it.copy(
                            busyLanguage = null,
                            error = TranslationError.DownloadFailed(
                                listOf(language),
                                cause.message ?: "unknown",
                            ),
                        )
                    }
                }
        }
    }

    fun delete(language: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyLanguage = language, error = null) }
            runCatching { manager.deletePack(language) }
                .onSuccess { markDownloaded(language, downloaded = false) }
                .onFailure { cause ->
                    _state.update {
                        it.copy(
                            busyLanguage = null,
                            error = TranslationError.Unknown(cause.message ?: "unknown"),
                        )
                    }
                }
        }
    }

    private fun markDownloaded(language: String, downloaded: Boolean) {
        _state.update { current ->
            current.copy(
                packs = current.packs.map {
                    if (it.language == language) it.copy(isDownloaded = downloaded) else it
                },
                busyLanguage = null,
            )
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWifiOnlyDownloads(enabled) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        manager.close()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                PackManagerViewModel(app)
            }
        }
    }
}
