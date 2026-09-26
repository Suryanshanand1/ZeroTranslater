package com.zerotranslater.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.zerotranslater.R
import com.zerotranslater.engine.LanguagePair
import com.zerotranslater.engine.ProcessTextLimits
import com.zerotranslater.engine.TranslationError

/**
 * Renders the error taxonomy into user-facing copy.
 *
 * Kept out of the ViewModel on purpose: the ViewModel holds no Context, so string
 * resources stay in the UI layer where lint can verify every lookup, and the same
 * error can be phrased differently in the bottom-sheet overlay if needed.
 */
@Composable
fun TranslationError.toMessage(): String = when (this) {
    TranslationError.EmptyInput -> stringResource(R.string.err_empty_input)

    TranslationError.InputTruncated -> pluralStringResource(
        R.plurals.err_truncated,
        ProcessTextLimits.MAX_CHARS,
        ProcessTextLimits.MAX_CHARS,
    )

    is TranslationError.ModelNotDownloaded ->
        stringResource(R.string.err_model_missing, missing.joinToString(", ") { LanguagePair.displayName(it) })

    is TranslationError.DownloadFailed ->
        stringResource(R.string.err_download_failed)

    is TranslationError.UnsupportedPair ->
        stringResource(
            R.string.err_unsupported_pair,
            LanguagePair.displayName(source),
            LanguagePair.displayName(target),
        )

    is TranslationError.LowConfidenceDetection -> stringResource(R.string.err_low_confidence)

    is TranslationError.UnsupportedDetectedLanguage ->
        stringResource(
            R.string.err_detected_unsupported,
            LanguagePair.displayName(detected),
        )

    TranslationError.EngineUnavailable -> stringResource(R.string.err_download_failed)

    is TranslationError.Unknown -> stringResource(R.string.err_unknown, cause)
}
