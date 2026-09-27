package com.zerotranslater.ui

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
import com.zerotranslater.quicktranslate.QuickTranslateService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslateUiState(
    val sourceText: String = "",
    val sourceLanguage: String = LanguagePair.AUTO,
    val targetLanguage: String = Settings.DEFAULT_TARGET,
    val output: String = "",
    /** Concrete source after auto-detection; null while still unknown. */
    val resolvedSource: String? = null,
    val isBusy: Boolean = false,
    val error: TranslationError? = null,
    val inputWasTruncated: Boolean = false,
    /** Packs this translation needs, or null while the source is still auto-detect. */
    val packsNeeded: Int? = null,
    val routedViaEnglish: Boolean = false,
    val missingPacks: List<String> = emptyList(),
    val isDownloadingPacks: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val quickTranslateEnabled: Boolean = false,
    /** Meaning of the current word, if it is a single word and was found. */
    val meaning: WordMeaning? = null,
    val onlineFallbackEnabled: Boolean = false,
) {
    val charCount: Int get() = sourceText.length
    val canTranslate: Boolean get() = sourceText.isNotBlank() && !isBusy
    val hasOutput: Boolean get() = output.isNotBlank()
}

class TranslateViewModel(application: Application) : ViewModel() {

    private val app: Application = application
    private val settingsStore = SettingsStore(application)
    private var wifiOnly = false
    private val manager = TranslationManager(
        downloadConditions = {
            if (wifiOnly) {
                DownloadConditions.Builder().requireWifi().build()
            } else {
                DownloadConditions.Builder().build()
            }
        },
    )
    private val meaningRepo = MeaningRepository(app)

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var translateJob: Job? = null
    private var meaningJob: Job? = null
    private var translationEpoch = 0

