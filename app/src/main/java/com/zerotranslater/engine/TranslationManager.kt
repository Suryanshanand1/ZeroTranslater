package com.zerotranslater.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.Closeable

data class TranslateRequest(
    val text: String,
    /** BCP-47 tag, or [LanguagePair.AUTO] to detect. */
    val sourceLanguage: String,
    val targetLanguage: String,
)

sealed interface TranslationOutcome {
    data class Success(
        val translatedText: String,
        /** The source actually used, after auto-detection resolved. */
        val resolvedSource: String,
        val wasTruncated: Boolean,
    ) : TranslationOutcome

    data class Failure(val error: TranslationError) : TranslationOutcome
}

sealed interface DetectionResult {
    data class Confident(val language: String) : DetectionResult
    data class NotConfident(val bestGuess: String?, val confidence: Float) : DetectionResult
    data class UnsupportedLanguage(val detected: String) : DetectionResult
}

/** A language pack and what the pack manager needs to draw a row for it. */
data class PackInfo(
    val language: String,
    val isDownloaded: Boolean,
)

/**
 * Wraps ML Kit's on-device translation.
 *
 * ## Why this class exists
 *
 * Two non-obvious hazards live behind ML Kit's pleasant API:
 *
 * 1. **Native memory.** A [Translator] holds native model memory and must be
 *    closed. The natural way to write this app - "keep a translator around so we
 *    don't reload the model on every keystroke" - is exactly the way to leak
 *    until the process is killed. So this class owns the cache and closes the
 *    outgoing translator on every pair change, in [close], and before deleting a
 *    pack that is in use. Caching without closing is a review-blocking defect.
 *
 * 2. **Blocking.** Every ML Kit call returns a Play services `Task`. Calling
 *    `Tasks.await` on any of them from the main thread would freeze the UI, so all
 *    of them are bridged to `suspend` functions with
 *    `kotlinx-coroutines-play-services`.
 *
 * ## Threading
 *
 * The translator cache is guarded by a plain monitor. Cache mutation is
 * non-suspending and therefore short; the actual `translate()` call happens
 * outside the lock.
 *
 * ## Lifetime
 *
 * Not reusable after [close].
 */
