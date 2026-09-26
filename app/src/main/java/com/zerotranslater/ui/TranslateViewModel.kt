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
import com.zerotranslater.engine.TranslateRequest
import com.zerotranslater.engine.TranslationError
import com.zerotranslater.engine.TranslationManager
import com.zerotranslater.quicktranslate.QuickTranslateService
import com.zerotranslater.engine.TranslationOutcome
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
) {
    val charCount: Int get() = sourceText.length
    val canTranslate: Boolean get() = sourceText.isNotBlank() && !isBusy
    val hasOutput: Boolean get() = output.isNotBlank()
}

class TranslateViewModel(application: Application) : ViewModel() {

    /**
     * Held explicitly because this extends plain [ViewModel], not
     * [AndroidViewModel], so there is no inherited getApplication(). Starting the
     * overlay service needs a context, and the application context is the correct
     * one: the service outlives any activity.
     */
    private val app: Application = application

    private val settingsStore = SettingsStore(application)

    /**
     * Read synchronously by the engine when it builds download conditions, so it is
     * a plain volatile field rather than part of the state flow.
     */
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

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var translateJob: Job? = null

    /**
     * Invalidates the result of any in-flight translation.
     *
     * `Job.cancel()` only *requests* cancellation: it marks the coroutine and
     * returns at once, so an in-flight `manager.translate` can still complete and
     * would then write its output into state that has already moved on - showing a
     * translation of the previous language pair underneath the newly selected pair.
     * Bumping this counter on every input, language, or clearing change lets
     * [runTranslation] recognise its own result as stale and discard it.
     */
    private var translationEpoch = 0

    /**
     * Drops any in-flight translation and clears the busy flag.
     *
     * The flag has to be reset here rather than left to the cancelled coroutine:
     * a cancelled coroutine never reaches the line that clears it, so relying on
     * that strands the UI on a permanent progress indicator.
     */
    private fun cancelInFlight() {
        translationEpoch++
        translateJob?.cancel()
        _state.update { it.copy(isBusy = false) }
    }

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { saved ->
                wifiOnly = saved.wifiOnlyDownloads
                _state.update { current ->
                    current.withLanguages(
                        source = saved.sourceLanguage,
                        target = saved.targetLanguage,
                        wifiOnly = saved.wifiOnlyDownloads,
                    ).copy(quickTranslateEnabled = saved.quickTranslateEnabled)
                }

                // Keep the pill in step with the stored preference. This is also the
                // recovery path: if the service was killed, or the overlay
                // permission was granted while the app was closed, re-launching the
                // app brings the pill back without the user touching the switch.
                if (saved.quickTranslateEnabled) {
                    QuickTranslateService.start(app)
                }
            }
        }
    }

    // ------------------------------------------------------------------- input

    fun onSourceTextChange(value: String) {
        _state.update { it.copy(sourceText = value, error = null) }
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
        // The pair is about to change, so a translation of the old pair is now
        // meaningless. Drop it instead of leaving it on screen under new labels.
        cancelInFlight()
        _state.update {
            it.copy(
                sourceLanguage = code,
                output = "",
                error = null,
                resolvedSource = null,
                inputWasTruncated = false,
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
            )
        }
        viewModelScope.launch { settingsStore.setTargetLanguage(code) }
    }

    /**
     * Swap is only meaningful between two concrete languages.
     *
     * With auto-detect as the source there is no concrete language to promote, so
     * the current target becomes an explicit source and the target falls back to
     * the default. That is the only predictable reading of "swap" here; the
     * alternative - silently doing nothing - would read as a broken button.
     */
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
            )
        }
    }

    private fun clearOutput() {
        _state.update {
            it.copy(output = "", error = null, resolvedSource = null, inputWasTruncated = false)
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWifiOnlyDownloads(enabled) }
    }

    /**
     * Turns the floating pill on or off.
     *
     * The service is started and stopped here rather than left to the composable so
     * that enabling and disabling are one atomic decision: the preference and the
     * running service can never disagree, including if this is called from the
     * settings-recovery path after the service was killed.
     */
    fun setQuickTranslateEnabled(enabled: Boolean) {
        if (enabled) {
            // Returns false when the overlay permission is missing. The UI checks
            // first and routes the user to system settings, so reaching here without
            // it means something changed underneath us; leave the preference off
            // rather than storing a setting that silently does nothing.
            if (!QuickTranslateService.start(app)) return
        } else {
            QuickTranslateService.stop(app)
        }
        viewModelScope.launch { settingsStore.setQuickTranslateEnabled(enabled) }
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

        // The input, the language pair, or both changed while this was in flight.
        // [current] describes a state that no longer exists, so the result is
        // discarded rather than published.
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
    }

    /** Downloads the packs this translation is blocked on, then retries it. */
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
        // Releases the cached ML Kit translator's native memory. Without this the
        // model stays resident for the lifetime of the process.
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

/**
 * Works out how many packs the current pair costs and whether it has to route
 * through English. Returns null packs when the source is still auto-detect,
 * because the real number is not knowable until a language is detected - showing
 * a guess would be worse than showing nothing.
 */
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