    private fun cancelInFlight() {
        translationEpoch++
        translateJob?.cancel()
        meaningJob?.cancel()
        _state.update { it.copy(isBusy = false) }
    }

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { saved ->
                wifiOnly = saved.wifiOnlyDownloads
                meaningRepo.onlineFallbackEnabled = saved.onlineMeaningFallback
                _state.update { current ->
                    current.withLanguages(
                        source = saved.sourceLanguage,
                        target = saved.targetLanguage,
                        wifiOnly = saved.wifiOnlyDownloads,
                    ).copy(
                        quickTranslateEnabled = saved.quickTranslateEnabled,
                        onlineFallbackEnabled = saved.onlineMeaningFallback,
                    )
                }
                if (saved.quickTranslateEnabled) {
                    QuickTranslateService.start(app)
                }
            }
        }
    }

    // ------------------------------------------------------------------- input

    fun onSourceTextChange(value: String) {
        _state.update { it.copy(sourceText = value, error = null, meaning = null) }
        cancelInFlight()
        translateJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runTranslation()
        }
    }

    /** Explicit trigger from the IME action key or the Translate button. */
    fun translateNow() {
        cancelInFlight()
        translateJob = viewModelScope.launch { runTranslation() }
    }

    fun onSourceLanguageChange(code: String) {
        cancelInFlight()
        _state.update {
            it.copy(
                sourceLanguage = code,
                output = "",
                error = null,
                resolvedSource = null,
                inputWasTruncated = false,
                meaning = null,
            )
        }
        viewModelScope.launch { settingsStore.setSourceLanguage(code) }
    }

    fun onTargetLanguageChange(code: String) {
        cancelInFlight()
        _state.update {
            it.copy(
                targetLanguage = code,
                output = "",
                error = null,
                inputWasTruncated = false,
                meaning = null,
            )
        }
        viewModelScope.launch { settingsStore.setTargetLanguage(code) }
    }

    fun swapLanguages() {
        val current = _state.value
        val concreteSource = current.resolvedSource
            ?: current.sourceLanguage.takeIf { it != LanguagePair.AUTO }

        if (concreteSource == null) {
            onSourceLanguageChange(current.targetLanguage)
            onTargetLanguageChange(Settings.DEFAULT_TARGET)
        } else {
            onSourceLanguageChange(current.targetLanguage)
            onTargetLanguageChange(concreteSource)
        }
        clearOutput()
    }

    fun clearInput() {
        cancelInFlight()
        _state.update {
            it.copy(
                sourceText = "",
                output = "",
                error = null,
                resolvedSource = null,
                inputWasTruncated = false,
                missingPacks = emptyList(),
                isBusy = false,
                meaning = null,
            )
        }
    }

    private fun clearOutput() {
        _state.update {
            it.copy(output = "", error = null, resolvedSource = null, inputWasTruncated = false, meaning = null)
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWifiOnlyDownloads(enabled) }
    }

    fun setQuickTranslateEnabled(enabled: Boolean) {
        if (enabled) {
            if (!QuickTranslateService.start(app)) return
        } else {
            QuickTranslateService.stop(app)
        }
        viewModelScope.launch { settingsStore.setQuickTranslateEnabled(enabled) }
    }

    fun setOnlineMeaningFallback(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setOnlineMeaningFallback(enabled)
            meaningRepo.onlineFallbackEnabled = enabled
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ engine

    private suspend fun runTranslation() {
        val epoch = ++translationEpoch
        val current = _state.value
        if (current.sourceText.isBlank()) {
            _state.update { it.copy(output = "", error = null, isBusy = false) }
            return
        }
        _state.update { it.copy(isBusy = true, error = null) }

        val outcome = manager.translate(
            TranslateRequest(
                text = current.sourceText,
                sourceLanguage = current.sourceLanguage,
                targetLanguage = current.targetLanguage,
            ),
        )

        if (epoch != translationEpoch) return

        when (outcome) {
            is TranslationOutcome.Success -> _state.update {
                it.copy(
                    output = outcome.translatedText,
                    resolvedSource = outcome.resolvedSource,
                    isBusy = false,
                    error = null,
                    inputWasTruncated = outcome.wasTruncated,
                    missingPacks = emptyList(),
                ).recomputePackHint()
            }

            is TranslationOutcome.Failure -> _state.update {
                it.copy(
                    isBusy = false,
                    output = "",
                    error = outcome.error,
                    missingPacks = (outcome.error as? TranslationError.ModelNotDownloaded)
                        ?.missing
                        .orEmpty(),
                )
            }
        }

        // Look up meaning for single words, regardless of translation success/failure.
        // This lets users see the definition even when auto-detect gives low confidence.
        val textToLookup = current.sourceText.trim()
        if (SingleWordDetector.isSingleWord(textToLookup)) {
            meaningJob = viewModelScope.launch {
                val normalised = textToLookup.lowercase()
                meaningRepo.lookup(normalised)?.let { meaning ->
                    if (epoch == translationEpoch) {
                        _state.update { current.copy(meaning = meaning) }
                    }
                }
            }
        }
    }

    fun downloadMissingPacks() {
        val missing = _state.value.missingPacks
        if (missing.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isDownloadingPacks = true, error = null) }
            val result = runCatching { manager.downloadPacks(missing) }
            _state.update {
                it.copy(
                    isDownloadingPacks = false,
                    error = result.exceptionOrNull()?.let { cause ->
                        TranslationError.DownloadFailed(missing, cause.message ?: "unknown")
                    },
                    missingPacks = if (result.isSuccess) emptyList() else missing,
                )
            }
            if (result.isSuccess) {
                cancelInFlight()
                translateJob = viewModelScope.launch { runTranslation() }
            }
        }
    }

    override fun onCleared() {
        manager.close()
        super.onCleared()
    }

    companion object {
        private const val DEBOUNCE_MS = 450L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                TranslateViewModel(app)
            }
        }
    }
}

/**
 * Applies a language-pair change and recomputes the derived pack hint in one step,
 * so the hint can never drift out of sync with the languages it describes.
 */
private fun TranslateUiState.withLanguages(
    source: String,
    target: String,
    wifiOnly: Boolean,
): TranslateUiState = copy(
    sourceLanguage = source,
    targetLanguage = target,
    wifiOnlyDownloads = wifiOnly,
).recomputePackHint()

private fun TranslateUiState.recomputePackHint(): TranslateUiState {
    val concrete = resolvedSource ?: sourceLanguage.takeIf { it != LanguagePair.AUTO }
    return if (concrete == null) {
        copy(packsNeeded = null, routedViaEnglish = false)
    } else {
        copy(
            packsNeeded = LanguagePair.packCount(concrete, targetLanguage),
            routedViaEnglish = LanguagePair.requiresPivot(concrete, targetLanguage),
        )
    }
}
