package com.zerotranslater.engine

/**
 * The complete error taxonomy. Every failure path in the app maps to exactly one
 * of these; nothing is reported as a bare string, so the UI can render a
 * specific message and a specific recovery action for each case.
 */
sealed interface TranslationError {

    /** Input was null, empty, or whitespace only. */
    data object EmptyInput : TranslationError

    /** Input exceeded [ProcessTextLimits.MAX_CHARS] and was cut short. */
    data object InputTruncated : TranslationError

    /** One or more required language packs are not on the device yet. */
    data class ModelNotDownloaded(val missing: List<String>) : TranslationError

    /** Pack download failed - almost always "no connectivity". */
    data class DownloadFailed(val languages: List<String>, val cause: String) :
        TranslationError

    /**
     * The pair cannot be translated: identical languages, or a pair ML Kit is
     * known to get wrong.
     */
    data class UnsupportedPair(val source: String, val target: String, val reason: String) :
        TranslationError

    /** language-id could not confidently identify the input. */
    data class LowConfidenceDetection(val bestGuess: String?, val confidence: Float) :
        TranslationError

    /** The detected language is not one ML Kit can translate. */
    data class UnsupportedDetectedLanguage(val detected: String) : TranslationError

    /** Play services / ML Kit is unavailable on this device. */
    data object EngineUnavailable : TranslationError

    data class Unknown(val cause: String) : TranslationError
}

/** Input limits, kept in one place because the PROCESS_TEXT path and the UI share them. */
object ProcessTextLimits {
    /**
     * ML Kit's translator takes a single string with no chunking, and the
     * PROCESS_TEXT action can hand us an entire chapter. Beyond a few thousand
     * characters the on-device models become slow enough to look hung, so we cap
     * it and tell the user we did.
     */
    const val MAX_CHARS: Int = 5_000
}

/** Minimum language-id confidence before we trust an auto-detected source. */
object LanguageDetection {
    /**
     * Auto-detect is weakest exactly where it matters most here: PROCESS_TEXT
     * frequently delivers a single word, and a single word carries very little
     * evidence. At 0.5 we would guess wrong often enough to be worse than
     * asking, so we ask instead.
     */
    const val MIN_CONFIDENCE: Float = 0.60f
}
