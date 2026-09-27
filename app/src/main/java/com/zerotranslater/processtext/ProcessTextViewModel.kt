package com.zerotranslater.processtext

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.common.model.DownloadConditions
import com.zerotranslater.data.Settings
import com.zerotranslater.data.SettingsStore
import com.zerotranslater.engine.LanguagePair
import com.zerotranslater.engine.MeaningRepository
import com.zerotranslater.engine.SingleWordDetector
import com.zerotranslater.engine.TranslateRequest
import com.zerotranslater.engine.TranslationError
import com.zerotranslater.engine.TranslationManager
import com.zerotranslater.engine.TranslationOutcome
import com.zerotranslater.engine.WordMeaning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProcessTextUiState(
    val originalText: String = "",
    val translation: String = "",
    val isBusy: Boolean = true,
    val truncated: Boolean = false,
    val error: TranslationError? = null,
    val meaning: WordMeaning? = null,
)

/**
 * Backs the system-wide selection overlay.
 *
 * The source language is always auto-detected: the user selected text inside some
 * other app and has no way to tell us what it is, so there is nothing to persist
 * and nothing to choose. The target is the user's saved preference.
 */
class ProcessTextViewModel(application: Application) : ViewModel() {

    private val settingsStore = SettingsStore(application)

    private val manager = TranslationManager(
        // The overlay is a one-shot action, so it uses whatever network is
        // available rather than consulting the Wi-Fi-only preference.
        downloadConditions = { DownloadConditions.Builder().build() },
    )

    private val meaningRepo = MeaningRepository(application)

    private val _state = MutableStateFlow(ProcessTextUiState())
    val state: StateFlow<ProcessTextUiState> = _state.asStateFlow()

    private var started = false

    fun start(originalText: String, truncated: Boolean) {
        // Guard against re-entrancy: this Activity is singleTop, so a second
        // selection reuses the instance and must replace, not append to, the work.
        if (started) return
        started = true

        _state.update { it.copy(originalText = originalText, truncated = truncated) }

        viewModelScope.launch {
            val target = runCatching { settingsStore.settings.first().targetLanguage }
                .getOrDefault(Settings.DEFAULT_TARGET)

            _state.update { it.copy(isBusy = true, error = null) }

            val outcome = manager.translate(
                TranslateRequest(
                    text = originalText,
                    sourceLanguage = LanguagePair.AUTO,
                    targetLanguage = target,
                ),
            )

            when (outcome) {
                is TranslationOutcome.Success -> _state.update {
                    it.copy(
                        translation = outcome.translatedText,
                        isBusy = false,
                        error = null,
                        // Re-derive truncation from what actually happened rather
                        // than trusting the caller's copy.
                        truncated = outcome.wasTruncated,
                    )
                }

                is TranslationOutcome.Failure -> _state.update {
                    it.copy(isBusy = false, translation = "", error = outcome.error)
                }
            }

            // Look up meaning for single words, even on failure (e.g. low-confidence
            // auto-detect), so the user still gets the definition.
            val originalTrimmed = originalText.trim()
            if (SingleWordDetector.isSingleWord(originalTrimmed)) {
                viewModelScope.launch {
                    val normalised = originalTrimmed.lowercase()
                    meaningRepo.lookup(normalised)?.let { meaning ->
                        _state.update { it.copy(meaning = meaning) }
                    }
                }
            } else {
                _state.update { it.copy(meaning = null) }
            }
        }
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
                ProcessTextViewModel(app)
            }
        }
    }
}