class TranslationManager(
    /**
     * Supplies the network policy for pack downloads. Always non-null because
     * `RemoteModelManager.download` has no single-argument overload; pass
     * `DownloadConditions.Builder().build()` to allow any network.
     */
    private val downloadConditions: () -> DownloadConditions,
) : Closeable {

    private val lock = Any()
    private var cachedPair: Pair<String, String>? = null
    private var cachedTranslator: Translator? = null
    private var closed = false

    private val modelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    /**
     * Note the type: `LanguageIdentification` is the factory, and `getClient()`
     * hands back a [LanguageIdentifier].
     */
    private val languageIdentifier: LanguageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    // ---------------------------------------------------------------- translator

    /**
     * Returns a translator for the pair, reusing the cached instance when the pair
     * is unchanged and closing the previous one when it is not.
     */
    private fun translatorFor(source: String, target: String): Translator = synchronized(lock) {
        check(!closed) { "TranslationManager used after close()" }
        val pair = source to target
        cachedTranslator?.let { existing ->
            if (cachedPair == pair) return existing
            // The critical line: release the outgoing model before taking a new one.
            existing.close()
            cachedTranslator = null
            cachedPair = null
        }
        val fresh = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        cachedPair = pair
        cachedTranslator = fresh
        fresh
    }

    /** Drops and closes the cached translator, e.g. before deleting a pack in use. */
    fun releaseTranslator() = synchronized(lock) {
        cachedTranslator?.close()
        cachedTranslator = null
        cachedPair = null
    }

    // ---------------------------------------------------------------- detection

    suspend fun detectLanguage(text: String): DetectionResult {
        val candidates = runCatching {
            languageIdentifier.identifyPossibleLanguages(text).await()
        }.getOrElse { return DetectionResult.NotConfident(null, 0f) }

        // The SDK reports this sentinel when it genuinely cannot tell.
        val usable = candidates.filterNot {
            it.getLanguageTag() == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
        }
        if (usable.isEmpty()) return DetectionResult.NotConfident(null, 0f)

        val translatable = LanguagePair.supportedLanguages.toSet()
        val best = usable.firstOrNull { it.getLanguageTag() in translatable }
            ?: return DetectionResult.UnsupportedLanguage(usable.first().getLanguageTag())

        return if (best.getConfidence() < LanguageDetection.MIN_CONFIDENCE) {
            DetectionResult.NotConfident(best.getLanguageTag(), best.getConfidence())
        } else {
            DetectionResult.Confident(best.getLanguageTag())
        }
    }

    // ---------------------------------------------------------------- packs

    suspend fun isPackDownloaded(language: String): Boolean = runCatching {
        modelManager.isModelDownloaded(remoteModel(language)).await()
    }.getOrDefault(false)

    /**
     * One round-trip. `getDownloadedModels` takes the model class and returns
     * every pack of that type already on the device, which is far better than
     * asking about 59 languages one at a time.
     */
    suspend fun installedPacks(): Set<String> = runCatching {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .map { it.getLanguage() }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Downloads the given packs.
     *
     * ML Kit exposes **no download size and no progress callback** for on-device
     * translate models - `RemoteModelManager.download` resolves with a bare
     * `Task<Void>` and neither `RemoteModel` nor `TranslateRemoteModel` carries a
     * byte count. The UI therefore shows an indeterminate indicator and states the
     * pack names rather than a size or a percentage. See README ->
     * "Known limitations".
     */
    suspend fun downloadPacks(languages: Collection<String>) {
        val conditions = downloadConditions()
        for (language in languages) {
            modelManager.download(remoteModel(language), conditions).await()
        }
    }

    /**
     * Deletes a pack, releasing the cached translator first if it is currently
     * loaded. Deleting a model that is still mapped throws, so the ordering matters.
     */
    suspend fun deletePack(language: String) {
        synchronized(lock) {
            val pair = cachedPair
            val inUse = pair != null && (pair.first == language || pair.second == language)
            if (inUse) {
                cachedTranslator?.close()
                cachedTranslator = null
                cachedPair = null
            }
        }
        modelManager.deleteDownloadedModel(remoteModel(language)).await()
    }

    private fun remoteModel(language: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(language).build()

    // ---------------------------------------------------------------- translate

    suspend fun translate(request: TranslateRequest): TranslationOutcome {
        val raw = request.text
        if (raw.isBlank()) {
            return TranslationOutcome.Failure(TranslationError.EmptyInput)
        }

        // Auto-detect resolves before anything else, because the pivot logic and the
        // pack check both need a concrete source language.
        val resolvedSource = when (request.sourceLanguage) {
            LanguagePair.AUTO -> when (val detection = detectLanguage(raw)) {
                is DetectionResult.Confident -> detection.language
                is DetectionResult.NotConfident -> return TranslationOutcome.Failure(
                    TranslationError.LowConfidenceDetection(detection.bestGuess, detection.confidence),
                )

                is DetectionResult.UnsupportedLanguage -> return TranslationOutcome.Failure(
                    TranslationError.UnsupportedDetectedLanguage(detection.detected),
                )
            }

            else -> request.sourceLanguage
        }

        val rejection = LanguagePair.rejectionReason(resolvedSource, request.targetLanguage)
        if (rejection != null) {
            return TranslationOutcome.Failure(
                TranslationError.UnsupportedPair(resolvedSource, request.targetLanguage, rejection),
            )
        }

        // Report missing packs instead of silently downloading: a user who did not
        // ask for a large download should be told, not surprised by it.
        val required = LanguagePair.requiredPacks(resolvedSource, request.targetLanguage)
        val missing = required.filterNot { isPackDownloaded(it) }
        if (missing.isNotEmpty()) {
            return TranslationOutcome.Failure(TranslationError.ModelNotDownloaded(missing))
        }

        val truncated = raw.length > ProcessTextLimits.MAX_CHARS
        val payload = if (truncated) raw.take(ProcessTextLimits.MAX_CHARS) else raw

        return try {
            val translator = translatorFor(resolvedSource, request.targetLanguage)
            val text = translator.translate(payload).await()
            TranslationOutcome.Success(
                translatedText = text,
                resolvedSource = resolvedSource,
                wasTruncated = truncated,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A dead translator must not poison the cache.
            releaseTranslator()
            TranslationOutcome.Failure(TranslationError.Unknown(t.message ?: t::class.java.simpleName))
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            cachedTranslator?.close()
            cachedTranslator = null
            cachedPair = null
        }
        runCatching { languageIdentifier.close() }
    }
}
