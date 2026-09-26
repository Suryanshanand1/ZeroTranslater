package com.zerotranslater.engine

import java.util.Locale

/**
 * ML Kit's on-device translation models are **English-pivoted**.
 *
 * Every downloadable model converts between exactly one non-English language and
 * English. There is no direct German->French model. A German->French request is
 * therefore executed as German->English->French and needs **three** packs
 * (`de`, `en`, `fr`) rather than two, and the output quality is measurably worse
 * than a direct English pivot because of the double conversion.
 *
 * The UI surfaces this as a pack count so the user understands why a non-English
 * pair costs more storage than an English-involving one.
 */
object LanguagePair {

    /** BCP-47 code of the pivot language. */
    const val PIVOT: String = "en"

    /** Sentinel source meaning "detect the language of the input". */
    const val AUTO: String = "auto"

    /**
     * Pairs that ML Kit nominally accepts but returns wrong output for.
     *
     * Japanese->Korean (and its inverse) is a long-standing upstream defect where
     * the Japanese model emits English rather than Korean. See
     * https://issuetracker.google.com/issues/369752306
     *
     * Surfacing a clear error beats silently returning garbage.
     */
    private val KNOWN_BAD_PAIRS: Set<Pair<String, String>> = setOf(
        "ja" to "ko",
        "ko" to "ja",
    )

    /** Every language ML Kit can translate, straight from the SDK. Never hardcode this. */
    val supportedLanguages: List<String>
        get() = TranslateLanguageCatalog.supported

    /**
     * Language packs that must be present on the device for [source] -> [target].
     * Pass the *detected* source, not [AUTO].
     */
    fun requiredPacks(source: String, target: String): Set<String> = when {
        source == PIVOT -> setOf(target)
        target == PIVOT -> setOf(source)
        else -> setOf(source, PIVOT, target)
    }

    fun packCount(source: String, target: String): Int = requiredPacks(source, target).size

    /** True when the translation has to route through English and lose quality doing so. */
    fun requiresPivot(source: String, target: String): Boolean =
        source != PIVOT && target != PIVOT

    /**
     * @return null when the pair is usable, otherwise a human-readable reason.
     */
    fun rejectionReason(source: String, target: String): String? = when {
        source == target -> "Source and target language are the same"
        (source to target) in KNOWN_BAD_PAIRS ->
            "ML Kit returns incorrect output for $source -> $target"
        else -> null
    }

    fun isUsable(source: String, target: String): Boolean = rejectionReason(source, target) == null

    /**
     * Human-readable language name from a BCP-47 tag, resolved from the platform
     * locale data so it works offline and needs no bundled lookup table.
     */
    fun displayName(code: String): String {
        if (code == AUTO) return "Auto-detect"
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayLanguage(Locale.getDefault())
        return name.ifBlank { code }.replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }
}

/**
 * Indirection over [com.google.mlkit.nl.translate.TranslateLanguage] so that
 * [LanguagePair.supportedLanguages] can be read in plain JVM unit tests without
 * loading the ML Kit native/Play-services classes.
 */
internal object TranslateLanguageCatalog {
    val supported: List<String> by lazy {
        runCatching { com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages() }
            .getOrDefault(listOf("en"))
    }
}
